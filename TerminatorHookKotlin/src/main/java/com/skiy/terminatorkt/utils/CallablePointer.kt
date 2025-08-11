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
package com.skiy.terminatorkt.utils

import android.util.Log
import com.skiy.terminator.hooks.cnative.DlPhdrResolver
import com.skiy.terminatorkt.CString
import com.skiy.terminatorkt.Pointer
import com.skiy.terminatorkt.isNull
import com.skiy.terminatorkt.kClassToValueLayout
import com.skiy.terminatorkt.memory.Out
import com.skiy.terminatorkt.readCString
import com.skiy.terminatorkt.memory.NativeEnum
import com.skiy.terminatorkt.memory.Struct
import com.skiy.terminatorkt.memory.StructCompanion
import com.skiy.terminatorkt.safeArgToString
import com.skiy.terminatorkt.toCString
import com.skiy.terminatorkt.withTempArena
import com.v7878.foreign.FunctionDescriptor
import com.v7878.foreign.Linker
import com.v7878.foreign.SymbolLookup
import com.v7878.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.typeOf

/**
 * A wrapper around a native function pointer that makes it directly and safely callable from Kotlin.
 *
 * This class encapsulates the complexity of the Foreign Function & Memory API, providing:
 * - Caching of the expensive `MethodHandle` object for efficient subsequent calls.
 * - Automatic, temporary memory management for arguments and return values.
 * - Type-safe, automatic conversion between Kotlin `String` and native `const char*`.
 *
 * The primary way to create an instance is via the [asCallable] extension function.
 *
 * @param pointer The native function pointer.
 * @param handle The cached `MethodHandle` for invoking the native function.
 * @param returnKClass The KClass of the function's return type, used for smart conversions.
 */
class CallablePointer(
    val pointer: Pointer,
    private val handle: MethodHandle,
    private val returnKClass: KClass<*>
) {

    /**
     * Invokes the native function with the given arguments, performing automatic type conversions.
     *
     * This operator is the core of the framework's convenience, handling:
     *
     * **Argument Conversions (Kotlin -> Native):**
     * 1.  **[Out] Parameters**: Automatically allocates memory for `Out<T>` arguments.
     * 2.  **[Struct]s**: Automatically passes the struct's underlying pointer.
     * 3.  **[String]s**: Automatically converts Kotlin `String` to native C-strings.
     * 4.  **Arrays**: Automatically converts Kotlin arrays (`IntArray`, `ByteArray`, etc.)
     *     to native pointers by copying their content.
     * 5.  **[NativeEnum]s**: Automatically converts enums to their integer `value`.
     *
     * **Return Value Conversions (Native -> Kotlin):**
     * 6.  **`CString`**: Automatically reads a `const char*` pointer back into a Kotlin `String`.
     * 7.  **`NativeEnum`**: Automatically converts an integer result back into a Kotlin `NativeEnum`.
     * 8.  **`Struct`**: Automatically wraps a returned raw pointer (e.g., `UserProfile*`) into
     *     a safe Kotlin [Struct] object (`UserProfile`).
     *
     * **Memory Management:**
     * 9.  A temporary memory arena is created and destroyed for each call, ensuring that all
     *     memory allocated for arguments (like strings and arrays) is automatically freed.
     *
     * @param args The arguments to pass to the function. Can be a mix of primitive types,
     *             and the special types listed above.
     * @return The result of the function call, with automatic conversion applied where applicable.
     */
    operator fun invoke(vararg args: Any?): Any? {
        try {
            return withTempArena { arena ->
                val nativeArgs = args.map { convertKotlinToNative(it, arena) }.toTypedArray()

                val result = handle.invokeWithArguments(*nativeArgs)

                return@withTempArena convertNativeToKotlin(result, returnKClass)
            }
        } catch (t: Throwable) {
            val formattedArgs = args.mapIndexed { i, arg ->
                "  - arg$i: ${safeArgToString(arg)}"
            }.joinToString("\n")

            val errorMessage = """
            Failed to invoke native function at address 0x${pointer.address().toString(16)}
            Cause: ${t.message ?: t.javaClass.simpleName}
            Arguments:
            $formattedArgs
        """.trimIndent()

            throw NativeInvocationException(errorMessage, t)
        }
    }
}

