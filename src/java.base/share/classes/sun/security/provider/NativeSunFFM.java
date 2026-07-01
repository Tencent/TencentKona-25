/*
 * Copyright (C) 2026, Tencent. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2 as
 * published by the Free Software Foundation. Tencent designates
 * this particular file as subject to the "Classpath" exception as provided
 * in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License version 2 for more details.
 */

package sun.security.provider;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.ref.Cleaner;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The native SM3 implementation based on OpenSSL, using the FFM API
 * instead of JNI.
 */
final class NativeSunFFM {

    private static final int SM3_DIGEST_LENGTH = 32;
    private static final long INPUT_BUFFER_SIZE = 16 * 1024;

    private static final Arena LIBRARY_ARENA = Arena.ofShared();
    private static final Linker LINKER = Linker.nativeLinker();
    private static final Cleaner CLEANER = Cleaner.create();

    @SuppressWarnings("restricted")
    private static final SymbolLookup CRYPTO =
            SymbolLookup.libraryLookup("libcrypto.so", LIBRARY_ARENA);

    private static final MethodHandle EVP_SM3;
    private static final MethodHandle EVP_MD_CTX_NEW;
    private static final MethodHandle EVP_MD_CTX_FREE;
    private static final MethodHandle EVP_DIGEST_INIT_EX;
    private static final MethodHandle EVP_DIGEST_UPDATE;
    private static final MethodHandle EVP_DIGEST_FINAL_EX;

    // EVP_sm3() returns a pointer to a static, immutable object, so it is
    // fetched once and reused for every digest.
    private static final MemorySegment SM3_MD;

    static {
        EVP_SM3 = bind("EVP_sm3", FunctionDescriptor.of(ADDRESS));
        EVP_MD_CTX_NEW = bind("EVP_MD_CTX_new", FunctionDescriptor.of(ADDRESS));
        EVP_MD_CTX_FREE = bind("EVP_MD_CTX_free", FunctionDescriptor.ofVoid(ADDRESS));
        EVP_DIGEST_INIT_EX = bind("EVP_DigestInit_ex",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        EVP_DIGEST_UPDATE = bind("EVP_DigestUpdate",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG));
        EVP_DIGEST_FINAL_EX = bind("EVP_DigestFinal_ex",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        try {
            MemorySegment md = (MemorySegment) EVP_SM3.invokeExact();
            if (md.address() == 0) {
                throw new IllegalStateException("SM3 algorithm not available");
            }
            SM3_MD = md;
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    @SuppressWarnings("restricted")
    private static MethodHandle bind(String name, FunctionDescriptor desc) {
        MemorySegment addr = CRYPTO.find(name).orElseThrow(
                () -> new IllegalStateException("Cannot find OpenSSL symbol: " + name));
        return LINKER.downcallHandle(addr, desc);
    }

    /**
     * Per-thread reusable native resources. Creating an EVP_MD_CTX and native
     * scratch buffers on every call is relatively expensive, so each thread
     * keeps its own set and reuses them. The native context is released when
     * the owning thread (and thus this object) is garbage collected.
     */
    private static final class State {
        final Arena arena;
        final MemorySegment ctx;
        final MemorySegment digest;
        final MemorySegment lenSeg;
        final MemorySegment inBuf;

        State() {
            Arena a = Arena.ofShared();
            MemorySegment c;
            try {
                c = (MemorySegment) EVP_MD_CTX_NEW.invokeExact();
            } catch (Throwable t) {
                a.close();
                throw new IllegalStateException("Failed to create SM3 context", t);
            }
            if (c.address() == 0) {
                a.close();
                throw new IllegalStateException("Failed to create SM3 context");
            }
            this.arena = a;
            this.ctx = c;
            this.digest = a.allocate(SM3_DIGEST_LENGTH);
            this.lenSeg = a.allocate(JAVA_INT);
            this.inBuf = a.allocate(INPUT_BUFFER_SIZE);

            // Must not capture 'this' here, otherwise the object could never
            // become unreachable and the cleanup would never run.
            final MemorySegment ctxToFree = c;
            final Arena arenaToClose = a;
            CLEANER.register(this, () -> {
                try {
                    EVP_MD_CTX_FREE.invokeExact(ctxToFree);
                } catch (Throwable ignore) {
                    // best effort
                }
                arenaToClose.close();
            });
        }
    }

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    static void sm3Digest(byte[] message, byte[] out, int outOffset) {
        if (message == null || out == null) {
            throw new NullPointerException("Input/output arrays must not be null");
        }
        if (outOffset < 0 || outOffset + SM3_DIGEST_LENGTH > out.length) {
            throw new IndexOutOfBoundsException("Output buffer too small");
        }

        State s = STATE.get();
        Arena bigInput = null;
        try {
            int r = (int) EVP_DIGEST_INIT_EX.invokeExact(s.ctx, SM3_MD, MemorySegment.NULL);
            if (r != 1) {
                throw new IllegalStateException("SM3 initialization failed");
            }

            // Reuse the per-thread buffer for common (<= 16 KB) inputs; only
            // allocate a temporary buffer for larger ones (where the copy cost
            // is negligible compared to the hashing itself).
            MemorySegment in;
            if (message.length <= INPUT_BUFFER_SIZE) {
                in = s.inBuf;
            } else {
                bigInput = Arena.ofConfined();
                in = bigInput.allocate(message.length);
            }
            MemorySegment.copy(message, 0, in, JAVA_BYTE, 0, message.length);

            r = (int) EVP_DIGEST_UPDATE.invokeExact(s.ctx, in, (long) message.length);
            if (r != 1) {
                throw new IllegalStateException("SM3 update failed");
            }

            r = (int) EVP_DIGEST_FINAL_EX.invokeExact(s.ctx, s.digest, s.lenSeg);
            if (r != 1) {
                throw new IllegalStateException("SM3 finalization failed");
            }
            if (s.lenSeg.get(JAVA_INT, 0) != SM3_DIGEST_LENGTH) {
                throw new IllegalStateException("Invalid SM3 digest length");
            }

            MemorySegment.copy(s.digest, JAVA_BYTE, 0, out, outOffset, SM3_DIGEST_LENGTH);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("SM3 FFM digest failed", t);
        } finally {
            if (bigInput != null) {
                bigInput.close();
            }
        }
    }
}
