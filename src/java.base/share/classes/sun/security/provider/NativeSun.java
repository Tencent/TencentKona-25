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
import java.util.Objects;

import static java.lang.foreign.MemorySegment.NULL;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The native implementation with OpenSSL and FFM API for Sun algorithms.
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
        boolean enableNativeCrypto = Boolean.getBoolean(
                "jdk.sun.enableNativeCrypto");
        MethodHandle evpSM3 = null;
        MethodHandle evpMDCTXNew = null;
        MethodHandle evpMDCTXFree = null;
        MethodHandle evpDigestInitEx = null;
        MethodHandle evpDigestUpdate = null;
        MethodHandle evpDigestFinalEx = null;
        boolean enabled = false;

        if (enableNativeCrypto && OpenSSLUtil.isOpenSSLLoaded()) {
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup lookup = SymbolLookup.loaderLookup()
                        .or(linker.defaultLookup());
                evpSM3 = downcall(linker, lookup, "EVP_sm3",
                        FunctionDescriptor.of(ADDRESS));
                evpMDCTXNew = downcall(linker, lookup, "EVP_MD_CTX_new",
                        FunctionDescriptor.of(ADDRESS));
                evpMDCTXFree = downcall(linker, lookup, "EVP_MD_CTX_free",
                        FunctionDescriptor.ofVoid(ADDRESS));
                evpDigestInitEx = downcall(linker, lookup, "EVP_DigestInit_ex",
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
                evpDigestUpdate = downcall(linker, lookup, "EVP_DigestUpdate",
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG));
                evpDigestFinalEx = downcall(linker, lookup, "EVP_DigestFinal_ex",
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
                enabled = true;
            } catch (Throwable e) {
                System.err.println("Failed to initialize OpenSSL SM3 FFM downcalls: " + e);
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
    private static MethodHandle downcall(Linker linker, SymbolLookup lookup,
            String name, FunctionDescriptor descriptor) {
        return linker.downcallHandle(lookup.findOrThrow(name), descriptor);
    }

    static boolean isNativeCryptoEnabled() {
        return IS_NATIVE_CRYPTO_ENABLED;
    }

    public static void sm3Digest(byte[] message, byte[] out, int outOffset) {
        Objects.requireNonNull(message, "Input array must not be null");
        Objects.requireNonNull(out, "Output array must not be null");
        Objects.checkFromIndexSize(outOffset, SM3_DIGEST_LENGTH, out.length);

        if (!IS_NATIVE_CRYPTO_ENABLED) {
            throw new IllegalStateException("Native SUN crypto is not enabled");
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment sm3 = (MemorySegment) EVP_SM3.invokeExact();
            if (sm3.equals(NULL)) {
                throw new IllegalStateException("SM3 algorithm not available");
            }

            MemorySegment ctx = (MemorySegment) EVP_MD_CTX_NEW.invokeExact();
            if (ctx.equals(NULL)) {
                throw new IllegalStateException("Failed to create SM3 context");
            }

            try {
                checkReturn((int) EVP_DIGEST_INIT_EX.invokeExact(ctx, sm3, NULL),
                        "SM3 initialization failed");

                MemorySegment messageSegment = arena.allocateFrom(
                        ValueLayout.JAVA_BYTE, message);
                checkReturn((int) EVP_DIGEST_UPDATE.invokeExact(
                        ctx, messageSegment, (long) message.length),
                        "SM3 update failed");

                MemorySegment digestSegment = arena.allocate(SM3_DIGEST_LENGTH);
                MemorySegment digestLength = arena.allocate(JAVA_INT);
                checkReturn((int) EVP_DIGEST_FINAL_EX.invokeExact(
                        ctx, digestSegment, digestLength),
                        "SM3 finalization failed");

                if (digestLength.get(JAVA_INT, 0) != SM3_DIGEST_LENGTH) {
                    throw new IllegalStateException("Invalid SM3 digest length");
                }

                MemorySegment.copy(digestSegment, ValueLayout.JAVA_BYTE, 0,
                        out, outOffset, SM3_DIGEST_LENGTH);
            } finally {
                EVP_MD_CTX_FREE.invokeExact(ctx);
            }
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("SM3 digest failed", e);
        }
    }

    private static void checkReturn(int result, String message) {
        if (result != 1) {
            throw new IllegalStateException(message);
        }
    }
}
