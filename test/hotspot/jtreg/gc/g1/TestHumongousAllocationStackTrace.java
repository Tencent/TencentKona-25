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

package gc.g1;

/*
 * @test TestHumongousAllocationStackTrace
 * @summary Check that successful G1 humongous allocations log the allocating
 *          Java thread and call stack.
 * @requires vm.gc.G1
 * @requires vm.continuations
 * @requires vm.flagless
 * @library /test/lib
 * @run driver gc.g1.TestHumongousAllocationStackTrace
 */

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestHumongousAllocationStackTrace {
    private static final int HUMONGOUS_OBJECT_SIZE = 768 * 1024;
    private static final int FAILING_HUMONGOUS_OBJECT_SIZE = 64 * 1024 * 1024;
    private static final String LOG_PREFIX = "Humongous allocation succeeded:";
    private static final String STACK_HEADER = "Java call stack:";
    private static final String PLATFORM_THREAD_NAME = "humongous-platform";
    private static final String VIRTUAL_THREAD_NAME = "humongous-virtual";
    private static final String CONCURRENT_THREAD_PREFIX = "humongous-concurrent-";
    private static final String GC_THREAD_NAME = "humongous-after-gc";
    private static final int CONCURRENT_THREAD_COUNT = 8;
    private static final int CONCURRENT_ALLOCATION_COUNT = 3;
    private static final CountDownLatch ALLOCATION_READY = new CountDownLatch(2);
    private static final CountDownLatch START_ALLOCATION = new CountDownLatch(1);
    private static volatile Object sink;
    private static volatile Object[][] concurrentSink;

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            switch (args[0]) {
                case "allocate" -> allocateHumongousObjects();
                case "fail" -> failHumongousAllocation();
                case "gc" -> allocateAfterGc();
                default -> throw new IllegalArgumentException("Unknown mode: " + args[0]);
            }
            return;
        }

        OutputAnalyzer debugOutput = runWithLogging("debug");
        debugOutput.shouldHaveExitValue(0);
        List<LogBlock> debugBlocks = parseLogBlocks(debugOutput.getOutput());
        assertExactLogCount(debugBlocks, PLATFORM_THREAD_NAME, "allocateFromPlatformThread", 1);
        assertExactLogCount(debugBlocks, VIRTUAL_THREAD_NAME, "allocateFromVirtualThread", 1);
        for (int i = 0; i < CONCURRENT_THREAD_COUNT; i++) {
            assertExactLogCount(debugBlocks, CONCURRENT_THREAD_PREFIX + i, "allocateFromConcurrentThread",
                    CONCURRENT_ALLOCATION_COUNT);
        }

        OutputAnalyzer infoOutput = runWithLogging("info");
        infoOutput.shouldHaveExitValue(0);
        infoOutput.shouldNotContain(LOG_PREFIX);

        OutputAnalyzer gcOutput = runGcThenSuccessfulAllocation();
        gcOutput.shouldHaveExitValue(0);
        // The number of GC-assisted successful allocations depends on runtime
        // heap state, so only require at least one complete log block.
        assertAtLeastOneLog(parseLogBlocks(gcOutput.getOutput()), GC_THREAD_NAME, "allocateAfterGc");

        OutputAnalyzer failureOutput = runFailingAllocation();
        failureOutput.shouldHaveExitValue(0);
        failureOutput.shouldContain("Caught expected OutOfMemoryError");
        failureOutput.shouldNotContain(LOG_PREFIX);
    }

    private static OutputAnalyzer runWithLogging(String level) throws Exception {
        return ProcessTools.executeLimitedTestJava(
                "-XX:+UseG1GC",
                "-Xms64m",
                "-Xmx64m",
                "-XX:G1HeapRegionSize=1m",
                "-Xint",
                "-Xlog:gc+humongous=" + level,
                TestHumongousAllocationStackTrace.class.getName(),
                "allocate");
    }

    private static OutputAnalyzer runFailingAllocation() throws Exception {
        return ProcessTools.executeLimitedTestJava(
                "-XX:+UseG1GC",
                "-Xms16m",
                "-Xmx16m",
                "-XX:G1HeapRegionSize=1m",
                "-Xint",
                "-Xlog:gc+humongous=debug",
                TestHumongousAllocationStackTrace.class.getName(),
                "fail");
    }

    private static OutputAnalyzer runGcThenSuccessfulAllocation() throws Exception {
        return ProcessTools.executeLimitedTestJava(
                "-XX:+UseG1GC",
                "-Xms8m",
                "-Xmx8m",
                "-XX:G1HeapRegionSize=1m",
                "-Xint",
                "-Xlog:gc+humongous=debug",
                TestHumongousAllocationStackTrace.class.getName(),
                "gc");
    }

    private static void allocateHumongousObjects() throws Exception {
        Thread platformThread = new Thread(
                TestHumongousAllocationStackTrace::allocateFromPlatformThread,
                PLATFORM_THREAD_NAME);
        platformThread.start();

        Thread virtualThread = Thread.ofVirtual()
                .name(VIRTUAL_THREAD_NAME)
                .start(TestHumongousAllocationStackTrace::allocateFromVirtualThread);

        ALLOCATION_READY.await();
        START_ALLOCATION.countDown();
        platformThread.join();
        virtualThread.join();

        allocateConcurrently();
    }

    private static void allocateFromPlatformThread() {
        awaitStart();
        sink = new byte[HUMONGOUS_OBJECT_SIZE];
    }

    private static void allocateFromVirtualThread() {
        awaitStart();
        sink = new byte[HUMONGOUS_OBJECT_SIZE];
    }

    private static void allocateConcurrently() throws Exception {
        concurrentSink = new Object[CONCURRENT_THREAD_COUNT][CONCURRENT_ALLOCATION_COUNT];
        CountDownLatch ready = new CountDownLatch(CONCURRENT_THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[CONCURRENT_THREAD_COUNT];

        for (int i = 0; i < threads.length; i++) {
            final int index = i;
            threads[i] = new Thread(() -> allocateFromConcurrentThread(index, ready, start),
                    CONCURRENT_THREAD_PREFIX + index);
            threads[i].start();
        }

        ready.await();
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
    }

    private static void allocateFromConcurrentThread(int threadIndex,
                                                     CountDownLatch ready,
                                                     CountDownLatch start) {
        ready.countDown();
        await(start);
        for (int i = 0; i < CONCURRENT_ALLOCATION_COUNT; i++) {
            concurrentSink[threadIndex][i] = new byte[HUMONGOUS_OBJECT_SIZE];
        }
    }

    private static void awaitStart() {
        ALLOCATION_READY.countDown();
        await(START_ALLOCATION);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    private static void failHumongousAllocation() {
        try {
            sink = new byte[FAILING_HUMONGOUS_OBJECT_SIZE];
            throw new AssertionError("Allocation unexpectedly succeeded");
        } catch (OutOfMemoryError expected) {
            System.out.println("Caught expected OutOfMemoryError");
        }
    }

    private static void allocateAfterGc() throws Exception {
        Thread allocator = new Thread(() -> {
            Object[] objects = new Object[6];
            for (int i = 0; i < objects.length; i++) {
                objects[i] = new byte[HUMONGOUS_OBJECT_SIZE];
            }

            objects[0] = null;
            sink = objects;
            objects[0] = new byte[HUMONGOUS_OBJECT_SIZE];
        }, GC_THREAD_NAME);
        allocator.start();
        allocator.join();
    }

    private record LogBlock(String header, List<String> stackLines) { }

    private static List<LogBlock> parseLogBlocks(String output) {
        // Each successful allocation should be emitted as one non-interleaving
        // log block: a header line followed by the Java stack lines.
        List<LogBlock> blocks = new ArrayList<>();
        String currentHeader = null;
        List<String> currentStackLines = new ArrayList<>();

        for (String line : output.lines().toList()) {
            if (line.contains(LOG_PREFIX)) {
                if (currentHeader != null) {
                    blocks.add(new LogBlock(currentHeader, currentStackLines));
                }
                currentHeader = line;
                currentStackLines = new ArrayList<>();
            } else if (currentHeader != null) {
                currentStackLines.add(line);
            }
        }

        if (currentHeader != null) {
            blocks.add(new LogBlock(currentHeader, currentStackLines));
        }

        return blocks;
    }

    private static void assertExactLogCount(List<LogBlock> blocks, String threadName,
                                            String expectedFrame, int expectedCount) {
        int actualCount = countMatchingLogs(blocks, threadName, expectedFrame);
        Asserts.assertEQ(actualCount, expectedCount,
                "Unexpected humongous allocation log count for thread " + threadName);
    }

    private static void assertAtLeastOneLog(List<LogBlock> blocks, String threadName,
                                            String expectedFrame) {
        int actualCount = countMatchingLogs(blocks, threadName, expectedFrame);
        Asserts.assertGreaterThan(actualCount, 0,
                "Missing humongous allocation log for thread " + threadName);
    }

    private static int countMatchingLogs(List<LogBlock> blocks, String threadName, String expectedFrame) {
        String threadMarker = "Java thread \"" + threadName + "\"";
        int actualCount = 0;

        for (LogBlock block : blocks) {
            if (!block.header().contains(threadMarker)) {
                continue;
            }

            assertLogBlock(block, threadName, expectedFrame);
            actualCount += 1;
        }

        return actualCount;
    }

    private static void assertLogBlock(LogBlock block, String threadName, String expectedFrame) {
        Asserts.assertTrue(block.header().contains("object "),
                "Missing object address for thread " + threadName);
        Asserts.assertTrue(block.header().contains("size "),
                "Missing object size for thread " + threadName);
        Asserts.assertTrue(block.header().contains("regions "),
                "Missing region count for thread " + threadName);
        Asserts.assertTrue(block.stackLines().stream().anyMatch(line -> line.contains(STACK_HEADER)),
                "Missing stack header for thread " + threadName);
        Asserts.assertTrue(block.stackLines().stream().anyMatch(line -> line.contains(expectedFrame)),
                "Missing stack frame " + expectedFrame + " for thread " + threadName);
    }
}
