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

import java.lang.foreign.AddressLayout;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

final class FFMCrypto {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup()
            .or(LINKER.defaultLookup());
    private static final ValueLayout.OfInt C_INT =
            (ValueLayout.OfInt) LINKER.canonicalLayouts().get("int");
    private static final ValueLayout C_SIZE_T =
            (ValueLayout) LINKER.canonicalLayouts().get("size_t");
    private static final AddressLayout C_POINTER =
            (AddressLayout) LINKER.canonicalLayouts().get("void*");

    private static final MethodHandle EVP_SM3 = downcall("EVP_sm3",
            FunctionDescriptor.of(C_POINTER));
    private static final MethodHandle EVP_MD_CTX_NEW = downcall("EVP_MD_CTX_new",
            FunctionDescriptor.of(C_POINTER));
    private static final MethodHandle EVP_DIGEST_INIT_EX = downcall(
            "EVP_DigestInit_ex",
            FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_POINTER));
    private static final MethodHandle EVP_DIGEST_UPDATE = downcall(
            "EVP_DigestUpdate",
            FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_SIZE_T));
    private static final MethodHandle EVP_DIGEST_FINAL_EX = downcall(
            "EVP_DigestFinal_ex",
            FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_POINTER));
    private static final MethodHandle EVP_MD_CTX_FREE = downcall(
            "EVP_MD_CTX_free", FunctionDescriptor.ofVoid(C_POINTER));

    private FFMCrypto() {
    }

    static boolean isAvailable() {
        return EVP_SM3 != null
                && EVP_MD_CTX_NEW != null
                && EVP_DIGEST_INIT_EX != null
                && EVP_DIGEST_UPDATE != null
                && EVP_DIGEST_FINAL_EX != null
                && EVP_MD_CTX_FREE != null;
    }

    static MemorySegment evpSM3() {
        try {
            return (MemorySegment) EVP_SM3.invokeExact();
        } catch (Throwable t) {
            throw foreignCallFailed("EVP_sm3", t);
        }
    }

    static MemorySegment evpMDContextNew() {
        try {
            return (MemorySegment) EVP_MD_CTX_NEW.invokeExact();
        } catch (Throwable t) {
            throw foreignCallFailed("EVP_MD_CTX_new", t);
        }
    }

    static int evpDigestInit(MemorySegment context, MemorySegment digest) {
        try {
            return (int) EVP_DIGEST_INIT_EX.invokeExact(
                    context, digest, MemorySegment.NULL);
        } catch (Throwable t) {
            throw foreignCallFailed("EVP_DigestInit_ex", t);
        }
    }

    static int evpDigestUpdate(MemorySegment context, MemorySegment input,
            long inputLength) {
        try {
            return (int) EVP_DIGEST_UPDATE.invokeExact(
                    context, input, inputLength);
        } catch (Throwable t) {
            throw foreignCallFailed("EVP_DigestUpdate", t);
        }
    }

    static int evpDigestFinal(MemorySegment context, MemorySegment output,
            MemorySegment outputLength) {
        try {
            return (int) EVP_DIGEST_FINAL_EX.invokeExact(
                    context, output, outputLength);
        } catch (Throwable t) {
            throw foreignCallFailed("EVP_DigestFinal_ex", t);
        }
    }

    static void evpMDContextFree(MemorySegment context) {
        try {
            EVP_MD_CTX_FREE.invokeExact(context);
        } catch (Throwable t) {
            throw foreignCallFailed("EVP_MD_CTX_free", t);
        }
    }

    private static IllegalStateException foreignCallFailed(
            String symbol, Throwable cause) {
        return new IllegalStateException(symbol + " foreign call failed", cause);
    }

    @SuppressWarnings("restricted")
    private static MethodHandle downcall(String symbol,
            FunctionDescriptor descriptor) {
        return LOOKUP.find(symbol)
                .map(address -> LINKER.downcallHandle(address, descriptor))
                .orElse(null);
    }
}