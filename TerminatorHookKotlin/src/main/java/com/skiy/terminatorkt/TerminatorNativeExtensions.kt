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

import com.skiy.terminator.hooks.cnative.DlPhdrResolver
import com.skiy.terminatorkt.memory.Struct
import com.skiy.terminatorkt.memory.StructCompanion
import com.v7878.foreign.Arena
import com.v7878.foreign.MemorySegment
import com.v7878.foreign.ValueLayout
import com.v7878.unsafe.AndroidUnsafe
import java.util.Locale
import kotlin.reflect.KClass

//Type Aliases for better readability

/** A typealias for [MemorySegment] to represent a native pointer. */
typealias Pointer = MemorySegment
/** A typealias for [String] when it represents a C-style, null-terminated string pointer. */
typealias CString = String
/** A typealias for [IntArray] when it represents a pointer to a C array of ints. */
typealias CIntArray = IntArray
/** A typealias for [LongArray] when it represents a pointer to a C array of longs. */
typealias CLongArray = LongArray
/** A typealias for [ByteArray] when it represents a pointer to a C array of bytes. */
typealias CByteArray = ByteArray

/** Checks if the pointer is null (i.e., its address is 0). */
val Pointer.isNull: Boolean
    get() = this.address == 0L

/**
 * Throws a [NullPointerException] if the pointer is null.
 * @param name An optional name for the pointer to include in the exception message.
 * @return The non-null pointer.
 */
fun Pointer.checkNull(name: String = "Pointer"): Pointer {
    if (isNull) throw NullPointerException("$name is null")
    return this
}

/** A convenience property to get the native address of the pointer as a [Long]. */
val Pointer.address: Long
    get() = this.nativeAddress()

/** Creates a [Pointer] from a [Long] address. */
fun Long.toPointer(): Pointer = MemorySegment.ofAddress(this)

/**
 * Pointer arithmetic: returns a new pointer shifted by the given byte offset.
 * Example: `val p2 = p1 + 8`
 * @param offset The number of bytes to offset.
 * @return A new [Pointer] instance (a slice) at the new address.
 */
operator fun Pointer.plus(offset: Long): Pointer {
    return this.asSlice(offset)
}
operator fun Pointer.plus(offset: Int): Pointer = this.plus(offset.toLong())

/**
 * Indexed access: returns a new pointer shifted by `index * layout.byteSize()` bytes.
 * Useful for navigating arrays of native structures.
 * Example: `val p3 = p1[3, MyStructLayout]`
 * @param index The element index.
 * @param layout The [ValueLayout] of the element, used to calculate the size.
 * @return A new [Pointer] instance (a slice) at the new address.
 */
operator fun Pointer.get(index: Long, layout: ValueLayout): Pointer {
    return this.asSlice(index * layout.byteSize())
}
operator fun Pointer.get(index: Int, layout: ValueLayout): Pointer = this.get(index.toLong(), layout)


// --- Primitive Type Read/Write Extensions ---

/** Reads a [Byte] from the pointer's address with an optional offset. */
fun Pointer.readByte(offset: Long = 0): Byte = this.get(ValueLayout.JAVA_BYTE, offset)
/** Writes a [Byte] to the pointer's address with an optional offset. */
fun Pointer.writeByte(offset: Long = 0, value: Byte) = this.set(ValueLayout.JAVA_BYTE, offset, value)

/** Reads a [Short] from the pointer's address with an optional offset. */
fun Pointer.readShort(offset: Long = 0): Short = this.get(ValueLayout.JAVA_SHORT, offset)
/** Writes a [Short] to the pointer's address with an optional offset. */
fun Pointer.writeShort(offset: Long = 0, value: Short) = this.set(ValueLayout.JAVA_SHORT, offset, value)

/** Reads an [Int] from the pointer's address with an optional offset. */
fun Pointer.readInt(offset: Long = 0): Int = this.get(ValueLayout.JAVA_INT, offset)
/** Writes an [Int] to the pointer's address with an optional offset. */
fun Pointer.writeInt(offset: Long = 0, value: Int) = this.set(ValueLayout.JAVA_INT, offset, value)

/** Reads a [Long] from the pointer's address with an optional offset. */
fun Pointer.readLong(offset: Long = 0): Long = this.get(ValueLayout.JAVA_LONG, offset)
/** Writes a [Long] to the pointer's address with an optional offset. */
fun Pointer.writeLong(offset: Long = 0, value: Long) = this.set(ValueLayout.JAVA_LONG, offset, value)

/** Reads a [Float] from the pointer's address with an optional offset. */
fun Pointer.readFloat(offset: Long = 0): Float = this.get(ValueLayout.JAVA_FLOAT, offset)
/** Writes a [Float] to the pointer's address with an optional offset. */
fun Pointer.writeFloat(offset: Long = 0, value: Float) = this.set(ValueLayout.JAVA_FLOAT, offset, value)

