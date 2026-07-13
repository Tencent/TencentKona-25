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
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package sun.security.provider;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.ref.Cleaner;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Objects;

/**
 * FFM-based SM3 engine that directly calls OpenSSL {@code libcrypto}
 * via the Foreign Function &amp; Memory API. Incremental hashing is
 * supported: {@code update()} calls {@code EVP_DigestUpdate}
 * immediately (with a 64-byte internal buffer for single-byte
 * updates), avoiding the buffering overhead of the JNI path.
 */
public final class SM3EngineFFM implements SM3Engine {

    /* ---- Static FFM linkage to libcrypto ---- */

    private static final Linker LINKER = Linker.nativeLinker();

    private static final MethodHandle evpSm3;
    private static final MethodHandle evpMdCtxNew;
    private static final MethodHandle evpMdCtxFree;
    private static final MethodHandle evpMdCtxCopyEx;
    private static final MethodHandle evpDigestInit;
    private static final MethodHandle evpDigestUpdate;
    private static final MethodHandle evpDigestFinal;
    private static final MemorySegment sm3Md;
    private static final boolean AVAILABLE;
    private static final Cleaner CLEANER = Cleaner.create();

    static {
        boolean ok = false;
        MethodHandle sm3 = null;
        MethodHandle ctxNew = null;
        MethodHandle ctxFree = null;
        MethodHandle ctxCopyEx = null;
        MethodHandle digestInit = null;
        MethodHandle digestUpdate = null;
        MethodHandle digestFinal = null;
        MemorySegment md = null;

        try {
            SymbolLookup crypto = loadCrypto();
            Linker linker = LINKER;

            @SuppressWarnings("restricted")
            MethodHandle tmpSm3 = linker.downcallHandle(
                    crypto.findOrThrow("EVP_sm3"),
                    FunctionDescriptor.of(ValueLayout.ADDRESS));
            sm3 = tmpSm3;
            md = (MemorySegment) sm3.invoke();

            @SuppressWarnings("restricted")
            MethodHandle tmpCtxNew = linker.downcallHandle(
                    crypto.findOrThrow("EVP_MD_CTX_new"),
                    FunctionDescriptor.of(ValueLayout.ADDRESS));
            ctxNew = tmpCtxNew;

            @SuppressWarnings("restricted")
            MethodHandle tmpCtxFree = linker.downcallHandle(
                    crypto.findOrThrow("EVP_MD_CTX_free"),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            ctxFree = tmpCtxFree;

            @SuppressWarnings("restricted")
            MethodHandle tmpCtxCopyEx = linker.downcallHandle(
                    crypto.findOrThrow("EVP_MD_CTX_copy_ex"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            ctxCopyEx = tmpCtxCopyEx;

            @SuppressWarnings("restricted")
            MethodHandle tmpDigestInit = linker.downcallHandle(
                    crypto.findOrThrow("EVP_DigestInit_ex"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));
            digestInit = tmpDigestInit;

            @SuppressWarnings("restricted")
            MethodHandle tmpDigestUpdate = linker.downcallHandle(
                    crypto.findOrThrow("EVP_DigestUpdate"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG));
            digestUpdate = tmpDigestUpdate;

            @SuppressWarnings("restricted")
            MethodHandle tmpDigestFinal = linker.downcallHandle(
                    crypto.findOrThrow("EVP_DigestFinal_ex"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));
            digestFinal = tmpDigestFinal;

            ok = true;
        } catch (Throwable e) {
            System.err.println(
                    "Failed to initialize SM3 FFM engine: " + e);
        }

        AVAILABLE = ok;
        evpSm3 = sm3;
        evpMdCtxNew = ctxNew;
        evpMdCtxFree = ctxFree;
        evpMdCtxCopyEx = ctxCopyEx;
        evpDigestInit = digestInit;
        evpDigestUpdate = digestUpdate;
        evpDigestFinal = digestFinal;
        sm3Md = md;
    }

    @SuppressWarnings({"removal", "restricted"})
    private static SymbolLookup loadCrypto() {
        String libPath = System.getProperty(
                "jdk.openssl.cryptoLibPath");
        if (libPath != null) {
            final String path = libPath;
            AccessController.doPrivileged(
                    (PrivilegedAction<Void>) () -> {
                        System.load(path);
                        return null;
                    });
        } else {
            AccessController.doPrivileged(
                    (PrivilegedAction<Void>) () -> {
                        System.loadLibrary("crypto");
                        return null;
                    });
        }
        return SymbolLookup.loaderLookup()
                .or(LINKER.defaultLookup());
    }

    /**
     * Returns true if the FFM SM3 engine is available for use.
     */
    static boolean isAvailable() {
        return AVAILABLE;
    }

    /* ---- Per-instance state ---- */

    private MemorySegment mdCtx;
    private byte[] pending;             // 64-byte internal buffer
    private int pendingCount;
    private final Cleaner.Cleanable cleanable;

    /**
     * Creates a new FFM-based SM3 engine with a fresh OpenSSL
     * {@code EVP_MD_CTX}. The context is automatically freed via
     * {@link Cleaner} when the engine becomes unreachable.
     */
    @SuppressWarnings("restricted")
    public SM3EngineFFM() {
        try {
            MemorySegment ctx = (MemorySegment)
                    evpMdCtxNew.invoke();
            if (MemorySegment.NULL.equals(ctx)) {
                throw new OutOfMemoryError(
                        "EVP_MD_CTX_new returned NULL");
            }
            mdCtx = ctx;
            pending = new byte[64];
            reset();
            cleanable = CLEANER.register(this, () -> {
                try {
                    evpMdCtxFree.invoke(ctx);
                } catch (Throwable ignored) {
                }
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(
                    "SM3 FFM engine init failed", e);
        }
    }

    /* ---- SM3Engine implementation ---- */

    @Override
    @SuppressWarnings("restricted")
    public void reset() {
        try {
            int rc = (int) evpDigestInit.invoke(mdCtx, sm3Md,
                    MemorySegment.NULL);
            verify(rc, "EVP_DigestInit_ex");
            pendingCount = 0;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("SM3 digest reset failed", e);
        }
    }

    @Override
    public void update(byte message) {
        pending[pendingCount++] = message;
        if (pendingCount == pending.length) {
            flushPending();
        }
    }

    @Override
    public void update(byte[] message) {
        update(message, 0, message.length);
    }

    @Override
    @SuppressWarnings("restricted")
    public void update(byte[] message, int offset, int length) {
        if (length == 0) {
            return;
        }
        Objects.checkFromIndexSize(offset, length, message.length);
        flushPending();
        try (Arena tempArena = Arena.ofConfined()) {
            MemorySegment chunk = tempArena.allocate(length);
            MemorySegment.copy(message, offset, chunk,
                    ValueLayout.JAVA_BYTE, 0, length);
            int rc = (int) evpDigestUpdate.invoke(mdCtx,
                    chunk, (long) length);
            verify(rc, "EVP_DigestUpdate");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("SM3 digest update failed", e);
        }
    }

    @Override
    public void doFinal(byte[] out) {
        doFinal(out, 0);
    }

    @Override
    @SuppressWarnings("restricted")
    public void doFinal(byte[] out, int outOffset) {
        Objects.checkFromIndexSize(outOffset, 32, out.length);
        flushPending();
        try (Arena tempArena = Arena.ofConfined()) {
            MemorySegment hashBuf = tempArena.allocate(32);
            int rc = (int) evpDigestFinal.invoke(mdCtx, hashBuf,
                    MemorySegment.NULL);
            verify(rc, "EVP_DigestFinal_ex");
            MemorySegment.copy(hashBuf, ValueLayout.JAVA_BYTE, 0,
                    out, outOffset, 32);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("SM3 doFinal failed", e);
        }
        // Reinitialize for reuse
        reset();
    }

    @Override
    public byte[] doFinal() {
        byte[] digest = new byte[32];
        doFinal(digest);
        return digest;
    }

    @Override
    public SM3Engine clone() throws CloneNotSupportedException {
        SM3EngineFFM copy;
        try {
            copy = new SM3EngineFFM();
        } catch (RuntimeException e) {
            throw new CloneNotSupportedException(
                    "SM3 FFM clone failed: " + e.getMessage());
        }

        @SuppressWarnings("restricted")
        int rc;
        try {
            rc = (int) evpMdCtxCopyEx.invoke(
                    copy.mdCtx, this.mdCtx);
        } catch (Throwable e) {
            // newCtx is managed by copy's Cleaner — no leak
            throw new CloneNotSupportedException(
                    "EVP_MD_CTX_copy_ex failed: "
                            + e.getMessage());
        }
        verify(rc, "EVP_MD_CTX_copy_ex");

        System.arraycopy(this.pending, 0, copy.pending,
                0, this.pendingCount);
        copy.pendingCount = this.pendingCount;

        return copy;
    }

    /* ---- Internal helpers ---- */

    @SuppressWarnings("restricted")
    private void flushPending() {
        if (pendingCount == 0) {
            return;
        }
        try (Arena tempArena = Arena.ofConfined()) {
            MemorySegment chunk = tempArena.allocateFrom(
                    ValueLayout.JAVA_BYTE, pending);
            int rc = (int) evpDigestUpdate.invoke(mdCtx,
                    chunk.asSlice(0, pendingCount),
                    (long) pendingCount);
            verify(rc, "EVP_DigestUpdate");
            pendingCount = 0;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(
                    "SM3 pending flush failed", e);
        }
    }

    private static void verify(int status, String operation) {
        if (status != 1) {
            throw new RuntimeException(
                    operation + " failed, status=" + status);
        }
    }
}
