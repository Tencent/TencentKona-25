/*
 * Copyright (C) 2022, 2024, Tencent. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2 as
 * published by the Free Software Foundation.
 */

package sun.security.provider;

/**
 * SM3 hash engine interface.
 * Implementations define the core SM3 hash computation strategy.
 */
public interface SM3Engine extends Cloneable {

    /**
     * Resets the engine to its initial state.
     */
    void reset();

    /**
     * Updates the digest with a single byte.
     */
    void update(byte message);

    /**
     * Updates the digest with a byte array.
     */
    void update(byte[] message);

    /**
     * Updates the digest with a portion of a byte array.
     */
    void update(byte[] message, int offset, int length);

    /**
     * Completes the hash computation and stores the result in the output array.
     */
    void doFinal(byte[] out);

    /**
     * Completes the hash computation and stores the result at the given offset.
     */
    void doFinal(byte[] out, int offset);

    /**
     * Completes the hash computation and returns the digest.
     */
    byte[] doFinal();

    /**
     * Creates a clone of this engine with the same internal state.
     */
    SM3Engine clone() throws CloneNotSupportedException;
}
