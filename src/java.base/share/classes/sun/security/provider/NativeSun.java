/*
 * Copyright (C) 2025, Tencent. All rights reserved.
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

import sun.security.util.OpenSSLUtil;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The native implementation with OpenSSL for Sun algorithms.
 */
final class NativeSun {

    private static final int SM3_DIGEST_LENGTH = 32;

    private static final boolean IS_NATIVE_CRYPTO_ENABLED;

    private static final MethodHandle EVP_SM3;
    private static final MethodHandle EVP_MD_CTX_NEW;
    private static final MethodHandle EVP_MD_CTX_FREE;
    private static final MethodHandle EVP_DIGEST_INIT_EX;
    private static final MethodHandle EVP_DIGEST_UPDATE;
    private static final MethodHandle EVP_DIGEST_FINAL_EX;

    static {
        MethodHandle evpSM3 = null;
        MethodHandle evpMDCTXNew = null;
        MethodHandle evpMDCTXFree = null;
        MethodHandle evpDigestInitEx = null;
        MethodHandle evpDigestUpdate = null;
        MethodHandle evpDigestFinalEx = null;

        boolean enabled = Boolean.getBoolean("jdk.sun.enableNativeCrypto")
                && OpenSSLUtil.isOpenSSLLoaded();

        if (enabled) {
            try {
                Linker linker = Linker.nativeLinker();

                evpSM3 = downcall(linker, "EVP_sm3",
                        FunctionDescriptor.of(ValueLayout.ADDRESS));
                evpMDCTXNew = downcall(linker, "EVP_MD_CTX_new",
                        FunctionDescriptor.of(ValueLayout.ADDRESS));
                evpMDCTXFree = downcall(linker, "EVP_MD_CTX_free",
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
                evpDigestInitEx = downcall(linker, "EVP_DigestInit_ex",
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS));
                evpDigestUpdate = downcall(linker, "EVP_DigestUpdate",
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG));
                evpDigestFinalEx = downcall(linker, "EVP_DigestFinal_ex",
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS));
            } catch (Throwable e) {
                System.err.println("Failed to initialize OpenSSL FFM bindings: " + e);
                enabled = false;
            }
        }

        EVP_SM3 = evpSM3;
        EVP_MD_CTX_NEW = evpMDCTXNew;
        EVP_MD_CTX_FREE = evpMDCTXFree;
        EVP_DIGEST_INIT_EX = evpDigestInitEx;
        EVP_DIGEST_UPDATE = evpDigestUpdate;
        EVP_DIGEST_FINAL_EX = evpDigestFinalEx;
        IS_NATIVE_CRYPTO_ENABLED = enabled;
    }

    @SuppressWarnings("restricted")
    private static MethodHandle downcall(Linker linker, String name,
            FunctionDescriptor descriptor) {
        MemorySegment symbol = findOpenSSLSymbol(name).orElseThrow(
                () -> new UnsatisfiedLinkError("OpenSSL symbol not found: " + name));
        return linker.downcallHandle(symbol, descriptor);
    }

    @SuppressWarnings("restricted")
    private static Optional<MemorySegment> findOpenSSLSymbol(String name) {
        Optional<MemorySegment> symbol = SymbolLookup.loaderLookup().find(name);
        if (symbol.isPresent()) {
            return symbol;
        }

        String opensslCryptoLibPath = System.getProperty("jdk.openssl.cryptoLibPath");
        if (opensslCryptoLibPath != null) {
            return SymbolLookup.libraryLookup(
                    Path.of(opensslCryptoLibPath), Arena.global()).find(name);
        }
        return SymbolLookup.libraryLookup("crypto", Arena.global()).find(name);
    }

    static boolean isNativeCryptoEnabled() {
        return IS_NATIVE_CRYPTO_ENABLED;
    }

    static void sm3Digest(byte[] message, byte[] out, int outOffset) {
        if (message == null || out == null) {
            throw new NullPointerException("Input/output arrays must not be null");
        }
        if (outOffset < 0 || outOffset + SM3_DIGEST_LENGTH > out.length) {
            throw new IndexOutOfBoundsException("Output buffer too small");
        }
        if (!IS_NATIVE_CRYPTO_ENABLED) {
            throw new IllegalStateException("OpenSSL FFM SM3 is not available");
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment sm3 = (MemorySegment) EVP_SM3.invokeExact();
            if (sm3.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("SM3 algorithm not available");
            }

            MemorySegment ctx = (MemorySegment) EVP_MD_CTX_NEW.invokeExact();
            if (ctx.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("Failed to create SM3 context");
            }

            try {
                check((int) EVP_DIGEST_INIT_EX.invokeExact(
                        ctx, sm3, MemorySegment.NULL),
                        "SM3 initialization failed");

                MemorySegment in = arena.allocate(message.length);
                MemorySegment.copy(message, 0, in, ValueLayout.JAVA_BYTE,
                        0, message.length);
                check((int) EVP_DIGEST_UPDATE.invokeExact(
                        ctx, in, (long) message.length),
                        "SM3 update failed");

                MemorySegment digest = arena.allocate(SM3_DIGEST_LENGTH);
                MemorySegment digestLength = arena.allocate(ValueLayout.JAVA_INT);
                check((int) EVP_DIGEST_FINAL_EX.invokeExact(
                        ctx, digest, digestLength),
                        "SM3 finalization failed");

                int actualDigestLength = digestLength.get(ValueLayout.JAVA_INT, 0);
                if (actualDigestLength != SM3_DIGEST_LENGTH) {
                    throw new IllegalStateException("Invalid SM3 digest length");
                }

                byte[] digestBytes = digest.toArray(ValueLayout.JAVA_BYTE);
                System.arraycopy(digestBytes, 0, out, outOffset, SM3_DIGEST_LENGTH);
            } finally {
                EVP_MD_CTX_FREE.invokeExact(ctx);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("OpenSSL FFM SM3 invocation failed", e);
        }
    }

    private static void check(int result, String message) {
        if (result != 1) {
            throw new IllegalStateException(message);
        }
    }
}
