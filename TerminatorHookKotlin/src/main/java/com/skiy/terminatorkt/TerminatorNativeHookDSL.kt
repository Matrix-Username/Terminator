/*
 * Copyright 2025 Nazar Sladkovskyi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.skiy.terminatorkt

import android.util.Log
import com.skiy.terminator.hooks.cnative.TerminatorNativeHook
import com.skiy.terminatorkt.utils.addressByTarget
import com.v7878.foreign.Arena
import com.v7878.foreign.FunctionDescriptor
import java.lang.invoke.MethodHandle
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * Provides the context for a native function call within a hook callback.
 * It offers safe access to arguments, the original function, and a temporary memory arena.
 *
 * @param original The [MethodHandle] to invoke the original, unhooked function.
 * @param args The arguments passed to the native function.
 * @param arena A temporary, confined [Arena] that is valid only for the duration of the hook callback.
 *              Used for allocating native memory that is automatically freed upon return.
 * @param paramTypes A list of the expected parameter types ([KClass]) for smart argument dumping.
 */
class HookContext(
    private val original: MethodHandle,
    private val args: Array<out Any?>,
    val arena: Arena,
    internal val paramTypes: List<KClass<*>>
) {
    /**
     * Provides access to the 'this' pointer (also known as 'self').
     * IMPORTANT: Use this property only when hooking non-static C++ methods,
     * where the first argument is a pointer to the class instance.
     * @return The 'this' [Pointer].
     * @throws IllegalStateException if the method has no arguments.
     */
    val self: Pointer
        get() {
            if (args.isEmpty()) {
                throw IllegalStateException("Cannot get 'self'. The method has no arguments.")
            }
            return args[0] as Pointer
        }

    /**
     * Gets a function argument by its index and casts it to the specified type.
     * @param index The index of the argument. For C++ instance methods, remember that 'this' is at index 0.
     * @return The argument cast to type [T].
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> arg(index: Int): T = args[index] as T

    /**
     * Invokes the original (unhooked) function with the original arguments.
     * @return The result of the original function call.
     */
    fun callOriginal(): Any? = original.invokeWithArguments(*args)

    /**
     * Invokes the original (unhooked) function with a new set of arguments.
     * @param newArgs The substituted arguments to pass to the original function.
     * @return The result of the original function call.
     */
    fun callOriginal(vararg newArgs: Any?): Any? = original.invokeWithArguments(*newArgs)

    /**
     * Creates a smart dump of the function arguments for logging purposes.
     * It correctly identifies the 'self' pointer and attempts to read C-style strings.
     * @return A list of formatted strings, each representing an argument.
     */
    fun dumpArgsSmart(): List<String> {
        val argStrings = args.mapIndexed { index, arg ->
            val expectedType = paramTypes.getOrNull(index)

            val valueStr = if (expectedType == CString::class && arg is Pointer && !arg.isNull) {
                try {
                    "\"${arg.readCString()}\""
                } catch (t: Throwable) {
                    "CString(ReadError: ${t.javaClass.simpleName})"
                }
            } else {
                safeArgToString(arg)
            }

            valueStr
        }
        return argStrings
    }
}

/**
 * Represents an installed hook, providing a handle to uninstall it.
 * Implements [AutoCloseable] to support `try-with-resources` (or `use` in Kotlin) for scoped hooks.
 *
 * @param id A unique identifier for the hook.
 * @param hookManager The manager responsible for this hook.
 */
class ActiveHook internal constructor(internal val id: String, internal val hookManager: NidaHookManager) : AutoCloseable {
    /**
     * Uninstalls the hook, restoring the original function bytes.
     */
    fun uninstall() {
        hookManager.uninstall(id)
    }

    /**
     * Implements [AutoCloseable] to automatically uninstall the hook when used in a `use` block.
     */
    override fun close() {
        uninstall()
    }
}

/**
 * A DSL builder for configuring and installing a native function hook.
 *
 * @param hookManager The [NidaHookManager] that will manage the lifecycle of the created hook.
 */
class HookBuilder internal constructor(private val hookManager: NidaHookManager) {
    private var target: () -> Long = { 0L }
    private var returnType: KClass<*>? = null
    private var paramTypes: List<KClass<*>> = emptyList()
    private lateinit var callback: HookContext.() -> Any?

    /**
     * Specifies the hook target by library name and symbol name.
     * This method performs a lookup and verifies that the found symbol belongs to the specified library.
     * @param libraryName The file name of the library (e.g., "libc.so", "libcustom.so").
     * @param symbol The name of the symbol to hook.
     * @throws NoSuchMethodError if the symbol is not found or does not belong to the specified library.
     * @throws UnsatisfiedLinkError if the base address of the library cannot be resolved.
     */
    fun target(libraryName: String, symbol: String) {
        target = {
            addressByTarget(libraryName, symbol)
        }
    }

