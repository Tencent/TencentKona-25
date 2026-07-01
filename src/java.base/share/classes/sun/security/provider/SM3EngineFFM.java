/*
 * Copyright (C) 2025, 2026, Tencent. All rights reserved.
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

import java.lang.foreign.*;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.security.ProviderException;

/**
 * FFM (Foreign Function &amp; Memory) based SM3 engine.
 * <p>
 * This implementation uses the JDK 25 FFM API to directly call
 * OpenSSL's libcrypto EVP functions for SM3 hashing, eliminating
 * the JNI bridge layer ({@code sun.c}) and its associated overhead.
 * <p>
 * Unlike {@link SM3EngineNative} which buffers all data before
 * computing the digest, this engine performs true streaming:
 * each {@code update()} call immediately invokes
 * {@code EVP_DigestUpdate} on the native OpenSSL context.
 * <p>
 * Resource cleanup is handled via {@link java.lang.ref.Cleaner},
 * ensuring native memory ({@code EVP_MD_CTX}) is freed even if
 * {@code doFinal()} is never called.
 *
 * @see SM3Engine
 * @see SM3EngineImpl
 * @see SM3EngineNative
 * @since 25
 */
@SuppressWarnings("restricted")
public final class SM3EngineFFM implements SM3Engine {

    // ---- Static FFM bindings ----

    private static final Linker LINKER = Linker.nativeLinker();

    // SymbolLookup.loaderLookup() searches symbols exported by libraries
    // loaded via System.loadLibrary() — which includes OpenSSL libcrypto
    // already loaded by OpenSSLUtil.
    private static final SymbolLookup LIBCRYPTO;

    // Cached method handles for OpenSSL EVP functions
    private static final MethodHandle EVP_SM3_HANDLE;
    private static final MethodHandle EVP_MD_CTX_NEW_HANDLE;
    private static final MethodHandle EVP_DIGEST_INIT_EX_HANDLE;
    private static final MethodHandle EVP_DIGEST_UPDATE_HANDLE;
    private static final MethodHandle EVP_DIGEST_FINAL_EX_HANDLE;
    private static final MethodHandle EVP_MD_CTX_COPY_EX_HANDLE;
    private static final MethodHandle EVP_MD_CTX_FREE_HANDLE;

    // Cached SM3 algorithm pointer (EVP_MD*).
    // This is a process-wide singleton in OpenSSL — safe to cache statically.
    private static final MemorySegment EVP_MD_SM3;

    private static final int SM3_DIGEST_LENGTH = 32;

    static {
        try {
            // Build a composite SymbolLookup from multiple sources:
            // 1. Library lookup for common OpenSSL names (most reliable)
            // 2. Loader lookup (finds symbols from System.loadLibrary-loaded libs)
            // 3. Linker default lookup (system library symbols)
            LIBCRYPTO = buildCryptoLookup();

            // Resolve OpenSSL EVP function symbols.
            MemorySegment evpSm3Addr = lookupSymbol("EVP_sm3");
            MemorySegment evpMdCtxNewAddr = lookupSymbol("EVP_MD_CTX_new");
            MemorySegment evpDigestInitExAddr = lookupSymbol("EVP_DigestInit_ex");
            MemorySegment evpDigestUpdateAddr = lookupSymbol("EVP_DigestUpdate");
            MemorySegment evpDigestFinalExAddr = lookupSymbol("EVP_DigestFinal_ex");
            MemorySegment evpMdCtxCopyExAddr = lookupSymbol("EVP_MD_CTX_copy_ex");
            MemorySegment evpMdCtxFreeAddr = lookupSymbol("EVP_MD_CTX_free");

            // Create downcall handles

            // const EVP_MD* EVP_sm3(void)
            EVP_SM3_HANDLE = LINKER.downcallHandle(
                    evpSm3Addr,
                    FunctionDescriptor.of(ValueLayout.ADDRESS));

            // EVP_MD_CTX* EVP_MD_CTX_new(void)
            EVP_MD_CTX_NEW_HANDLE = LINKER.downcallHandle(
                    evpMdCtxNewAddr,
                    FunctionDescriptor.of(ValueLayout.ADDRESS));

            // int EVP_DigestInit_ex(EVP_MD_CTX *ctx, const EVP_MD *type, ENGINE *impl)
            EVP_DIGEST_INIT_EX_HANDLE = LINKER.downcallHandle(
                    evpDigestInitExAddr,
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));

            // int EVP_DigestUpdate(EVP_MD_CTX *ctx, const void *d, size_t cnt)
            EVP_DIGEST_UPDATE_HANDLE = LINKER.downcallHandle(
                    evpDigestUpdateAddr,
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG));

