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

/**
 * An interface for Kotlin enums that map to native integer constants.
 *
 * Implementing this interface, along with [NativeEnum.Companion], provides full, automatic
 * conversion support within the TerminatorKT framework, including for [Struct] fields
 * and [CallablePointer] arguments/return values.
 */
interface NativeEnum {
    /** The underlying integer value that represents this enum constant in native memory. */
    val value: Int

    /**
     * An interface that must be implemented by the companion object of any [NativeEnum].
     * It provides a factory method to convert an integer back into an enum constant.
     *
     * ### Example Implementation:
     * ```
     * enum class Status(override val value: Int) : NativeEnum {
     *     ACTIVE(0), INACTIVE(1);
     *
     *     companion object : NativeEnum.Companion<Status> {
     *         // This find-based implementation works for most cases.
     *         override fun fromValue(value: Int) = entries.find { it.value == value }
     *     }
     * }
     * ```
     */
    interface Companion<T : NativeEnum> {
        /**
         * Creates an enum instance from its integer representation.
         * @param value The integer value read from native memory.
         * @return The corresponding enum constant, or `null` if the value is not a valid member of the enum.
         */
        fun fromValue(value: Int): T?
    }
}