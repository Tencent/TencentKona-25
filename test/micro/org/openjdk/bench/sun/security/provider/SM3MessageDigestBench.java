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

package org.openjdk.bench.sun.security.provider;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.security.MessageDigest;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@Fork(value = 3)
@Warmup(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
public class SM3MessageDigestBench {

    private static final int SET_SIZE = 128;

    @Param({"1024", "1048576"})
    private int dataSize;

    private byte[][] data;
    private int index;

    @Setup
    public void setup() {
        Random random = new Random(1123);
        data = new byte[SET_SIZE][dataSize];
        for (byte[] d : data) {
            random.nextBytes(d);
        }
    }

    @Benchmark
    public byte[] digest() throws Exception {
        MessageDigest md = MessageDigest.getInstance("SM3");
        byte[] d = data[index];
        index = (index + 1) % SET_SIZE;
        return md.digest(d);
    }
}