    /**
     * Specifies the hook target by an offset from the base address of a library.
     * @param libraryName The file name of the library (e.g., "libc.so").
     * @param offset The offset in bytes from the library's base address.
     */
    fun target(libraryName: String, offset: Long) {
        target = {
            addressByTarget(libraryName, offset)
        }
    }

    /**
     * Specifies the hook target by its absolute memory address.
     * @param address The absolute native address.
     */
    fun target(address: Long) {
        target = { address }
    }

    /**
     * Specifies the return type of the hooked function.
     * @param type The [KClass] of the return value.
     */
    fun <T : Any> returns(type: KClass<T>) {
        this.returnType = type
    }

    /**
     * Specifies the parameter types of the hooked function.
     * @param types A vararg of [KClass] representing the function's parameters in order.
     */
    fun params(vararg types: KClass<*>) {
        this.paramTypes = types.toList()
    }

    /**
     * Defines the callback logic to be executed when the hooked function is called.
     * @param block A lambda with [HookContext] as its receiver.
     */
    fun onCalled(block: HookContext.() -> Any?) {
        this.callback = block
    }

    /**
     * Finalizes the configuration and installs the hook.
     * @return An [ActiveHook] instance that can be used to uninstall the hook.
     * @throws IllegalStateException if the configuration is incomplete or the target address is invalid.
     */
    internal fun install(): ActiveHook {
        val finalAddress = try {
            target()
        } catch (e: Exception) {
            throw IllegalStateException("Failed to resolve hook target", e)
        }

        if (finalAddress == 0L) throw IllegalStateException("Hook target address is zero.")

        val ret = returnType ?: throw IllegalStateException("Return type not specified! Use returns<T>().")
        val descriptor = FunctionDescriptor.of(kClassToValueLayout(ret), *paramTypes.map { kClassToValueLayout(it) }.toTypedArray())

        val hookId = "hook_0x${finalAddress.toString(16)}"

        val hookCallback = TerminatorNativeHook.HookCallback { original, args ->
            // Create a new confined arena for each invocation to ensure memory safety.
            Arena.ofConfined().use { arena ->
                callback(HookContext(original, args, arena, this.paramTypes))
            }
        }

        hookManager.install(hookId, finalAddress, hookCallback, descriptor)
        return ActiveHook(hookId, hookManager)
    }
}

/**
 * Manages the lifecycle of active hooks.
 * It ensures that hooks are installed correctly and provides a central point for uninstallation.
 */
class NidaHookManager {
    private val activeHooks = ConcurrentHashMap<String, TerminatorNativeHook>()

    /**
     * Installs a hook using the underlying [TerminatorNativeHook].
     * @param id A unique identifier for the hook.
     * @param address The absolute address to hook.
     * @param callback The raw Java callback.
     * @param descriptor The function descriptor.
     */
    internal fun install(id: String, address: Long, callback: TerminatorNativeHook.HookCallback, descriptor: FunctionDescriptor) {
        if (activeHooks.containsKey(id)) {
            Log.w("NidaHookManager", "Hook $id already installed.")
            return
        }
        try {
            val hook = TerminatorNativeHook.install(address, callback, descriptor)
            activeHooks[id] = hook
        } catch (t: Throwable) {
            Log.e("NidaHookManager", "Failed to install hook $id", t)
        }
    }

    /**
     * Uninstalls a specific hook by its ID.
     * @param id The unique identifier of the hook to uninstall.
     */
    internal fun uninstall(id: String) {
        activeHooks.remove(id)?.close()
    }

    /**
     * Uninstalls all hooks currently managed by this instance.
     */
    fun uninstallAll() {
        activeHooks.keys.forEach { uninstall(it) }
    }
}

/**
 * A global singleton object for managing hooks that should persist for a long duration.
 */
object TerminatorNative {
    private val manager = NidaHookManager()

    /**
     * Creates and installs a long-lived, global hook.
     * @param block The DSL configuration block for the hook.
     * @return An [ActiveHook] handle.
     */
    fun hook(block: HookBuilder.() -> Unit): ActiveHook = HookBuilder(manager).apply(block).install()

    /**
     * Uninstalls all hooks managed by the global [TerminatorNative] instance.
     */
    fun uninstallAll() = manager.uninstallAll()
}

/**
 * A convenience extension function for specifying the return type using reified generics.
 * Allows for cleaner syntax: `returns<String>()` instead of `returns(String::class)`.
 */
inline fun <reified T : Any> HookBuilder.returns() = returns(T::class)