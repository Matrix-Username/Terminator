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
package com.skiy.terminatorkt.memory

import com.skiy.terminatorkt.Pointer
import com.skiy.terminatorkt.checkNull
import com.skiy.terminatorkt.kClassToValueLayout
import com.skiy.terminatorkt.readByte
import com.skiy.terminatorkt.readDouble
import com.skiy.terminatorkt.readFloat
import com.skiy.terminatorkt.readInt
import com.skiy.terminatorkt.readLong
import com.skiy.terminatorkt.readShort
import com.v7878.foreign.Arena
import kotlin.reflect.KClass

/**
 * A type-safe container for 'out' parameters passed to native functions.
 *
 * This class abstracts away the manual memory management required for pointer-based
 * output parameters (e.g., `int* out_value`). You create an instance of `Out<T>`,
 * pass it to a [CallablePointer] call, and then retrieve the result from its [value] property.
 *
 * ### Example:
 * ```kotlin
 * // C function: void get_dimensions(int* width, int* height);
 * val getDimensions = nativeFuncPtr.asCallable<(Pointer, Pointer) -> Unit>()
 *
 * // Create containers for the out parameters.
 * val widthOut = Out(Int::class)
 * val heightOut = Out(Int::class)
 *
 * // The framework will automatically allocate memory for these.
 * getDimensions(widthOut, heightOut)
 *
 * // Retrieve the values written by the native function.
 * println("Width: ${widthOut.value}, Height: ${heightOut.value}")
 * ```
 *
 * @param T The Kotlin type of the out parameter (e.g., Int, Long, Float).
 * @property kClass The [KClass] of type T, used for layout determination.
 */
class Out<T : Any>(val kClass: KClass<T>) {
    private var pointer: Pointer? = null
    private var readValue: T? = null

    /**
     * The value written by the native function.
     * This property reads from the native memory the first time it's accessed
     * and caches the result for subsequent reads.
     *
     * @throws IllegalStateException if the `Out` instance has not been used in a native call yet,
     *         or if the pointer is NULL after the call.
     */
    val value: T
        get() {
            if (readValue != null) return readValue!!

            val p = pointer ?: throw IllegalStateException("Out parameter was not used in a native call, so its value cannot be read.")
            p.checkNull("Pointer for Out parameter is NULL after native call.")

            @Suppress("UNCHECKED_CAST")
            val result = when (kClass) {
                Int::class -> p.readInt()
                Long::class -> p.readLong()
                Float::class -> p.readFloat()
                Double::class -> p.readDouble()
                Byte::class -> p.readByte()
                Short::class -> p.readShort()
                else -> throw NotImplementedError("Reading type '${kClass.simpleName}' from Out<> is not supported.")
            } as T

            this.readValue = result
            return result
        }

    /**
     * INTERNAL USE ONLY.
     * Allocates memory for the out parameter within a given arena and stores the pointer.
     * This is called automatically by the [CallablePointer] framework.
     *
     * @param arena The arena to allocate memory in.
     * @return The newly allocated [Pointer].
     */
    internal fun allocate(arena: Arena): Pointer {
        val layout = kClassToValueLayout(kClass)
        val p = arena.allocate(layout)
        this.pointer = p
        // Reset cached value in case this Out instance is reused
        this.readValue = null
        return p
    }
}

/**
 * A convenience factory function to create an [Out] instance using reified generics.
 * This allows for a cleaner syntax: `val intOut = out<Int>()`.
 *
 * @return An instance of [Out<T>].
 */
inline fun <reified T : Any> out(): Out<T> = Out(T::class)