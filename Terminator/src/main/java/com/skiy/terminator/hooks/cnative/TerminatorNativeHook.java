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

import static com.v7878.unsafe.AndroidUnsafe.PAGE_SIZE;

import android.system.OsConstants;
import android.util.Log;

import com.v7878.foreign.Arena;
import com.v7878.foreign.FunctionDescriptor;
import com.v7878.foreign.Linker;
import com.v7878.foreign.MemorySegment;
import com.v7878.foreign.SymbolLookup;
import com.v7878.unsafe.InstructionSet;
import com.v7878.unsafe.NativeCodeBlob;
import com.v7878.unsafe.io.IOUtils;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;

/**
 * A universal class for creating trampoline hooks for native functions on Android
 * using the PanamaPort library.
 * <p>
 * This class overwrites the prologue of a target native function with a jump to a
 * dynamically generated Java method handle (an "upcall stub"). To allow calling the
 * original function, the overwritten bytes are saved and combined with a jump back
 * to the original function's body, creating a "trampoline".
 * <p>
 * Supports ARM64, ARM32, X86_64, and X86 architectures.
 * <p>
 * It allows intercepting calls to any native function, providing a convenient
 * callback-based API for handling the intercepted calls.
 */
public final class TerminatorNativeHook implements AutoCloseable {

    private static final String TAG = "TerminatorNativeHook";
    private static final Linker LINKER = Linker.nativeLinker();

    private final MemorySegment targetFunctionAddress;
    private final Arena hookArena;
    private final byte[] originalBytes;
    private boolean isHooked;

    private final MethodHandle originalInvoker;
    private final HookCallback userCallback;

    private static final int JUMP_CODE_SIZE;

    static {
        // Determine the size of the absolute jump instruction based on the current architecture.
        switch (InstructionSet.CURRENT_INSTRUCTION_SET) {
            case ARM64:
                JUMP_CODE_SIZE = 16; // 64-bit architectures require longer instructions for absolute jumps.
                break;
            case X86_64:
                JUMP_CODE_SIZE = 13;
                break;
            case ARM:
            case X86:
                JUMP_CODE_SIZE = 12; // 32-bit jump instructions are more compact.
                break;
            default:
                throw new UnsupportedOperationException(
                        "Unsupported instruction set: " + InstructionSet.CURRENT_INSTRUCTION_SET);
        }
    }

    /**
     * A functional interface for the hook callback logic.
     * This is executed when the hooked native function is called.
     */
    @FunctionalInterface
    public interface HookCallback {
        /**
         * The method to be implemented by the user to define the hook's behavior.
         *
         * @param original A [MethodHandle] that can be used to invoke the original, unhooked function (the "trampoline").
         * @param args The arguments passed to the original function call.
         * @return The value that should be returned by the hooked function. The type must match the function's original return type.
         * @throws Throwable Allows the callback to throw any exception.
         */
        Object onCall(MethodHandle original, Object... args) throws Throwable;
    }

    /**
     * Private constructor that sets up the entire hook mechanism.
     *
     * @param targetFunctionAddress The memory segment of the target function.
     * @param callback The user-defined callback.
     * @param descriptor The function signature.
     * @throws Throwable if any part of the setup fails.
     */
    private TerminatorNativeHook(MemorySegment targetFunctionAddress, HookCallback callback, FunctionDescriptor descriptor) throws Throwable {
        this.targetFunctionAddress = Objects.requireNonNull(targetFunctionAddress);
        this.userCallback = Objects.requireNonNull(callback);
        this.hookArena = Arena.ofShared(); // Use a shared arena for long-lived hook resources.
        this.isHooked = false;

        // 1. Save the original bytes from the function's prologue.
        this.originalBytes = this.targetFunctionAddress.asSlice(0, JUMP_CODE_SIZE).toArray(com.v7878.foreign.ValueLayout.JAVA_BYTE);

        // 2. Create the trampoline, which will execute the original bytes and jump back.
        MemorySegment trampoline = createTrampoline(this.targetFunctionAddress.nativeAddress() + JUMP_CODE_SIZE);
        this.originalInvoker = LINKER.downcallHandle(trampoline, descriptor);

        // 3. Create a generic handler that adapts the user callback to a standard signature.
        MethodHandle genericHandler = MethodHandles.lookup().findVirtual(
                TerminatorNativeHook.class, "genericHookHandler",
                MethodType.methodType(Object.class, Object[].class)
        ).bindTo(this);

        // 4. Adapt the generic handler to the specific types of the hooked function.
        MethodType targetType = descriptor.toMethodType();
        genericHandler = genericHandler.asCollector(Object[].class, targetType.parameterCount());
        genericHandler = genericHandler.asType(targetType);

        // 5. Create a native stub (upcall) that can be called from C/C++.
        MemorySegment hookStub = LINKER.upcallStub(genericHandler, descriptor, this.hookArena);

        // 6. Generate the machine code for jumping to our hook stub.
        byte[] jumpToHookCode = generateJumpCode(hookStub.nativeAddress());

        // 7. Overwrite the function prologue with our jump.
        installHookInternal(jumpToHookCode);
    }

