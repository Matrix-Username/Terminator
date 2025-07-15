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
import com.skiy.terminatorkt.readCString
import com.skiy.terminatorkt.readDouble
import com.skiy.terminatorkt.readFloat
import com.skiy.terminatorkt.readInt
import com.skiy.terminatorkt.readLong
import com.skiy.terminatorkt.readPointer
import com.skiy.terminatorkt.writeByte
import com.skiy.terminatorkt.writeDouble
import com.skiy.terminatorkt.writeFloat
import com.skiy.terminatorkt.writeInt
import com.skiy.terminatorkt.writeLong
import com.skiy.terminatorkt.writePointer
import com.v7878.foreign.AddressLayout
import com.v7878.foreign.Arena
import com.v7878.foreign.GroupLayout
import com.v7878.foreign.MemoryLayout
import com.v7878.foreign.MemorySegment
import com.v7878.foreign.SequenceLayout
import com.v7878.foreign.ValueLayout
import java.nio.charset.StandardCharsets
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** A descriptor for a field within a struct layout. It holds the necessary metadata. */
sealed class FieldDescriptor<T>(
    internal val companion: StructCompanion<*>,
    internal val memoryLayout: MemoryLayout
) {
    internal lateinit var name: String
}

class PrimitiveField<T>(companion: StructCompanion<*>, memoryLayout: MemoryLayout) :
    FieldDescriptor<T>(companion, memoryLayout)

class NestedStructField<T : Struct>(companion: StructCompanion<*>, internal val nestedCompanion: StructCompanion<T>) :
    FieldDescriptor<T>(companion, nestedCompanion.layout)

/**
 * A base class for a struct's companion object. It serves two primary purposes:
 * 1.  **Schema Builder**: You declare the struct's layout by defining its fields in order
 *     using methods like `int("name")`, `string("name", size)`, etc.
 * 2.  **Factory**: It provides safe methods (`create(arena)`, `of(pointer)`) to create
 *     instances of the struct wrapper.
 *
 * This design ensures that the layout definition is decoupled from the instance logic,
 * providing a clean, safe, and highly readable DSL.
 *
 * @param T The concrete [Struct] type this companion builds and describes.
 */
abstract class StructCompanion<T : Struct> {
    val fields = mutableMapOf<String, FieldDescriptor<*>>()

    /** The memory layout of the struct, computed automatically based on declared fields. */
    val layout: GroupLayout by lazy {
        val layouts = fields.values.map { it.memoryLayout.withName(it.name) }
        MemoryLayout.paddedStructLayout(*layouts.toTypedArray())
    }

    /** The total size of the struct in bytes, including any padding for alignment. */
    val size: Long by lazy { layout.byteSize() }

    private val offsets: Map<String, Long> by lazy {
        val structLayout = this.layout // ensure layout is computed first
        fields.values.associate {
            it.name to structLayout.byteOffset(MemoryLayout.PathElement.groupElement(it.name))
        }
    }

    /**
     * **INTERNAL USE ONLY**. The raw factory method for creating a wrapper.
     * This method assumes the pointer is already correctly sized. It is made `internal abstract`
     * to prevent accidental unsafe use from outside the framework.
     * @param pointer A pointer that is guaranteed to have a size of at least `this.size`.
     */
    protected abstract fun create(pointer: Pointer): T

    /**
     * Allocates new, zero-initialized memory for the struct in the given arena and returns a wrapper.
     * This is the standard way to create a new struct instance from scratch.
     *
     * @param arena The arena for memory allocation.
     * @return A new instance of [T] backed by newly allocated memory.
     */
    fun create(arena: Arena): T = create(arena.allocate(layout))

    /**
     * **The safe factory for wrapping an existing native pointer.**
     * This method correctly handles potentially zero-length pointers (which are common
     * when receiving pointers from native callbacks) by reinterpreting them to the
     * full size of the struct. This is the **required** method for handling pointers
     * whose size is not guaranteed.
     *
     * @param pointer The native pointer to wrap (can be a zero-length segment).
     * @return A new, safe instance of [T] pointing to the given address.
     * @throws NullPointerException if the provided pointer is NULL.
     */
    fun of(pointer: Pointer): T {
        pointer.checkNull("Cannot create struct from NULL pointer")
        val correctlySizedPointer = pointer.reinterpret(this.size)
        return create(correctlySizedPointer)
    }

    internal fun getField(name: String) = fields[name] ?: throw NoSuchElementException("Field '$name' not declared in companion.")
    internal fun getOffset(name: String) = offsets[name] ?: throw NoSuchElementException("Offset for '$name' not calculated.")

    private fun <F : FieldDescriptor<*>> register(field: F, name: String): F {
        field.name = name
        fields[name] = field
        return field
    }

    protected fun int(name: String) = register(PrimitiveField<Int>(this, ValueLayout.JAVA_INT), name)
    protected fun long(name: String) = register(PrimitiveField<Long>(this, ValueLayout.JAVA_LONG), name)
    protected fun float(name: String) = register(PrimitiveField<Float>(this, ValueLayout.JAVA_FLOAT), name)
    protected fun double(name: String) = register(PrimitiveField<Double>(this, ValueLayout.JAVA_DOUBLE), name)
    protected fun pointer(name: String) = register(PrimitiveField<Pointer>(this, ValueLayout.ADDRESS), name)
    protected fun string(name: String, size: Long) = register(PrimitiveField<String>(this, MemoryLayout.sequenceLayout(size, ValueLayout.JAVA_BYTE)), name)
    protected fun <N : Struct> nested(name: String, nestedCompanion: StructCompanion<N>) = register(NestedStructField(this, nestedCompanion), name)
}

