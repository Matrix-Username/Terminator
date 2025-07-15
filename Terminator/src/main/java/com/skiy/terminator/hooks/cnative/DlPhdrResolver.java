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
package com.skiy.terminator.hooks.cnative;

import android.util.Log;
import com.v7878.foreign.Arena;
import com.v7878.foreign.FunctionDescriptor;
import com.v7878.foreign.GroupLayout;
import com.v7878.foreign.Linker;
import com.v7878.foreign.MemoryLayout;
import com.v7878.foreign.MemorySegment;
import com.v7878.foreign.SymbolLookup;
import com.v7878.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A utility class to resolve information about loaded native libraries
 * by iterating through the program headers using `dl_iterate_phdr`.
 */
public final class DlPhdrResolver {

    private static final String TAG = "DlPhdrResolver";

    public record LibraryInfo(String name, long baseAddress) {}

    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle DL_ITERATE_PHDR_HANDLE;
    private static final long MAX_PATH_SIZE = 4096;

    private static final GroupLayout DL_PHDR_INFO_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("dlpi_addr"),
            ValueLayout.ADDRESS.withName("dlpi_name")
    );
    private static final long DLPI_ADDR_OFFSET = 0;
    private static final long DLPI_NAME_OFFSET = DL_PHDR_INFO_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("dlpi_name"));

    private static final GroupLayout NameContextLayout = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("target_name_ptr"),
            ValueLayout.ADDRESS.withName("result_addr")
    );
    private static final long NAME_CONTEXT_TARGET_NAME_PTR_OFFSET = 0;
    private static final long NAME_CONTEXT_RESULT_ADDR_OFFSET = NameContextLayout.byteOffset(MemoryLayout.PathElement.groupElement("result_addr"));
    private static final MemorySegment NAME_LOOKUP_CALLBACK_STUB;

    private static final GroupLayout AddrContextLayout = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("target_address"),
            ValueLayout.ADDRESS.withName("best_fit_base"),
            ValueLayout.ADDRESS.withName("result_name_ptr")
    );
    private static final long ADDR_CONTEXT_TARGET_OFFSET = 0;
    private static final long ADDR_CONTEXT_BEST_FIT_OFFSET = AddrContextLayout.byteOffset(MemoryLayout.PathElement.groupElement("best_fit_base"));
    private static final long ADDR_CONTEXT_RESULT_NAME_OFFSET = AddrContextLayout.byteOffset(MemoryLayout.PathElement.groupElement("result_name_ptr"));
    private static final MemorySegment ADDR_LOOKUP_CALLBACK_STUB;

    private static final ConcurrentHashMap<String, Long> baseAddressCache = new ConcurrentHashMap<>();

    static {
        try {
            SymbolLookup loader = SymbolLookup.loaderLookup();
            MemorySegment dlip_addr = loader.find("dl_iterate_phdr")
                    .orElseThrow(() -> new NoSuchMethodError("dl_iterate_phdr not found"));

            DL_ITERATE_PHDR_HANDLE = LINKER.downcallHandle(dlip_addr, FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS
            ));

            MethodHandle nameCallbackHandle = MethodHandles.lookup().findStatic(
                    DlPhdrResolver.class, "nameLookupCallback",
                    MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class)
            );
            NAME_LOOKUP_CALLBACK_STUB = LINKER.upcallStub(nameCallbackHandle, FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS
            ), Arena.global());

            MethodHandle addrCallbackHandle = MethodHandles.lookup().findStatic(
                    DlPhdrResolver.class, "addressLookupCallback",
                    MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class)
            );
            ADDR_LOOKUP_CALLBACK_STUB = LINKER.upcallStub(addrCallbackHandle, FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS
            ), Arena.global());

        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize DlPhdrResolver", t);
            throw new ExceptionInInitializerError(t);
        }
    }

    private DlPhdrResolver() {}

    private static int nameLookupCallback(MemorySegment infoSegment, MemorySegment sizeSegment, MemorySegment contextSegment) {
        MemorySegment info = infoSegment.reinterpret(DL_PHDR_INFO_LAYOUT.byteSize());
        MemorySegment context = contextSegment.reinterpret(NameContextLayout.byteSize());
        try {
            MemorySegment currentLibNamePtr = info.get(ValueLayout.ADDRESS, DLPI_NAME_OFFSET);
            if (currentLibNamePtr.address() == 0) return 0;
            String currentLibName = currentLibNamePtr.reinterpret(MAX_PATH_SIZE).getString(0);

            MemorySegment targetNamePtr = context.get(ValueLayout.ADDRESS, NAME_CONTEXT_TARGET_NAME_PTR_OFFSET);
            String targetLibName = targetNamePtr.reinterpret(MAX_PATH_SIZE).getString(0);

            if (currentLibName.endsWith("/" + targetLibName)) {
                long baseAddress = info.get(ValueLayout.ADDRESS, DLPI_ADDR_OFFSET).address();
                context.set(ValueLayout.ADDRESS, NAME_CONTEXT_RESULT_ADDR_OFFSET, MemorySegment.ofAddress(baseAddress));
                return 1;
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in nameLookupCallback", t);
            return 1;
        }
        return 0;
    }

    public static long getBaseAddress(String libraryName) {
        Objects.requireNonNull(libraryName, "libraryName cannot be null");
        return baseAddressCache.computeIfAbsent(libraryName, libName -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment context = arena.allocate(NameContextLayout);
                MemorySegment nativeLibName = arena.allocateFrom(libName);
                context.set(ValueLayout.ADDRESS, NAME_CONTEXT_TARGET_NAME_PTR_OFFSET, nativeLibName);
                context.set(ValueLayout.ADDRESS, NAME_CONTEXT_RESULT_ADDR_OFFSET, MemorySegment.NULL);
                DL_ITERATE_PHDR_HANDLE.invoke(NAME_LOOKUP_CALLBACK_STUB, context);
                return context.get(ValueLayout.ADDRESS, NAME_CONTEXT_RESULT_ADDR_OFFSET).address();
            } catch (Throwable t) {
                Log.e(TAG, "Failed to get base address for " + libName, t);
                return 0L;
            }
        });
    }

    private static int addressLookupCallback(MemorySegment infoSegment, MemorySegment size, MemorySegment contextSegment) {
        MemorySegment info = infoSegment.reinterpret(DL_PHDR_INFO_LAYOUT.byteSize());
        long currentBase = info.get(ValueLayout.ADDRESS, DLPI_ADDR_OFFSET).address();
        if (currentBase == 0) return 0;

        MemorySegment context = contextSegment.reinterpret(AddrContextLayout.byteSize());
        long targetAddress = context.get(ValueLayout.ADDRESS, ADDR_CONTEXT_TARGET_OFFSET).address();
        long bestFitBase = context.get(ValueLayout.ADDRESS, ADDR_CONTEXT_BEST_FIT_OFFSET).address();

        if (currentBase <= targetAddress && currentBase > bestFitBase) {
            context.set(ValueLayout.ADDRESS, ADDR_CONTEXT_BEST_FIT_OFFSET, MemorySegment.ofAddress(currentBase));

            MemorySegment currentLibNamePtr = info.get(ValueLayout.ADDRESS, DLPI_NAME_OFFSET);

            // This pointer from context is also unsized until we reinterpret it.
            MemorySegment resultNameBufferPtr = context.get(ValueLayout.ADDRESS, ADDR_CONTEXT_RESULT_NAME_OFFSET);

            if (currentLibNamePtr.address() != 0) {
                // THE ULTIMATE FIX: Both source and destination pointers obtained from memory
                // must be reinterpreted to have a size before they can be used in bulk operations.
                MemorySegment reinterpretedNameSrc = currentLibNamePtr.reinterpret(MAX_PATH_SIZE);
                MemorySegment reinterpretedNameDst = resultNameBufferPtr.reinterpret(MAX_PATH_SIZE);

                long nameLen = 0;
                while (nameLen < MAX_PATH_SIZE && reinterpretedNameSrc.get(ValueLayout.JAVA_BYTE, nameLen) != 0) {
                    nameLen++;
                }
                MemorySegment.copy(reinterpretedNameSrc, 0, reinterpretedNameDst, 0, nameLen + 1);
            } else {
                // We can set a single byte on the destination buffer pointer directly if it's all we need.
                resultNameBufferPtr.reinterpret(1).set(ValueLayout.JAVA_BYTE, 0, (byte)0);
            }
        }
        return 0;
    }

    public static LibraryInfo findLibraryForAddress(long address) {
        if (address == 0) return null;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment context = arena.allocate(AddrContextLayout);
            MemorySegment resultNameBuffer = arena.allocate(MAX_PATH_SIZE);

            context.set(ValueLayout.ADDRESS, ADDR_CONTEXT_TARGET_OFFSET, MemorySegment.ofAddress(address));
            context.set(ValueLayout.ADDRESS, ADDR_CONTEXT_BEST_FIT_OFFSET, MemorySegment.NULL);
            context.set(ValueLayout.ADDRESS, ADDR_CONTEXT_RESULT_NAME_OFFSET, resultNameBuffer);

            DL_ITERATE_PHDR_HANDLE.invoke(ADDR_LOOKUP_CALLBACK_STUB, context);

            long foundBase = context.get(ValueLayout.ADDRESS, ADDR_CONTEXT_BEST_FIT_OFFSET).address();
            if (foundBase != 0) {
                String foundName = resultNameBuffer.getString(0);
                int lastSlash = foundName.lastIndexOf('/');
                if (lastSlash != -1) {
                    foundName = foundName.substring(lastSlash + 1);
                }
                return new LibraryInfo(foundName, foundBase);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to find library for address 0x" + Long.toHexString(address), t);
        }
        return null;
    }
}