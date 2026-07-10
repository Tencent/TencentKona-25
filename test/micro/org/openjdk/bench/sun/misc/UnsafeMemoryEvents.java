/*
 * Copyright (c) 2026, Tencent. All rights reserved.
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
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.bench.sun.misc;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import jdk.internal.misc.Unsafe;
import jdk.jfr.Recording;

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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode(Mode.AverageTime)
@Warmup(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 3, jvmArgs = {"--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"})
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class UnsafeMemoryEvents {
    private static final Unsafe UNSAFE;
    private static final String EVENT_UNSAFE_MEMORY_ALLOCATION = "jdk.UnsafeMemoryAllocation";
    private static final String EVENT_UNSAFE_MEMORY_REALLOCATION = "jdk.UnsafeMemoryReallocation";
    private static final String EVENT_UNSAFE_MEMORY_FREE = "jdk.UnsafeMemoryFree";

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Param({"64", "1024"})
    public long size;

    @Param({"false", "true"})
    public boolean eventsEnabled;

    private long address;
    private Recording recording;

    @Setup
    public void setup(BenchmarkParams params) {
        if (eventsEnabled) {
            recording = new Recording();
            enableEvent(params);
            recording.setToDisk(false);
            recording.start();
        }
        address = UNSAFE.allocateMemory(size);
    }

    private void enableEvent(BenchmarkParams params) {
        String benchmark = params.getBenchmark();
        if (benchmark.endsWith(".allocateMemory")) {
            recording.enable(EVENT_UNSAFE_MEMORY_ALLOCATION);
        } else if (benchmark.endsWith(".reallocateMemory")) {
            recording.enable(EVENT_UNSAFE_MEMORY_REALLOCATION);
        } else if (benchmark.endsWith(".freeMemory")) {
            recording.enable(EVENT_UNSAFE_MEMORY_FREE);
        } else {
            throw new IllegalStateException("Unknown benchmark: " + benchmark);
        }
    }

    @TearDown
    public void tearDown() {
        if (address != 0) {
            UNSAFE.freeMemory(address);
            address = 0;
        }
        if (recording != null) {
            recording.stop();
            recording.close();
            recording = null;
        }
    }

    @Benchmark
    public void allocateMemory(Blackhole bh) {
        long p = UNSAFE.allocateMemory(size);
        bh.consume(p);
        UNSAFE.freeMemory(p);
    }

    @Benchmark
    public void reallocateMemory(Blackhole bh) {
        address = UNSAFE.reallocateMemory(address, size);
        bh.consume(address);
    }

    @Benchmark
    public void freeMemory() {
        long p = UNSAFE.allocateMemory(size);
        UNSAFE.freeMemory(p);
    }
}
