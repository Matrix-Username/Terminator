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

import com.skiy.terminatorkt.CString
import com.skiy.terminatorkt.Pointer
import com.skiy.terminatorkt.isNull
import com.skiy.terminatorkt.memory.NativeEnum
import com.skiy.terminatorkt.memory.Out
import com.skiy.terminatorkt.memory.Struct
import com.skiy.terminatorkt.memory.StructCompanion
import com.skiy.terminatorkt.readCString
import com.skiy.terminatorkt.toCString
import com.v7878.foreign.Arena
import com.v7878.foreign.ValueLayout
import kotlin.reflect.KClass

/**
 * Converts a Kotlin object to its native representation for a function call.
 * This function handles special framework types and allocates memory where necessary.
 *
 * @param arg The Kotlin object.
 * @param arena The arena used for temporary allocations (e.g., for strings, arrays).
 * @return A native-compatible value (e.g., Pointer, Int, Long).
 */
internal fun convertKotlinToNative(arg: Any?, arena: Arena): Any? {
    return when (arg) {
        // Special containers
        is Out<*> -> arg.allocate(arena)

        // Framework types
        is Struct -> arg.pointer
        is NativeEnum -> arg.value

        // Standard library types that need conversion
        is String -> arg.toCString(arena)
        is Boolean -> if (arg) 1 else 0

        // Arrays
        is IntArray -> arena.allocateFrom(ValueLayout.JAVA_INT, *arg)
        is LongArray -> arena.allocateFrom(ValueLayout.JAVA_LONG, *arg)
        is ByteArray -> arena.allocateFrom(ValueLayout.JAVA_BYTE, *arg)
        is FloatArray -> arena.allocateFrom(ValueLayout.JAVA_FLOAT, *arg)
        is DoubleArray -> arena.allocateFrom(ValueLayout.JAVA_DOUBLE, *arg)
        is ShortArray -> arena.allocateFrom(ValueLayout.JAVA_SHORT, *arg)
        is CharArray -> arena.allocateFrom(ValueLayout.JAVA_CHAR, *arg)

        is Int -> arena.allocateFrom(ValueLayout.JAVA_INT, arg)
        is Long -> arena.allocateFrom(ValueLayout.JAVA_LONG, arg)
        is Byte -> arena.allocateFrom(ValueLayout.JAVA_BYTE, arg)
        is Float -> arena.allocateFrom(ValueLayout.JAVA_FLOAT, arg)
        is Double -> arena.allocateFrom(ValueLayout.JAVA_DOUBLE, arg)
        is Short -> arena.allocateFrom(ValueLayout.JAVA_SHORT, arg)
        is Char -> arena.allocateFrom(ValueLayout.JAVA_CHAR, arg)

        // Primitives and other pointers are passed as-is
        else -> arg
    }
}

/**
 * Converts a value received from native code into a high-level Kotlin object.
 * This is the reverse of [convertKotlinToNative].
 *
 * @param nativeValue The raw value from the native call (e.g., Pointer, Int).
 * @param targetKClass The expected high-level Kotlin type.
 * @return The converted Kotlin object.
 */
@Suppress("UNCHECKED_CAST")
fun convertNativeToKotlin(nativeValue: Any?, targetKClass: KClass<*>): Any? {
    if (nativeValue == null) return null

    // 1. Pointer conversions
    if (nativeValue is Pointer) {
        if (nativeValue.isNull) return null

        if (targetKClass == CString::class) {
            return nativeValue.readCString()
        }

        if (Struct::class.java.isAssignableFrom(targetKClass.java)) {
            val companion = targetKClass.java.getField("Companion").get(null) as StructCompanion<Struct>
            return companion.of(nativeValue)
        }
    }

    // 2. Enum conversion
    if (nativeValue is Number && NativeEnum::class.java.isAssignableFrom(targetKClass.java)) {
        val companion = targetKClass.java.getField("Companion").get(null) as NativeEnum.Companion<NativeEnum>
        return companion.fromValue(nativeValue.toInt())
            ?: throw IllegalArgumentException("Native function returned invalid enum value: ${nativeValue.toInt()} for ${targetKClass.simpleName}")
    }

    // 3. Numeric and Boolean conversions
    if (nativeValue is Number) {
        return when (targetKClass) {
            // Standard number types
            Int::class -> nativeValue.toInt()
            Long::class -> nativeValue.toLong()
            Float::class -> nativeValue.toFloat()
            Double::class -> nativeValue.toDouble()
            Short::class -> nativeValue.toShort()
            Byte::class -> nativeValue.toByte()
            // Special case: C-style boolean (0 is false, non-zero is true)
            Boolean::class -> nativeValue.toLong() != 0L
            // If target is not a number/boolean, return the original number
            else -> nativeValue
        }
    }

    // 4. Handle cases where native value is already a boolean
    if (nativeValue is Boolean) {
        return when (targetKClass) {
            Boolean::class -> nativeValue
            // Convert boolean to C-style integer
            Int::class -> if (nativeValue) 1 else 0
            Long::class -> if (nativeValue) 1L else 0L
            else -> nativeValue
        }
    }

    // 5. Fallback for primitives or non-special pointers, where the value is already correct
    return nativeValue
}