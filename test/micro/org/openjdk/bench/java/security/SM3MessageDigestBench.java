/*
 * Copyright (C) 2026, Tencent. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
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
package org.openjdk.bench.java.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Benchmarks the SM3 message digest cold path: a fresh
 * {@code MessageDigest} instance per operation. The engine (pure Java
 * vs JNI native via OpenSSL) is controlled by JVM system property.
 *
 * <pre>
 *   java -jar benchmarks.jar SM3MessageDigestBench                          # Java
 *   java -Djdk.sun.enableNativeCrypto=true -jar benchmarks.jar ...          # JNI
 *   java -Djdk.sun.enableFFMCrypto=true   -jar benchmarks.jar ...          # FFM
 * </pre>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(jvmArgs = {"-Xms1024m", "-Xmx1024m", "-Xmn768m", "-XX:+UseParallelGC"},
        value = 3)
public class SM3MessageDigestBench {

    @Param({"64", "256", "512", "1024", "4096", "16384", "65536", "1048576"})
    private int length;

    private byte[] inputBytes;

    @Setup(Level.Trial)
    public void setup() {
        inputBytes = new byte[length];
        new Random(1234567890).nextBytes(inputBytes);

        boolean nativeRequested = Boolean.getBoolean(
                "jdk.sun.enableNativeCrypto");
    }

    /**
     * Cold path: obtains a fresh {@code MessageDigest} instance and
     * performs a single-shot digest. Measures engine creation plus
     * hashing cost.
     */
    @Benchmark
    public byte[] digest() throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SM3");
        return md.digest(inputBytes);
    }
}