    /**
     * Installs a hook on a native function identified by its library and symbol name.
     *
     * @param libraryName The name of the library file (e.g., "libc.so").
     * @param symbolName The name of the symbol (function) to hook.
     * @param callback The callback to execute when the function is called.
     * @param descriptor The [FunctionDescriptor] describing the native function's signature.
     * @return An instance of [TerminatorNativeHook] which can be used to uninstall the hook.
     * @throws Throwable if the symbol is not found or if the hook installation fails.
     */
    public static TerminatorNativeHook install(String libraryName, String symbolName, HookCallback callback, FunctionDescriptor descriptor) throws Throwable {
        SymbolLookup lookup = SymbolLookup.libraryLookup(libraryName, Arena.ofAuto());

        MemorySegment foundSymbol = lookup.find(symbolName)
                .orElseThrow(() -> new NoSuchMethodException("Symbol not found: " + symbolName + " in " + libraryName));

        // Reinterpret the found symbol with a page size to ensure it's usable for memory operations.
        MemorySegment usableSegment = foundSymbol.reinterpret(PAGE_SIZE);
        Log.d(TAG, "Installing hook for " + symbolName + " at 0x" + Long.toHexString(usableSegment.nativeAddress()));

        return new TerminatorNativeHook(usableSegment, callback, descriptor);
    }

    /**
     * Installs a hook on a native function at a specific absolute memory address.
     *
     * @param address The absolute memory address of the function to hook.
     * @param callback The callback to execute when the function is called.
     * @param descriptor The [FunctionDescriptor] describing the native function's signature.
     * @return An instance of [TerminatorNativeHook] which can be used to uninstall the hook.
     * @throws Throwable if the address is invalid or if the hook installation fails.
     */
    public static TerminatorNativeHook install(long address, HookCallback callback, FunctionDescriptor descriptor) throws Throwable {
        if (address == 0) {
            throw new IllegalArgumentException("Cannot hook address 0x0");
        }

        // Create a zero-length segment from the raw address.
        MemorySegment foundSymbol = MemorySegment.ofAddress(address);

        // "Expand" it to a workable size to allow memory operations.
        MemorySegment usableSegment = foundSymbol.reinterpret(PAGE_SIZE);
        Log.d(TAG, "Installing hook directly at address 0x" + Long.toHexString(usableSegment.nativeAddress()));

        return new TerminatorNativeHook(usableSegment, callback, descriptor);
    }

    /**
     * A generic handler that bridges the native call to the user's Java callback.
     */
    private Object genericHookHandler(Object... args) throws Throwable {
        return userCallback.onCall(this.originalInvoker, args);
    }

    /**
     * Implements [AutoCloseable] to allow for try-with-resources usage.
     * Automatically calls {@link #uninstall()}.
     */
    @Override
    public void close() {
        uninstall();
    }

    /**
     * Removes the hook and restores the original bytes of the function prologue.
     * This also closes the memory arena used by the hook, freeing associated resources.
     * The hook becomes inactive after this call.
     */
    public void uninstall() {
        if (!isHooked) {
            return;
        }
        // Calculate the start of the memory page containing the target function.
        long pageStart = targetFunctionAddress.nativeAddress() & ~(PAGE_SIZE - 1);

        try {
            // Change memory protection to allow writing.
            IOUtils.mprotect(pageStart, PAGE_SIZE, OsConstants.PROT_READ | OsConstants.PROT_WRITE);
            // Copy the original bytes back to the function prologue.
            MemorySegment.copy(MemorySegment.ofArray(originalBytes), 0, targetFunctionAddress, 0, originalBytes.length);
            // Restore original memory protection (Read & Execute).
            IOUtils.mprotect(pageStart, PAGE_SIZE, OsConstants.PROT_READ | OsConstants.PROT_EXEC);

            hookArena.close(); // Free resources associated with the hook (trampoline, stubs).
            isHooked = false;
            Log.i(TAG, "Hook for address 0x" + Long.toHexString(targetFunctionAddress.nativeAddress()) + " uninstalled.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to uninstall hook", e);
        }
    }