/** Reads a [Double] from the pointer's address with an optional offset. */
fun Pointer.readDouble(offset: Long = 0): Double = this.get(ValueLayout.JAVA_DOUBLE, offset)
/** Writes a [Double] to the pointer's address with an optional offset. */
fun Pointer.writeDouble(offset: Long = 0, value: Double) = this.set(ValueLayout.JAVA_DOUBLE, offset, value)

/** Reads another pointer from the memory location at the specified offset. */
fun Pointer.readPointer(offset: Long = 0): Pointer = this.get(ValueLayout.ADDRESS, offset)
/** Writes another pointer's address to the memory location at the specified offset. */
fun Pointer.writePointer(offset: Long = 0, value: Pointer) = this.set(ValueLayout.ADDRESS, offset, value)


// --- String Manipulation ---

/**
 * Reads a null-terminated C-style string from the memory location.
 * This function will automatically [reinterpret] a zero-length pointer to a readable size.
 * WARNING: The pointer must be valid, otherwise this may cause a crash.
 * @param maxSize The maximum number of bytes to search for a null terminator. Defaults to page size.
 * @return The read [String].
 */
fun Pointer.readCString(maxSize: Long = AndroidUnsafe.PAGE_SIZE.toLong()): String {
    val readableSegment = if (this.byteSize() > 0) this else this.reinterpret(maxSize)
    return readableSegment.getString(0)
}

/**
 * Converts a Kotlin [String] to a native, null-terminated memory segment.
 * @param arena The [Arena] in which to allocate memory for the native string.
 * @return A [Pointer] to the newly allocated native string.
 */
fun String.toCString(arena: Arena): Pointer {
    val bytesWithNull = this.toByteArray(Charsets.UTF_8) + 0.toByte()
    return arena.allocateFrom(ValueLayout.JAVA_BYTE, *bytesWithNull)
}


// --- Array Manipulation ---

/**
 * Reads a specified number of integers from memory into an [IntArray].
 * @param offset The starting offset in bytes from the pointer's address.
 * @param count The number of integers to read.
 * @return The resulting [IntArray].
 */
fun Pointer.readIntArray(offset: Long = 0, count: Int): IntArray {
    val slice = this.asSlice(offset, (count * ValueLayout.JAVA_INT.byteSize()))
    return slice.toArray(ValueLayout.JAVA_INT)
}

/**
 * Writes an [IntArray] to the memory location.
 * @param offset The starting offset in bytes from the pointer's address.
 * @param array The [IntArray] to write.
 */
fun Pointer.writeIntArray(offset: Long = 0, array: IntArray) {
    MemorySegment.copy(array, 0, this, ValueLayout.JAVA_INT, offset, array.size)
}

/**
 * Reads a specified number of longs from memory into a [LongArray].
 * @param offset The starting offset in bytes from the pointer's address.
 * @param count The number of longs to read.
 * @return The resulting [LongArray].
 */
fun Pointer.readLongArray(offset: Long = 0, count: Int): LongArray {
    val slice = this.asSlice(offset, (count * ValueLayout.JAVA_LONG.byteSize()))
    return slice.toArray(ValueLayout.JAVA_LONG)
}

/**
 * Writes a [LongArray] to the memory location.
 * @param offset The starting offset in bytes from the pointer's address.
 * @param array The [LongArray] to write.
 */
fun Pointer.writeLongArray(offset: Long = 0, array: LongArray) {
    MemorySegment.copy(array, 0, this, ValueLayout.JAVA_LONG, offset, array.size)
}

/**
 * Internal utility to map a Kotlin [KClass] to its corresponding FFM [ValueLayout].
 * Used for building [com.v7878.foreign.FunctionDescriptor].
 * @param cls The Kotlin class.
 * @return The matching [ValueLayout].
 * @throws IllegalArgumentException if the type is not supported.
 */
fun kClassToValueLayout(cls: KClass<*>): ValueLayout {

    if (Struct::class.java.isAssignableFrom(cls.java)) {
        return ValueLayout.ADDRESS
    }

    return when (cls) {
        Int::class -> ValueLayout.JAVA_INT
        Long::class -> ValueLayout.JAVA_LONG
        Float::class -> ValueLayout.JAVA_FLOAT
        Double::class -> ValueLayout.JAVA_DOUBLE
        Byte::class -> ValueLayout.JAVA_BYTE
        Char::class -> ValueLayout.JAVA_CHAR
        Short::class -> ValueLayout.JAVA_SHORT
        Boolean::class -> ValueLayout.JAVA_BOOLEAN
        Pointer::class -> ValueLayout.ADDRESS
        Void::class, Nothing::class -> ValueLayout.ADDRESS
        CString::class -> ValueLayout.ADDRESS
        CIntArray::class -> ValueLayout.ADDRESS
        CLongArray::class -> ValueLayout.ADDRESS
        CByteArray::class -> ValueLayout.ADDRESS
        else -> throw IllegalArgumentException("Unsupported type for function descriptor: ${cls.simpleName}")
    }
}

