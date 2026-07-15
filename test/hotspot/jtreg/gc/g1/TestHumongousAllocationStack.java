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
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package gc.g1;

/*
 * @test TestHumongousAllocationStack
 * @summary Check that successful G1 humongous allocations log the allocating
 *          Java thread and call stack at gc+humongous=debug.
 * @requires vm.gc.G1
 * @requires vm.continuations
 * @library /test/lib
 * @run driver gc.g1.TestHumongousAllocationStack
 */

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

import java.util.List;
import java.util.concurrent.CountDownLatch;

public class TestHumongousAllocationStack {
    private static final String ALLOCATOR_THREAD_NAME = "humongous-allocator";
    private static final String VIRTUAL_ALLOCATOR_THREAD_NAME = "humongous-virtual-allocator";
    private static final String ALLOCATION_LOG_PREFIX =
        "Successful humongous allocation by Java thread ";
    private static final String STACK_HEADER = "Java call stack:";
    private static final int DIRECT_ALLOCATION_COUNT = 3;
    private static final int VIRTUAL_ALLOCATION_COUNT = 2;
    private static final String CONCURRENT_ALLOCATOR_THREAD_PREFIX =
        "humongous-concurrent-allocator-";
    private static final int CONCURRENT_ALLOCATOR_THREAD_COUNT = 8;
    private static final int CONCURRENT_ALLOCATION_COUNT = 3;

    public static void main(String[] args) throws Exception {
        testDebugLogging();
        testVirtualThreadDebugLogging();
        testConcurrentDebugLogging();
        testInfoLogging();
        testGcThenSuccessfulAllocation();
    }

    private static void testDebugLogging() throws Exception {
        OutputAnalyzer output = ProcessTools.executeLimitedTestJava(
            "-XX:+UseG1GC",
            "-Xms32m",
            "-Xmx32m",
            "-XX:G1HeapRegionSize=1M",
            "-Xlog:gc+humongous=debug",
            DirectAllocator.class.getName());

        output.shouldHaveExitValue(0);
        assertAllocationLogs(output,
                             ALLOCATOR_THREAD_NAME,
                             "gc.g1.TestHumongousAllocationStack$DirectAllocator.allocateHumongousObject",
                             DIRECT_ALLOCATION_COUNT);
    }

    private static void testVirtualThreadDebugLogging() throws Exception {
        OutputAnalyzer output = ProcessTools.executeLimitedTestJava(
            "-XX:+UseG1GC",
            "-Xms32m",
            "-Xmx32m",
            "-XX:G1HeapRegionSize=1M",
            "-Xlog:gc+humongous=debug",
            VirtualThreadAllocator.class.getName());

        output.shouldHaveExitValue(0);
        assertAllocationLogs(output,
                             VIRTUAL_ALLOCATOR_THREAD_NAME,
                             "gc.g1.TestHumongousAllocationStack$VirtualThreadAllocator.allocateHumongousObject",
                             VIRTUAL_ALLOCATION_COUNT);
    }

    private static void testConcurrentDebugLogging() throws Exception {
        OutputAnalyzer output = ProcessTools.executeLimitedTestJava(
            "-XX:+UseG1GC",
            "-Xms64m",
            "-Xmx64m",
            "-XX:G1HeapRegionSize=1M",
            "-Xlog:gc+humongous=debug",
            ConcurrentAllocator.class.getName());

        output.shouldHaveExitValue(0);
        for (int i = 0; i < CONCURRENT_ALLOCATOR_THREAD_COUNT; i++) {
            assertAllocationLogs(output,
                                 CONCURRENT_ALLOCATOR_THREAD_PREFIX + i,
                                 "gc.g1.TestHumongousAllocationStack$ConcurrentAllocator.allocateHumongousObject",
                                 CONCURRENT_ALLOCATION_COUNT);
        }
    }

    private static void testInfoLogging() throws Exception {
        OutputAnalyzer output = ProcessTools.executeLimitedTestJava(
            "-XX:+UseG1GC",
            "-Xms32m",
            "-Xmx32m",
            "-XX:G1HeapRegionSize=1M",
            "-Xlog:gc+humongous=info",
            DirectAllocator.class.getName());

        output.shouldHaveExitValue(0);
        output.shouldNotContain(ALLOCATION_LOG_PREFIX);
    }

    private static void testGcThenSuccessfulAllocation() throws Exception {
        OutputAnalyzer output = ProcessTools.executeLimitedTestJava(
            "-XX:+UseG1GC",
            "-Xms8m",
            "-Xmx8m",
            "-XX:G1HeapRegionSize=1M",
            "-Xlog:gc,gc+alloc=trace,gc+humongous=debug",
            GcThenSuccessfulAllocator.class.getName());

        output.shouldHaveExitValue(0);
        output.shouldContain("Pause Young (Concurrent Start) (G1 Humongous Allocation)");
        output.shouldContain("Successfully scheduled collection returning");
        assertAllocationLogs(output,
                             ALLOCATOR_THREAD_NAME,
                             "gc.g1.TestHumongousAllocationStack$GcThenSuccessfulAllocator.allocateAfterGc",
                             GcThenSuccessfulAllocator.SUCCESSFUL_ALLOCATION_COUNT);
    }