    /**
     * Internally handles the memory protection changes and writes the jump code to the target function.
     * @param jumpCode The machine code for the jump instruction.
     * @throws Exception if mprotect or memory copy fails.
     */
    private void installHookInternal(byte[] jumpCode) throws Exception {
        long pageStart = targetFunctionAddress.nativeAddress() & ~(PAGE_SIZE - 1);
        // Temporarily make the page writable and executable.
        IOUtils.mprotect(pageStart, PAGE_SIZE, OsConstants.PROT_READ | OsConstants.PROT_WRITE | OsConstants.PROT_EXEC);
        // Overwrite the function prologue.
        MemorySegment.copy(MemorySegment.ofArray(jumpCode), 0, targetFunctionAddress, 0, jumpCode.length);
        // Restore to Read & Execute.
        IOUtils.mprotect(pageStart, PAGE_SIZE, OsConstants.PROT_READ | OsConstants.PROT_EXEC);
        this.isHooked = true;
        Log.i(TAG, "Hook installed successfully at address: 0x" + Long.toHexString(targetFunctionAddress.nativeAddress()));
    }

    /**
     * Creates a trampoline function in native memory.
     * The trampoline consists of the original, overwritten bytes followed by a jump back to the
     * rest of the original function.
     *
     * @param jumpBackAddress The address to jump to after executing the saved bytes.
     * @return A [MemorySegment] representing the executable trampoline.
     */
    private MemorySegment createTrampoline(long jumpBackAddress) {
        byte[] jumpBackCode = generateJumpCode(jumpBackAddress);
        byte[] trampolineCode = new byte[originalBytes.length + jumpBackCode.length];
        // 1. Copy the saved original bytes.
        System.arraycopy(originalBytes, 0, trampolineCode, 0, originalBytes.length);
        // 2. Append the jump-back instruction.
        System.arraycopy(jumpBackCode, 0, trampolineCode, originalBytes.length, jumpBackCode.length);
        // Create an executable memory blob from the combined code.
        return NativeCodeBlob.makeCodeBlob(hookArena, trampolineCode)[0];
    }

    /**
     * Converts a long value to a little-endian byte array.
     */
    private static byte[] toArray(long value) {
        // Little-endian byte order
        return new byte[]{(byte)(value),(byte)(value>>8),(byte)(value>>16),(byte)(value>>24),(byte)(value>>32),(byte)(value>>40),(byte)(value>>48),(byte)(value>>56)};
    }

    /**
     * Generates architecture-specific machine code for an absolute jump to a target address.
     *
     * @param targetAddress The destination address for the jump.
     * @return A byte array containing the machine code for the jump instruction.
     */
    private static byte[] generateJumpCode(long targetAddress) {
        byte[] addr = toArray(targetAddress);
        switch (InstructionSet.CURRENT_INSTRUCTION_SET) {
            case ARM64:
                // LDR X16, #8  ; load 64-bit address from the literal pool below
                // BR X16       ; branch to the address in X16
                // .quad targetAddress
                return new byte[]{
                        0x50, 0x00, 0x00, 0x58,       // LDR X16, #8
                        0x00, 0x02, 0x1f, (byte) 0xd6, // BR X16
                        // 8-byte address literal
                        addr[0], addr[1], addr[2], addr[3], addr[4], addr[5], addr[6], addr[7]
                };
            case X86_64:
                // MOVABS RAX, targetAddress ; move 64-bit immediate to RAX
                // JMP RAX                   ; jump to the address in RAX
                return new byte[]{
                        0x48, (byte) 0xb8, addr[0], addr[1], addr[2], addr[3], addr[4], addr[5], addr[6], addr[7],
                        (byte) 0xff, (byte) 0xe0
                };

            case ARM:
                // LDR PC, [PC, #-4] ; load 32-bit address from the literal pool immediately following this instruction
                // .word targetAddress
                return new byte[]{
                        (byte) 0x04, (byte) 0xF0, (byte) 0x1F, (byte) 0xE5, // LDR PC, [PC, #-4] (Corrected to Little Endian)
                        // 4-byte address literal
                        addr[0], addr[1], addr[2], addr[3]
                };

            case X86:
                // MOV EAX, targetAddress ; move 32-bit immediate to EAX
                // JMP EAX                ; jump to the address in EAX
                return new byte[]{
                        (byte) 0xB8, addr[0], addr[1], addr[2], addr[3],
                        (byte) 0xFF, (byte) 0xE0
                };

            default:
                throw new UnsupportedOperationException("Jump code generation not implemented for " + InstructionSet.CURRENT_INSTRUCTION_SET);
        }
    }
}