            // int EVP_DigestFinal_ex(EVP_MD_CTX *ctx, unsigned char *md, unsigned int *s)
            EVP_DIGEST_FINAL_EX_HANDLE = LINKER.downcallHandle(
                    evpDigestFinalExAddr,
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));

            // int EVP_MD_CTX_copy_ex(EVP_MD_CTX *out, const EVP_MD_CTX *in)
            EVP_MD_CTX_COPY_EX_HANDLE = LINKER.downcallHandle(
                    evpMdCtxCopyExAddr,
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));

            // void EVP_MD_CTX_free(EVP_MD_CTX *ctx)
            EVP_MD_CTX_FREE_HANDLE = LINKER.downcallHandle(
                    evpMdCtxFreeAddr,
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

            // Cache the SM3 algorithm pointer
            EVP_MD_SM3 = (MemorySegment) EVP_SM3_HANDLE.invokeExact();

        } catch (Throwable t) {
            // If FFM initialization fails (e.g., OpenSSL not available),
            // throw an ExceptionInInitializerError which will be caught
            // by SM3MessageDigest, falling back to the Java implementation.
            throw new ExceptionInInitializerError(
                    "Failed to initialize FFM bindings for SM3: " + t.getMessage());
        }
    }

    /**
     * Builds a composite SymbolLookup that searches for OpenSSL symbols
     * across multiple library sources.
     * <p>
     * Strategy (tried in order):
     * <ol>
     *   <li>{@code SymbolLookup.libraryLookup()} — loads libcrypto by
     *       common platform-specific names</li>
     *   <li>{@code SymbolLookup.loaderLookup()} — finds symbols from
     *       libraries loaded via {@code System.loadLibrary()}</li>
     *   <li>{@code Linker.defaultLookup()} — system library symbols</li>
     * </ol>
     */
    private static SymbolLookup buildCryptoLookup() {
        SymbolLookup composite = SymbolLookup.loaderLookup();

        // Common OpenSSL library names across platforms
        String[] cryptoLibNames = {
            "libcrypto-3-x64",       // Windows MSYS2/MinGW OpenSSL 3.x 64-bit
            "libcrypto-3",           // Linux OpenSSL 3.x
            "libcrypto",             // Generic POSIX
            "crypto-3-x64",          // Windows (without lib prefix)
            "libcrypto.so.3",        // Linux with .so
            "libcrypto.3.dylib",     // macOS
            "libcrypto-1_1-x64",     // Windows OpenSSL 1.1.x
            "libcrypto-1_1",         // Linux OpenSSL 1.1.x
        };

        for (String libName : cryptoLibNames) {
            try {
                SymbolLookup libLookup = SymbolLookup.libraryLookup(
                        libName, Arena.global());
                // if this succeeds, chain it into the composite
                composite = composite.or(libLookup);
            } catch (IllegalArgumentException e) {
                // Library not found on this system — skip
            }
        }

        // Also try the linker's default lookup (system libs)
        composite = composite.or(LINKER.defaultLookup());

        return composite;
    }

    /**
     * Look up a symbol in the loader lookup, with fallback to the
     * linker's default lookup (system libraries).
     */
    private static MemorySegment lookupSymbol(String name) {
        return LIBCRYPTO.find(name)
                .or(() -> LINKER.defaultLookup().find(name))
                .orElseThrow(() -> new UnsatisfiedLinkError(
                        "OpenSSL symbol not found: " + name));
    }

    // ---- Instance fields ----

    // Confined arena — owns the EVP_MD_CTX allocation.
    // Confined is correct because MessageDigest is not thread-safe per spec.
    private Arena arena;

    // Native pointer to EVP_MD_CTX
    private MemorySegment ctx;

    // Pre-allocated 1-byte segment for single-byte update() calls
    private MemorySegment oneByteSegment;

    // Cleaner for automatic native resource cleanup
    private java.lang.ref.Cleaner.Cleanable cleanable;

    // Guard: set true after dispose() to prevent use-after-free.
    // Normal doFinal()/reset() cycles do NOT set this flag —
    // they auto-reset the context per the MessageDigest contract.
    private boolean disposed;

    // ---- Constructors ----

    /**
     * Creates a new FFM-based SM3 engine.
     * Initializes the OpenSSL EVP digest context for SM3.
     *
     * @throws ProviderException if OpenSSL initialization fails
     */
    public SM3EngineFFM() {
        this(false);
    }

    /**
     * Internal constructor for clone support.
     *
     * @param skipInit if true, does not initialize the digest context
     *                 (caller must either copy from source or init separately)
     */
    private SM3EngineFFM(boolean skipInit) {
        if (skipInit) {
            return;
        }
        initEngine();
    }

    /**
     * Initializes the engine: creates arena, allocates EVP_MD_CTX,
     * and initializes the SM3 digest.
     */
    private void initEngine() {
        try {
            this.arena = Arena.ofConfined();
            this.ctx = (MemorySegment) EVP_MD_CTX_NEW_HANDLE.invokeExact();
            this.oneByteSegment = arena.allocate(ValueLayout.JAVA_BYTE);

            if (MemorySegment.NULL.equals(ctx)) {
                throw new ProviderException("EVP_MD_CTX_new returned NULL");
            }

            int rc = (int) EVP_DIGEST_INIT_EX_HANDLE.invokeExact(
                    ctx, EVP_MD_SM3, MemorySegment.NULL);
            checkReturn(rc, "EVP_DigestInit_ex");

            // Register cleanup with Cleaner for GC-triggered resource release
            this.cleanable = CleanerHolder.CLEANER.register(this, new CleanupAction(ctx, arena));

        } catch (ProviderException e) {
            throw e;
        } catch (Throwable t) {
            // Clean up partial initialization on failure
            if (ctx != null && !MemorySegment.NULL.equals(ctx)) {
                try {
                    EVP_MD_CTX_FREE_HANDLE.invokeExact(ctx);
                } catch (Throwable ignored) {
                }
            }
            if (arena != null) {
                try {
                    arena.close();
                } catch (Throwable ignored) {
                }
            }
            throw new ProviderException("Failed to initialize SM3 FFM engine: " + t.getMessage(), t);
        }
    }

    // ---- SM3Engine interface methods ----

    @Override
    public void reset() {
        checkNotDisposed();
        // Re-initialize digest on the existing context.
        // EVP_DigestInit_ex with the same ctx resets the internal state,
        // avoiding the cost of EVP_MD_CTX_free + EVP_MD_CTX_new.
        reinitContext();
    }

    @Override
    public void update(byte message) {
        checkNotDisposed();
        // Write the byte into the pre-allocated segment and call update
        oneByteSegment.set(ValueLayout.JAVA_BYTE, 0, message);
        try {
            int rc = (int) EVP_DIGEST_UPDATE_HANDLE.invokeExact(
                    ctx, (MemorySegment) oneByteSegment, 1L);
            checkReturn(rc, "update(byte)");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new ProviderException("SM3 update failed: " + t.getMessage(), t);
        }
    }

    @Override
    public void update(byte[] message) {
        update(message, 0, message.length);
    }

    @Override
    public void update(byte[] message, int offset, int length) {
        checkNotDisposed();
        if (length == 0) {
            return;
        }
        // Allocate a temporary native segment from a short-lived arena,
        // copy the data from the Java heap array, and pass to OpenSSL.
        // This avoids growing the engine's arena with variable-sized data.
        try (Arena tempArena = Arena.ofConfined()) {
            MemorySegment dataSegment = tempArena.allocate(
                    ValueLayout.JAVA_BYTE, length);
            // Copy data from the Java heap array to the native segment
            MemorySegment.copy(
                    MemorySegment.ofArray(message), offset,
                    dataSegment, 0, length);
            int rc = (int) EVP_DIGEST_UPDATE_HANDLE.invokeExact(
                    ctx, dataSegment, (long) length);
            checkReturn(rc, "update(byte[], int, int)");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new ProviderException("SM3 update failed: " + t.getMessage(), t);
        }
    }

    @Override
    public void doFinal(byte[] out) {
        doFinal(out, 0);
    }

    @Override
    public void doFinal(byte[] out, int outOffset) {
        checkNotDisposed();
        try {
            // Allocate a native segment for the output digest
            MemorySegment outSegment = arena.allocate(
                    ValueLayout.JAVA_BYTE, SM3_DIGEST_LENGTH);

            // Allocate a temporary segment to hold the output length (unsigned int)
            MemorySegment outLenSegment = arena.allocate(ValueLayout.JAVA_INT);

            int rc = (int) EVP_DIGEST_FINAL_EX_HANDLE.invokeExact(
                    ctx, outSegment, outLenSegment);
            checkReturn(rc, "EVP_DigestFinal_ex");

            int digestLen = outLenSegment.get(ValueLayout.JAVA_INT, 0);
            if (digestLen != SM3_DIGEST_LENGTH) {
                throw new ProviderException(
                        "Invalid SM3 digest length: " + digestLen +
                        " (expected " + SM3_DIGEST_LENGTH + ")");
            }

            // Copy the native digest back to the Java byte array
            MemorySegment.ofArray(out)
                    .asSlice(outOffset, SM3_DIGEST_LENGTH)
                    .copyFrom(outSegment);

        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new ProviderException("SM3 doFinal failed: " + t.getMessage(), t);
        } finally {
            // Auto-reset the context per MessageDigest contract.
            // EVP_DigestFinal_ex consumes the internal state; we must
            // re-initialize to make the engine reusable.
            reinitContext();
        }
    }

    @Override
    public byte[] doFinal() {
        byte[] digest = new byte[SM3_DIGEST_LENGTH];
        doFinal(digest);
        return digest;
    }

    @Override
    @SuppressWarnings("unchecked")
    public SM3EngineFFM clone() throws CloneNotSupportedException {
        checkNotDisposed();
        try {
            SM3EngineFFM clone = (SM3EngineFFM) super.clone();
            clone.arena = Arena.ofConfined();
            clone.ctx = (MemorySegment) EVP_MD_CTX_NEW_HANDLE.invokeExact();

            if (MemorySegment.NULL.equals(clone.ctx)) {
                clone.arena.close();
                throw new ProviderException("EVP_MD_CTX_new returned NULL during clone");
            }

            // Copy the internal hash state from source to destination
            int rc = (int) EVP_MD_CTX_COPY_EX_HANDLE.invokeExact(clone.ctx, this.ctx);
            checkReturn(rc, "EVP_MD_CTX_copy_ex (clone)");

            clone.oneByteSegment = clone.arena.allocate(ValueLayout.JAVA_BYTE);
            clone.disposed = false;

            // Register cleanup for the cloned engine
            clone.cleanable = CleanerHolder.CLEANER.register(
                    clone, new CleanupAction(clone.ctx, clone.arena));

            return clone;
        } catch (CloneNotSupportedException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new ProviderException("SM3 clone failed: " + t.getMessage(), t);
        }
    }

    // ---- Internal helpers ----

    /**
     * Checks that the engine has not been disposed.
     */
    private void checkNotDisposed() {
        if (disposed) {
            throw new IllegalStateException(
                    "SM3 engine has been disposed");
        }
    }

    /**
     * Re-initializes the EVP digest context for reuse.
     * Called after {@code doFinal()} and from {@code reset()}.
     */
    private void reinitContext() {
        try {
            int rc = (int) EVP_DIGEST_INIT_EX_HANDLE.invokeExact(
                    ctx, EVP_MD_SM3, MemorySegment.NULL);
            checkReturn(rc, "EVP_DigestInit_ex (reinit)");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new ProviderException(
                    "SM3 context reinit failed: " + t.getMessage(), t);
        }
    }

    /**
     * Checks an OpenSSL return code. OpenSSL returns 1 for success.
     */
    private static void checkReturn(int rc, String operation) {
        if (rc != 1) {
            throw new ProviderException(
                    "SM3 native operation failed: " + operation +
                    " (OpenSSL returned " + rc + ")");
        }
    }

    /**
     * Dispose native resources. Called by Cleaner or explicitly.
     */
    void dispose() {
        if (ctx != null && !MemorySegment.NULL.equals(ctx)) {
            try {
                EVP_MD_CTX_FREE_HANDLE.invokeExact(ctx);
            } catch (Throwable ignored) {
                // Best-effort cleanup
            }
            ctx = null;
        }
        if (arena != null) {
            try {
                arena.close();
            } catch (Throwable ignored) {
                // Best-effort cleanup
            }
            arena = null;
        }
        disposed = true;
    }

    // ---- Cleaner infrastructure ----

    /**
     * Holder for the shared Cleaner instance (lazy initialization).
     */
    private static final class CleanerHolder {
        static final java.lang.ref.Cleaner CLEANER = java.lang.ref.Cleaner.create();
    }

    /**
     * Cleanup action that frees the EVP_MD_CTX and closes the arena.
     */
    private record CleanupAction(MemorySegment ctx, Arena arena) implements Runnable {
        @Override
        public void run() {
            if (ctx != null && !MemorySegment.NULL.equals(ctx)) {
                try {
                    EVP_MD_CTX_FREE_HANDLE.invokeExact(ctx);
                } catch (Throwable ignored) {
                }
            }
            if (arena != null) {
                try {
                    arena.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
