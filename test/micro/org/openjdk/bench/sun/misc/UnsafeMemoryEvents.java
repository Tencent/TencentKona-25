/*
 * Copyright (c) 2025, Tencent and/or its affiliates. All rights reserved.
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
 * Please contact Tencent for additional information.
 */
package org.openjdk.bench.sun.misc;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import jdk.internal.misc.Unsafe;
import jdk.jfr.Recording;

import org.openjdk.jmh.annotations.*;

/**
 * Benchmarks measuring performance impact of JFR events on
 * {@link jdk.internal.misc.Unsafe} native memory operations.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 3, jvmArgs = {
    "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED"})
public class UnsafeMemoryEvents {

    static final Unsafe U;

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            U = (Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // ---- allocateMemory: baseline ----

    @State(Scope.Thread)
    public static class AllocateState {
        @Param({"64", "1024"})
        long size;

        long addr;

        @TearDown(Level.Invocation)
        public void teardown() {
            if (addr != 0) {
                U.freeMemory(addr);
                addr = 0;
            }
        }
    }

    @Benchmark
    public void allocate_baseline(AllocateState s) {
        s.addr = U.allocateMemory(s.size);
    }

    // ---- allocateMemory: JFR enabled ----

    @State(Scope.Thread)
    public static class AllocateEnabledState {
        @Param({"64", "1024"})
        long size;

        long addr;
        Recording recording;

        @Setup(Level.Trial)
        public void startJFR() {
            recording = new Recording();
            recording.enable("jdk.AllocateMemory")
                    .withThreshold(Duration.ofNanos(0))
                    ;
            recording.start();
        }

        @TearDown(Level.Trial)
        public void stopJFR() {
            recording.close();
        }

        @TearDown(Level.Invocation)
        public void teardown() {
            if (addr != 0) {
                U.freeMemory(addr);
                addr = 0;
            }
        }
    }

    @Benchmark
    public void allocate_enabled(AllocateEnabledState s) {
        s.addr = U.allocateMemory(s.size);
    }

    // ---- reallocateMemory: baseline ----

    @State(Scope.Thread)
    public static class ReallocateState {
        @Param({"64", "1024"})
        long size;

        long addr;

        @Setup(Level.Invocation)
        public void setup() {
            addr = U.allocateMemory(size);
        }

        @TearDown(Level.Invocation)
        public void teardown() {
            if (addr != 0) {
                U.freeMemory(addr);
                addr = 0;
            }
        }
    }

    @Benchmark
    public void reallocate_baseline(ReallocateState s) {
        s.addr = U.reallocateMemory(s.addr, s.size * 2);
    }

    // ---- reallocateMemory: JFR enabled ----

    @State(Scope.Thread)
    public static class ReallocateEnabledState {
        @Param({"64", "1024"})
        long size;

        long addr;
        Recording recording;

        @Setup(Level.Trial)
        public void startJFR() {
            recording = new Recording();
            recording.enable("jdk.ReallocateMemory")
                    .withThreshold(Duration.ofNanos(0))
                    ;
            recording.start();
        }

        @TearDown(Level.Trial)
        public void stopJFR() {
            recording.close();
        }

        @Setup(Level.Invocation)
        public void setup() {
            addr = U.allocateMemory(size);
        }

        @TearDown(Level.Invocation)
        public void teardown() {
            if (addr != 0) {
                U.freeMemory(addr);
                addr = 0;
            }
        }
    }

    @Benchmark
    public void reallocate_enabled(ReallocateEnabledState s) {
        s.addr = U.reallocateMemory(s.addr, s.size * 2);
    }

    // ---- freeMemory: baseline ----

    @State(Scope.Thread)
    public static class FreeState {
        @Param({"64", "1024"})
        long size;

        long addr;

        @Setup(Level.Invocation)
        public void setup() {
            addr = U.allocateMemory(size);
        }
    }

    @Benchmark
    public void free_baseline(FreeState s) {
        U.freeMemory(s.addr);
        s.addr = 0;
    }

    // ---- freeMemory: JFR enabled ----

    @State(Scope.Thread)
    public static class FreeEnabledState {
        @Param({"64", "1024"})
        long size;

        long addr;
        Recording recording;

        @Setup(Level.Trial)
        public void startJFR() {
            recording = new Recording();
            recording.enable("jdk.FreeMemory")
                    .withThreshold(Duration.ofNanos(0))
                    ;
            recording.start();
        }

        @TearDown(Level.Trial)
        public void stopJFR() {
            recording.close();
        }

        @Setup(Level.Invocation)
        public void setup() {
            addr = U.allocateMemory(size);
        }
    }

    @Benchmark
    public void free_enabled(FreeEnabledState s) {
        U.freeMemory(s.addr);
        s.addr = 0;
    }
}
