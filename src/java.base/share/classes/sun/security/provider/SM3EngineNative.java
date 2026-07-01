/*
 * Copyright (C) 2022, 2024, Tencent. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2 as
 * published by the Free Software Foundation.
 */

package sun.security.provider;

import java.io.ByteArrayOutputStream;
import java.util.HexFormat;

/**
 * JNI-based SM3 engine (legacy).
 * <p>
 * This implementation buffers all input data and computes the SM3 digest
 * in a single JNI call. It is kept for performance comparison with the
 * new {@link SM3EngineFFM} implementation.
 * <p>
 * NOTE: This is a STUB for standalone compilation. The real implementation
 * requires the libsuncrypto native library (sun.c) and NativeSun JNI bridge.
 * In the full TencentKona-25 build, this class delegates to
 * {@code NativeSun.sm3Digest()}.
 *
 * Kept for backward compatibility and performance comparison.
 * Use {@link SM3EngineFFM} for new code.
 */
public final class SM3EngineNative implements SM3Engine {

    private ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    /** Stub: returns false since we don't have the JNI library. */
    public static boolean isAvailable() {
        return false;
    }

    @Override
    public void reset() {
        buffer.reset();
    }

    @Override
    public void update(byte message) {
        buffer.write(message);
    }

    @Override
    public void update(byte[] message) {
        buffer.writeBytes(message);
    }

    @Override
    public void update(byte[] message, int offset, int length) {
        buffer.write(message, offset, length);
    }

    @Override
    public void doFinal(byte[] out) {
        doFinal(out, 0);
    }

    @Override
    public void doFinal(byte[] out, int outOffset) {
        // Stub: use Java SM3EngineImpl internally since we don't have
        // the JNI library in standalone mode.
        byte[] message = buffer.toByteArray();
        SM3EngineImpl javaEngine = new SM3EngineImpl();
        javaEngine.update(message);
        byte[] digest = javaEngine.doFinal();
        System.arraycopy(digest, 0, out, outOffset, digest.length);
    }

    @Override
    public byte[] doFinal() {
        byte[] digest = new byte[32];
        doFinal(digest);
        return digest;
    }

    @Override
    public SM3EngineNative clone() throws CloneNotSupportedException {
        SM3EngineNative clone = (SM3EngineNative) super.clone();
        clone.buffer = new ByteArrayOutputStream();
        clone.buffer.writeBytes(this.buffer.toByteArray());
        return clone;
    }
}