    private static void assertAllocationLogs(OutputAnalyzer output,
                                             String threadName,
                                             String expectedFrame,
                                             int expectedCount) {
        List<String> lines = output.asLines();
        String allocationLog = threadName == null
            ? ALLOCATION_LOG_PREFIX
            : ALLOCATION_LOG_PREFIX + "\"" + threadName + "\"";
        int actual = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).contains(allocationLog)) {
                continue;
            }

            int blockEnd = i + 1;
            while (blockEnd < lines.size() &&
                   !lines.get(blockEnd).contains(ALLOCATION_LOG_PREFIX)) {
                blockEnd++;
            }

            boolean hasStackHeader = false;
            boolean hasExpectedFrame = false;
            for (int j = i + 1; j < blockEnd; j++) {
                hasStackHeader |= lines.get(j).contains(STACK_HEADER);
                hasExpectedFrame |= lines.get(j).contains(expectedFrame);
            }
            if (!hasStackHeader || !hasExpectedFrame) {
                throw new RuntimeException("Missing stack details for allocation log \"" +
                                           allocationLog + "\"\nOutput:\n" + output.getOutput());
            }
            actual++;
        }

        if (actual != expectedCount) {
            throw new RuntimeException("Expected " + expectedCount + " allocation logs for \"" + allocationLog +
                                       "\", found " + actual + "\nOutput:\n" + output.getOutput());
        }
    }

    static class DirectAllocator {
        private static final Object[] sink = new Object[DIRECT_ALLOCATION_COUNT];

        public static void main(String[] args) throws Exception {
            Thread allocator = new Thread(DirectAllocator::allocateHumongousObjects,
                                          ALLOCATOR_THREAD_NAME);
            allocator.start();
            allocator.join();
        }

        private static void allocateHumongousObjects() {
            for (int i = 0; i < DIRECT_ALLOCATION_COUNT; i++) {
                sink[i] = allocateHumongousObject();
            }
        }

        private static Object allocateHumongousObject() {
            return new byte[768 * 1024];
        }
    }

    static class VirtualThreadAllocator {
        private static final Object[] sink = new Object[VIRTUAL_ALLOCATION_COUNT];

        public static void main(String[] args) throws Exception {
            Thread allocator = Thread.ofVirtual()
                                     .name(VIRTUAL_ALLOCATOR_THREAD_NAME)
                                     .start(VirtualThreadAllocator::allocateHumongousObjects);
            allocator.join();
        }

        private static void allocateHumongousObjects() {
            for (int i = 0; i < VIRTUAL_ALLOCATION_COUNT; i++) {
                sink[i] = allocateHumongousObject();
            }
        }

        private static Object allocateHumongousObject() {
            return new byte[768 * 1024];
        }
    }

    static class ConcurrentAllocator {
        private static final Object[][] sink =
            new Object[CONCURRENT_ALLOCATOR_THREAD_COUNT][CONCURRENT_ALLOCATION_COUNT];

        public static void main(String[] args) throws Exception {
            CountDownLatch ready = new CountDownLatch(CONCURRENT_ALLOCATOR_THREAD_COUNT);
            CountDownLatch start = new CountDownLatch(1);
            Thread[] allocators = new Thread[CONCURRENT_ALLOCATOR_THREAD_COUNT];

            for (int i = 0; i < allocators.length; i++) {
                final int index = i;
                allocators[i] = new Thread(() -> allocateHumongousObjects(index, ready, start),
                                           CONCURRENT_ALLOCATOR_THREAD_PREFIX + index);
                allocators[i].start();
            }

            ready.await();
            start.countDown();
            for (Thread allocator : allocators) {
                allocator.join();
            }
        }

        private static void allocateHumongousObjects(int threadIndex,
                                                     CountDownLatch ready,
                                                     CountDownLatch start) {
            ready.countDown();
            await(start);
            for (int i = 0; i < CONCURRENT_ALLOCATION_COUNT; i++) {
                sink[threadIndex][i] = allocateHumongousObject();
            }
        }

        private static void await(CountDownLatch latch) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        private static Object allocateHumongousObject() {
            return new byte[768 * 1024];
        }
    }

    static class GcThenSuccessfulAllocator {
        private static final int SUCCESSFUL_ALLOCATION_COUNT = 7;
        private static volatile Object[] sink;

        public static void main(String[] args) throws Exception {
            Thread allocator = new Thread(GcThenSuccessfulAllocator::allocateAfterGc,
                                          ALLOCATOR_THREAD_NAME);
            allocator.start();
            allocator.join();
        }

        private static void allocateAfterGc() {
            Object[] objects = new Object[6];
            for (int i = 0; i < objects.length; i++) {
                objects[i] = new byte[768 * 1024];
            }

            objects[0] = null;
            sink = objects;
            sink[0] = new byte[768 * 1024];
        }
    }
}
