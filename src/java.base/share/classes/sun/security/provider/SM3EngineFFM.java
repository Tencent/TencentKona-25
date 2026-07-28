/*
 * Copyright (C) 2022, 2024, 2026, Tencent. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES AND THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package sun.security.provider;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

/**
 * SM3 engine implementation using FFM API (Panama / JDK 22+ Native Interface).
 * This is a replacement for SM3EngineNative (JNI-based implementation).
 * 
 * Key optimizations:
 * - Uses confined arena for efficient memory management
 * - Direct MemorySegment operations for data transfer
 * - No JNI overhead
 * - OpenSSL 3.x SIMD-optimized SM3
 */
public final class SM3EngineFFM implements SM3Engine {

    private static final int SM3_DIGEST_LENGTH = 32;
    private static final int DEFAULT_BUFFER_SIZE = 4096;

    // OpenSSL function handles (static, shared)
    private static final MethodHandle EVP_MD_CTX_NEW;
    private static final MethodHandle EVP_MD_CTX_FREE;
    private static final MethodHandle EVP_SM3;
    private static final MethodHandle EVP_DIGESTINIT_EX;
    private static final MethodHandle EVP_DIGESTUPDATE;
    private static final MethodHandle EVP_DIGESTFINAL_EX;

    // Layouts for native calls
    private static final AddressLayout PTR = ValueLayout.ADDRESS;
    private static final ValueLayout INT = ValueLayout.JAVA_INT;
    private static final ValueLayout LONG = ValueLayout.JAVA_LONG;

    // Check if FFM is available
    private static final boolean FFM_AVAILABLE;
    
    // Cached EVP_MD handle
    private static final MemorySegment EVP_MD_HANDLE;

    static {
        boolean available = false;
        MethodHandle ctxNew = null;
        MethodHandle ctxFree = null;
        MethodHandle sm3 = null;
        MethodHandle initEx = null;
        MethodHandle update = null;
        MethodHandle finalEx = null;
        MemorySegment mdHandle = MemorySegment.NULL;

        try {
            SymbolLookup libcrypto = SymbolLookup.libraryLookup("libcrypto.so.3", Arena.ofAuto());
            Linker linker = Linker.nativeLinker();

            // EVP_MD_CTX* EVP_MD_CTX_new(void);
            ctxNew = linker.downcallHandle(
                libcrypto.find("EVP_MD_CTX_new").orElseThrow(),
                FunctionDescriptor.of(PTR)
            );

            // void EVP_MD_CTX_free(EVP_MD_CTX* ctx);
            ctxFree = linker.downcallHandle(
                libcrypto.find("EVP_MD_CTX_free").orElseThrow(),
                FunctionDescriptor.ofVoid(PTR)
            );

            // const EVP_MD* EVP_sm3(void);
            sm3 = linker.downcallHandle(
                libcrypto.find("EVP_sm3").orElseThrow(),
                FunctionDescriptor.of(PTR)
            );

            // int EVP_DigestInit_ex(EVP_MD_CTX* ctx, const EVP_MD* type, ENGINE* impl);
            initEx = linker.downcallHandle(
                libcrypto.find("EVP_DigestInit_ex").orElseThrow(),
                FunctionDescriptor.of(INT, PTR, PTR, PTR)
            );

            // int EVP_DigestUpdate(EVP_MD_CTX* ctx, const void* data, size_t count);
            update = linker.downcallHandle(
                libcrypto.find("EVP_DigestUpdate").orElseThrow(),
                FunctionDescriptor.of(INT, PTR, PTR, LONG)
            );

            // int EVP_DigestFinal_ex(EVP_MD_CTX* ctx, unsigned char* md, unsigned int* s);
            finalEx = linker.downcallHandle(
                libcrypto.find("EVP_DigestFinal_ex").orElseThrow(),
                FunctionDescriptor.of(INT, PTR, PTR, PTR)
            );

            // Verify EVP_sm3 returns non-null
            mdHandle = (MemorySegment) sm3.invoke();
            if (!mdHandle.equals(MemorySegment.NULL)) {
                available = true;
            }
        } catch (Throwable t) {
            // FFM not available, will use fallback
        }

        FFM_AVAILABLE = available;
        EVP_MD_CTX_NEW = ctxNew;
        EVP_MD_CTX_FREE = ctxFree;
        EVP_SM3 = sm3;
        EVP_DIGESTINIT_EX = initEx;
        EVP_DIGESTUPDATE = update;
        EVP_DIGESTFINAL_EX = finalEx;
        EVP_MD_HANDLE = mdHandle;
    }

