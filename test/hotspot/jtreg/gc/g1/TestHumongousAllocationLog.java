/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

/*
 * @test
 * @summary Verify that a successful humongous allocation logs the allocating
 *          thread and its Java call stack, including virtual-thread and
 *          concurrent-allocation scenarios.
 * @requires vm.gc.G1
 * @run main TestHumongousAllocationLog
 */

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class TestHumongousAllocationLog {

    // G1HeapRegionSize is set to 1m below; an object >= 0.5 region is humongous.
    private static final int HUMONGOUS_BYTES = 2 * 1024 * 1024;

    private static final int CONCURRENT_THREADS = 4;
    private static final int ALLOCS_PER_THREAD = 3;

    static volatile Object sink;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("child")) {
            runChild();
            return;
        }
        runParentAndVerify();
    }

    // ---- parent: launch a child JVM with humongous logging, verify output ----

    private static void runParentAndVerify() throws Exception {
        String javaBin = System.getProperty("java.home") + java.io.File.separator
                + "bin" + java.io.File.separator + "java";

        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-XX:+UseG1GC");
        cmd.add("-XX:G1HeapRegionSize=1m");
        cmd.add("-Xms64m");
        cmd.add("-Xmx256m");
        cmd.add("-Xlog:gc+humongous=debug");
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("TestHumongousAllocationLog");
        cmd.add("child");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        int rc = p.waitFor();

        System.out.println("---- child output ----");
        System.out.println(out);
        System.out.println("---- end child output (exit " + rc + ") ----");

        check(rc == 0, "child exited with " + rc);

        // A header line must be produced for humongous allocations.
        long headers = out.lines()
                .filter(l -> l.contains("Humongous allocation of"))
                .count();
        check(headers > 0, "no humongous allocation header logged");

        // Platform-thread allocation: header + its call stack frame.
        check(out.contains("allocateOnPlatformThread"),
              "missing platform-thread stack frame");

        // Virtual-thread allocation: our virtual-thread marker + its frame.
        check(out.contains("virtual thread #"),
              "missing virtual thread identity in log");
        check(out.contains("allocateOnVirtualThread"),
              "missing virtual-thread stack frame");

        // Concurrent allocation: many headers were produced without the JVM
        // crashing and each carries a recognizable stack frame.
        check(out.contains("allocateConcurrently"),
              "missing concurrent-allocation stack frame");
        check(headers >= 1 + 1 + CONCURRENT_THREADS,
              "expected at least " + (2 + CONCURRENT_THREADS)
              + " humongous headers, saw " + headers);

        System.out.println("PASSED");
    }

    // ---- child: trigger humongous allocations in three scenarios ----

    private static void runChild() throws Exception {
        allocateOnPlatformThread();

        Thread vt = Thread.ofVirtual().start(
                TestHumongousAllocationLog::allocateOnVirtualThread);
        vt.join();

        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_THREADS; i++) {
            Thread t = new Thread(() -> allocateConcurrently(start), "alloc-" + i);
            t.start();
            threads.add(t);
        }
        start.countDown();
        for (Thread t : threads) {
            t.join();
        }
    }

    private static void allocateOnPlatformThread() {
        sink = new byte[HUMONGOUS_BYTES];
    }

    private static void allocateOnVirtualThread() {
        sink = new byte[HUMONGOUS_BYTES];
    }

    private static void allocateConcurrently(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        for (int i = 0; i < ALLOCS_PER_THREAD; i++) {
            sink = new byte[HUMONGOUS_BYTES];
        }
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }
}