/**
 * An abstract base class for a type-safe Kotlin wrapper around a native struct.
 *
 * This framework provides a declarative DSL to define native struct layouts safely
 * and efficiently, abstracting away manual offset calculations and memory management details.
 *
 * ---
 *
 * ### Example 1: Defining and Creating a New Struct
 * This is the primary use case for creating and manipulating structs from scratch.
 *
 * ```kotlin
 * /*
 *  * C/C++ equivalent:
 *  * struct Point {
 *  *     int32_t x;
 *  *     int32_t y;
 *  * };
 *  */
 * class Point(pointer: Pointer) : Struct(pointer) {
 *
 *     // 1. The companion object defines the memory layout and factory methods.
 *     companion object : StructCompanion<Point>() {
 *         // 2. Declare fields in order, providing a unique name for each.
 *         val x = int("x")
 *         val y = int("y")
 *
 *         // 3. Implement the internal factory.
 *         override fun create(pointer: Pointer) = Point(pointer)
 *     }
 *
 *     // 4. Create properties that delegate to the Mapped provider.
 *     var x: Int by Mapped()
 *     var y: Int by Mapped()
 * }
 *
 * // Usage for creating a new struct:
 * Arena.ofConfined().use { arena ->
 *     val point = Point.create(arena) // Use create() to allocate new memory
 *     point.x = 10
 *     point.y = 20
 *     println("x=${point.x}, y=${point.y}") // Output: x=10, y=20
 * }
 * ```
 *
 * ---
 *
 * ### Example 2: Wrapping an Existing Pointer (e.g., from a hook)
 * This is the crucial use case when you receive a raw pointer from native code
 * (e.g., as a function argument in a hook callback). Such pointers are often
 * "unsized" (have a `byteSize` of 0) and must be safely wrapped.
 *
 * ```kotlin
 * // Assuming the Point class is defined as in Example 1.
 *
 * // Inside a hook callback (e.g., onCalled { ... }):
 * val rawPointer = arg<Pointer>(0) // Get a raw pointer from function arguments
 *
 * // Correct way to wrap it: Use the .of() factory method.
 * // .of() safely reinterprets the pointer to the correct size of the struct.
 * val point = Point.of(rawPointer)
 *
 * // Now you can safely access its fields.
 * println("Intercepted point with x = ${point.x}")
 *
 * // Modifying it will modify the memory of the original pointer.
 * point.x += 100
 * ```
 *
 * @param pointer The native pointer to the memory region of the struct.
 *                This pointer is expected to be correctly sized by a factory method in [StructCompanion].
 */
abstract class Struct(val pointer: Pointer) {
    init {
        pointer.checkNull(this::class.simpleName ?: "Struct")
    }

    /**
     * A universal property delegate that connects an instance property (e.g., `player.id`)
     * to the struct's schema defined in its companion object. It uses reflection to find the
     * companion and then retrieves the field's metadata by matching the property name.
     */
    protected class Mapped<T> : ReadWriteProperty<Struct, T> {
        override fun getValue(thisRef: Struct, property: KProperty<*>): T {
            val companion = thisRef::class.java.getField("Companion").get(null) as StructCompanion<*>
            val field = companion.getField(property.name)
            val offset = companion.getOffset(property.name)
            val ptr = thisRef.pointer

            @Suppress("UNCHECKED_CAST")
            return when (field) {
                is NestedStructField<*> -> field.nestedCompanion.of(ptr.asSlice(offset, field.nestedCompanion.size))
                is PrimitiveField<*> -> when (field.memoryLayout) {
                    is ValueLayout.OfInt -> ptr.readInt(offset)
                    is ValueLayout.OfLong -> ptr.readLong(offset)
                    is ValueLayout.OfFloat -> ptr.readFloat(offset)
                    is ValueLayout.OfDouble -> ptr.readDouble(offset)
                    is AddressLayout -> ptr.readPointer(offset)
                    is SequenceLayout -> ptr.asSlice(offset, field.memoryLayout.byteSize()).readCString()
                    else -> throw IllegalStateException("Unsupported primitive layout: ${field.memoryLayout}")
                }
            } as T
        }

        override fun setValue(thisRef: Struct, property: KProperty<*>, value: T) {
            val companion = thisRef::class.java.getField("Companion").get(null) as StructCompanion<*>
            val field = companion.getField(property.name)
            val offset = companion.getOffset(property.name)
            val ptr = thisRef.pointer

            when (value) {
                is Int -> ptr.writeInt(offset, value)
                is Long -> ptr.writeLong(offset, value)
                is Float -> ptr.writeFloat(offset, value)
                is Double -> ptr.writeDouble(offset, value)
                is Pointer -> ptr.writePointer(offset, value)
                is Struct -> MemorySegment.copy(value.pointer, 0, ptr, offset, field.memoryLayout.byteSize())
                is String -> {
                    val stringBytes = value.toByteArray(StandardCharsets.UTF_8)
                    val stringPointer = ptr.asSlice(offset, field.memoryLayout.byteSize())
                    val bytesToWrite = minOf(stringBytes.size.toLong(), field.memoryLayout.byteSize() - 1).toInt()
                    MemorySegment.copy(stringBytes, 0, stringPointer, ValueLayout.JAVA_BYTE, 0, bytesToWrite)
                    for (i in bytesToWrite until field.memoryLayout.byteSize().toInt()) {
                        stringPointer.writeByte(i.toLong(), 0)
                    }
                }
                else -> throw IllegalArgumentException("Unsupported value type for write: ${value!!::class.simpleName}")
            }
        }
    }
}