    // Instance state
    private byte[] buffer;
    private int bufferLen;

    public SM3EngineFFM() {
        this.buffer = new byte[DEFAULT_BUFFER_SIZE];
        this.bufferLen = 0;
    }

    /**
     * Checks if FFM-based SM3 is available (OpenSSL 3.x with SM3 support).
     */
    public static boolean isAvailable() {
        return FFM_AVAILABLE;
    }

    @Override
    public void reset() {
        bufferLen = 0;
    }

    @Override
    public void update(byte message) {
        ensureCapacity(bufferLen + 1);
        buffer[bufferLen++] = message;
    }

    @Override
    public void update(byte[] message) {
        update(message, 0, message.length);
    }

    @Override
    public void update(byte[] message, int offset, int length) {
        ensureCapacity(bufferLen + length);
        System.arraycopy(message, offset, buffer, bufferLen, length);
        bufferLen += length;
    }

    private void ensureCapacity(int minCapacity) {
        if (minCapacity > buffer.length) {
            int newCapacity = Math.max(minCapacity, buffer.length * 2);
            byte[] newBuffer = new byte[newCapacity];
            System.arraycopy(buffer, 0, newBuffer, 0, bufferLen);
            buffer = newBuffer;
        }
    }

    @Override
    public void doFinal(byte[] out, int offset) {
        try {
            // Use try-with-resources for arena
            try (Arena arena = Arena.ofConfined()) {
                // Allocate native context
                MemorySegment ctx = (MemorySegment) EVP_MD_CTX_NEW.invoke();
                try {
                    // Initialize digest
                    int result = (int) EVP_DIGESTINIT_EX.invoke(ctx, EVP_MD_HANDLE, MemorySegment.NULL);
                    if (result != 1) {
                        throw new IllegalStateException("SM3 initialization failed");
                    }

                    // Copy message to native memory
                    MemorySegment msgSegment = arena.allocate(bufferLen, 1);
                    java.nio.ByteBuffer buf = msgSegment.asByteBuffer();
                    buf.put(buffer, 0, bufferLen);

                    // Update digest
                    result = (int) EVP_DIGESTUPDATE.invoke(ctx, msgSegment, (long) bufferLen);
                    if (result != 1) {
                        throw new IllegalStateException("SM3 update failed");
                    }

                    // Allocate digest output buffer
                    MemorySegment digestSegment = arena.allocate(SM3_DIGEST_LENGTH, 1);

                    // Finalize
                    result = (int) EVP_DIGESTFINAL_EX.invoke(ctx, digestSegment, MemorySegment.NULL);
                    if (result != 1) {
                        throw new IllegalStateException("SM3 finalization failed");
                    }

                    // Copy result to Java array
                    java.nio.ByteBuffer outBuf = digestSegment.asByteBuffer();
                    outBuf.get(out, offset, SM3_DIGEST_LENGTH);

                } finally {
                    // Free context
                    EVP_MD_CTX_FREE.invoke(ctx);
                }
            }
        } catch (Throwable t) {
            throw new RuntimeException("SM3 FFM digest failed", t);
        }
        
        // Reset state
        reset();
        buffer = new byte[DEFAULT_BUFFER_SIZE];
    }

    @Override
    public void doFinal(byte[] out) {
        doFinal(out, 0);
    }

    @Override
    public byte[] doFinal() {
        byte[] digest = new byte[SM3_DIGEST_LENGTH];
        doFinal(digest);
        return digest;
    }

    @Override
    public SM3EngineFFM clone() throws CloneNotSupportedException {
        SM3EngineFFM cloned = (SM3EngineFFM) super.clone();
        cloned.buffer = new byte[this.bufferLen];
        System.arraycopy(this.buffer, 0, cloned.buffer, 0, this.bufferLen);
        cloned.bufferLen = this.bufferLen;
        return cloned;
    }
}
