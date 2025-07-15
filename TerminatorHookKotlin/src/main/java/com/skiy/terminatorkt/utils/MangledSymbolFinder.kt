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
import com.skiy.terminatorkt.Pointer
import com.skiy.terminatorkt.plus
import com.skiy.terminatorkt.readByte
import com.skiy.terminatorkt.readCString
import com.skiy.terminatorkt.readInt
import com.skiy.terminatorkt.readLong
import com.skiy.terminatorkt.readShort
import com.v7878.foreign.MemorySegment
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/**
 * A utility to parse ELF files (.so) and extract a list of all dynamic symbols,
 * including mangled C++ function names.
 */
object MangledSymbolFinder {

    private const val TAG = "MangledSymbolFinder"

    enum class SymbolType { FUNCTION, OBJECT, NOTYPE, OTHER }

    data class SymbolInfo(
        val name: String,
        val type: SymbolType,
        val value: Long, // Relative address (offset) within the library
        val size: Long
    ) {
        val isMangled: Boolean
            get() = name.startsWith("_Z")
    }

    private val cache = ConcurrentHashMap<String, List<SymbolInfo>>()

    fun getAllFunctions(libraryPath: String): List<SymbolInfo> {
        return getAllSymbols(libraryPath).filter { it.type == SymbolType.FUNCTION }
    }

    fun getAllSymbols(libraryPath: String): List<SymbolInfo> {
        return cache.getOrPut(libraryPath) {
            try {
                parseElfFile(libraryPath)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse ELF file: $libraryPath", e)
                emptyList()
            }
        }
    }


    private object Elf64 {
        const val E_SHOFF_OFFSET = 0x28L
        const val E_SHENTSIZE_OFFSET = 0x3aL
        const val E_SHNUM_OFFSET = 0x3cL
        const val E_SHSTRNDX_OFFSET = 0x3eL

        const val SH_NAME_OFFSET = 0x00L
        const val SH_TYPE_OFFSET = 0x04L
        const val SH_OFFSET_OFFSET = 0x18L
        const val SH_SIZE_OFFSET = 0x20L
        const val SHT_DYNSYM = 11

        const val ST_NAME_OFFSET = 0x00L
        const val ST_INFO_OFFSET = 0x04L
        const val ST_VALUE_OFFSET = 0x08L
        const val ST_SIZE_OFFSET = 0x10L
        const val SYMBOL_ENTRY_SIZE = 24L
        const val STT_NOTYPE = 0
        const val STT_OBJECT = 1
        const val STT_FUNC = 2
    }

    private fun parseElfFile(path: String): List<SymbolInfo> {
        val file = File(path)
        if (!file.exists()) {
            Log.e(TAG, "Library not found at path: $path")
            return emptyList()
        }

        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            val filePtr: Pointer = MemorySegment.ofBuffer(
                channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
            )

            // 1. Read ELF header
            val e_shoff = filePtr.readLong(Elf64.E_SHOFF_OFFSET)
            val e_shentsize = filePtr.readShort(Elf64.E_SHENTSIZE_OFFSET).toLong()
            val e_shnum = filePtr.readShort(Elf64.E_SHNUM_OFFSET).toInt()
            val e_shstrndx = filePtr.readShort(Elf64.E_SHSTRNDX_OFFSET).toInt()

            val sectionHeadersPtr = filePtr + e_shoff

            // 2. Find .shstrtab
            val shstrtabHeaderPtr = sectionHeadersPtr + (e_shstrndx * e_shentsize)
            val shstrtabOffset = shstrtabHeaderPtr.readLong(Elf64.SH_OFFSET_OFFSET)
            val shstrtabPtr = filePtr + shstrtabOffset

            fun getSectionName(headerPtr: Pointer): String {
                val nameOffset = headerPtr.readInt(Elf64.SH_NAME_OFFSET).toLong()
                return (shstrtabPtr + nameOffset).readCString()
            }

            // 3. Find .dynsym and .dynstr sections
            var dynsymPtr: Pointer? = null
            var dynstrPtr: Pointer? = null

            for (i in 0 until e_shnum) {
                val headerPtr = sectionHeadersPtr + (i * e_shentsize)
                when (getSectionName(headerPtr)) {
                    ".dynsym" -> {
                        val offset = headerPtr.readLong(Elf64.SH_OFFSET_OFFSET)
                        val size = headerPtr.readLong(Elf64.SH_SIZE_OFFSET)
                        dynsymPtr = filePtr.asSlice(offset, size)
                    }
                    ".dynstr" -> {
                        val offset = headerPtr.readLong(Elf64.SH_OFFSET_OFFSET)
                        val size = headerPtr.readLong(Elf64.SH_SIZE_OFFSET)
                        dynstrPtr = filePtr.asSlice(offset, size)
                    }
                }
            }

            if (dynsymPtr == null || dynstrPtr == null) {
                Log.w(TAG, "Could not find .dynsym or .dynstr sections in $path")
                return emptyList()
            }

            // 4. Iterate through the symbol table
            val symbols = mutableListOf<SymbolInfo>()
            val symbolCount = dynsymPtr.byteSize() / Elf64.SYMBOL_ENTRY_SIZE

            for (i in 0 until symbolCount) {
                val symbolEntryPtr = dynsymPtr + (i * Elf64.SYMBOL_ENTRY_SIZE)
                val st_name_offset = symbolEntryPtr.readInt(Elf64.ST_NAME_OFFSET).toLong()
                if (st_name_offset == 0L) continue

                val name = (dynstrPtr + st_name_offset).readCString()
                val st_info = symbolEntryPtr.readByte(Elf64.ST_INFO_OFFSET)
                val type = when (st_info.toInt() and 0x0F) {
                    Elf64.STT_FUNC -> SymbolType.FUNCTION
                    Elf64.STT_OBJECT -> SymbolType.OBJECT
                    Elf64.STT_NOTYPE -> SymbolType.NOTYPE
                    else -> SymbolType.OTHER
                }
                val value = symbolEntryPtr.readLong(Elf64.ST_VALUE_OFFSET)
                val size = symbolEntryPtr.readLong(Elf64.ST_SIZE_OFFSET)

                symbols.add(SymbolInfo(name, type, value, size))
            }
            return symbols
        }
    }
}