/**
 * Executes a block of code within the scope of a temporary, confined arena,
 * which is automatically closed after the block completes.
 *
 * @param block The code to execute. It receives the temporary arena as its context.
 * @return The result of the block.
 */
inline fun <R> withTempArena(block: (arena: Arena) -> R): R {
    Arena.ofConfined().use { arena ->
        return block(arena)
    }
}

/**
 * A convenience extension to allocate a primitive value in an arena and get a pointer to it.
 */
inline fun <reified T : Any> T.toNative(arena: Arena): Pointer {
    val layout = kClassToValueLayout(T::class)
    val pointer = arena.allocate(layout)
    when (this) {
        is Int -> pointer.writeInt(0, this)
        is Long -> pointer.writeLong(0, this)
        is Float -> pointer.writeFloat(0, this)
        is Double -> pointer.writeDouble(0, this)
        is Byte -> pointer.writeByte(0, this)
        is Short -> pointer.writeShort(0, this)
        else -> throw IllegalArgumentException("Unsupported type for toNative: ${T::class}")
    }
    return pointer
}

/**
 * Creates a human-readable string representation of hook arguments.
 *
 * @param arg The argument to convert to a string.
 * @return A formatted string representation of the argument.
 */
fun safeArgToString(arg: Any?): String {
    if (arg == null) return "null"

    if (arg is Pointer) {
        return try {
            if (arg.isNull) {
                "Pointer(NULL)"
            } else {
                val address = arg.nativeAddress()

                val libraryInfo = DlPhdrResolver.findLibraryForAddress(address)

                if (libraryInfo != null) {
                    val offset = address - libraryInfo.baseAddress()
                    "Pointer(${libraryInfo.name()}+0x${offset.toString(16)})"
                } else {
                    "Pointer(0x${address.toString(16)})"
                }
            }
        } catch (t: Throwable) {
            "Pointer(0x${(arg as Pointer).address.toString(16)} with lookup error: ${t.javaClass.simpleName})"
        }
    }

    return when (arg) {
        is IntArray -> arg.joinToString(prefix = "[", postfix = "]")
        is LongArray -> arg.joinToString(prefix = "[", postfix = "]")
        is ByteArray -> arg.joinToString(prefix = "[", postfix = "]") { String.format("%02x", it) }
        is Int, is Long, is Float, is Double, is Short, is Byte, is Boolean -> arg.toString()
        is String -> "\"$arg\""
        else -> {
            val className = arg.javaClass.simpleName
            val hashCode = try { arg.hashCode().toString(16) } catch (t: Throwable) { "?" }
            "Object($className@$hashCode)"
        }
    }
}

/**
 * Creates a detailed, human-readable dump of the struct's content.
 *
 * ### Example Usage:
 * ```kotlin
 * val player = PlayerInfo.create(arena)
 * player.id = 4144
 * player.hp = 80
 * player.name = "John Doe"
 * player.position.x = 10
 *
 * println(player.dump())
 * ```
 *
 * ### Example Output:
 * ```
 * --- Dump of PlayerInfo at 0x7b1a4c8a00 ---
 *   [0x00] id       (Long)    : 4144
 *   [0x08] hp       (Int)     : 80
 *   [0x10] position (Point)   : Point(x=10, y=0)
 *   [0x18] name     (String)  : "John Doe"
 * --------------------------------------------
 * ```
 *
 * @receiver The [Struct] instance to dump.
 * @return A formatted string containing the dump of the struct's memory.
 *
 * @apiNote This function relies on reflection (`KProperty` and `javaField`) to get
 *          property values. It assumes that property names in the class match the
 *          names declared in the companion object.
 */
fun Struct.dump(): String {
    val companion = this::class.java.getField("Companion").get(null) as StructCompanion<*>
    val sb = StringBuilder("--- Dump of ${this::class.simpleName} at 0x${pointer.address.toString(16)} ---\n")

    companion.fields.values.forEach { field ->
        val offset = companion.getOffset(field.name)
        val value = try {
            this::class.java.getDeclaredMethod("get${field.name.capitalize(Locale.ROOT)}").invoke(this)
        } catch (e: Exception) { "???" }

        sb.append("  [0x${offset.toString(16).padStart(2, '0')}] ${field.name.padEnd(8)} (${field.name}) : $value\n")
    }
    sb.append("-----------------------------------------")
    return sb.toString()
}