val callableCache = ConcurrentHashMap<String, CallablePointer>()

/**
 * Creates a [CallablePointer] from a native function pointer.
 *
 * This function uses a `reified` type parameter to automatically infer the function's
 * signature (parameter types and return type) from the provided Kotlin function type.
 *
 * ### Example Usage:
 *
 * **C/C++ Function:** `int sum(int a, int b);`
 * ```kotlin
 * val sumPtr: Pointer = ...
 * val sum = sumPtr.asCallable<(Int, Int) -> Int>()
 * val result = sum(10, 20) as Int // result = 30
 * ```
 *
 * **C/C++ Function:** `const char* greet(const char* name);`
 * ```kotlin
 * val greetPtr: Pointer = ...
 * val greet = greetPtr.asCallable<(CString) -> CString>()
 * val result = greet("World") as String // result = "Hello, World" (assuming C logic)
 * ```
 *
 * @param T The Kotlin function type, e.g., `() -> Unit`, `(Int) -> String`, `(Pointer, Long) -> Double`.
 * @return A [CallablePointer] instance ready to be invoked.
 * @throws NullPointerException if the pointer is NULL.
 * @throws IllegalArgumentException if the function type `T` is not a valid `Function` or contains unsupported types.
 */
@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T : Function<*>> Pointer.asCallable(): CallablePointer {
    if (this.isNull) {
        throw NullPointerException("Cannot create a callable from a NULL pointer.")
    }

    val functionType = typeOf<T>()
    val cacheKey = "${this.address()}:${functionType}"

    callableCache[cacheKey]?.let { return it }

    val typeArgs = functionType.arguments

    val paramKClasses: List<KClass<*>>
    val returnKClass: KClass<*>

    if (typeArgs.isEmpty()) {
        paramKClasses = emptyList()
        returnKClass = Unit::class
    } else {
        paramKClasses = typeArgs.dropLast(1).map {
            (it.type?.classifier ?: throw IllegalArgumentException("Invalid parameter type: $it")) as KClass<*>
        }
        returnKClass = (typeArgs.last().type?.classifier ?: throw IllegalArgumentException("Invalid return type")) as KClass<*>
    }

    // Convert KClass types to FFM ValueLayouts using our existing utility.
    val returnLayout = kClassToValueLayout(returnKClass)
    val paramLayouts = paramKClasses.map { kClassToValueLayout(it) }.toTypedArray()

    // Create the descriptor and handle once.
    val descriptor = FunctionDescriptor.of(returnLayout, *paramLayouts)
    val handle = Linker.nativeLinker().downcallHandle(this, descriptor)

    // Pass all necessary info to the CallablePointer constructor.
    val newCallable = CallablePointer(this, handle, returnKClass)

    callableCache[cacheKey] = newCallable
    return newCallable
}

class NativeInvocationException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

fun addressByTarget(libraryName: String, symbol: String): Long {
    val symbolAddress = SymbolLookup.loaderLookup().find(symbol)
        .map { it.nativeAddress() }
        .orElseThrow { NoSuchMethodError("Symbol '$symbol' not found in any loaded library.") }

    val baseAddress = DlPhdrResolver.getBaseAddress(libraryName)
    if (baseAddress == 0L) {
        throw UnsatisfiedLinkError("Could not resolve base address for '$libraryName'.")
    }

    val offset = symbolAddress - baseAddress

    if (offset >= 0 && offset < 300 * 1024 * 1024) {
        Log.d("HookBuilder", "Symbol '$symbol' verified in '$libraryName' at base 0x${baseAddress.toString(16)} + offset 0x${offset.toString(16)}")
        return symbolAddress
    } else {
        throw NoSuchMethodError("Symbol '$symbol' was found, but it does not belong to the requested library '$libraryName'.")
    }
}

fun addressByTarget(libraryName: String, offset: Long): Long {
    val baseAddress = DlPhdrResolver.getBaseAddress(libraryName)
    if (baseAddress == 0L) {
        throw UnsatisfiedLinkError("Could not resolve base address for '$libraryName' using dl_iterate_phdr.")
    }
    return baseAddress + offset
}