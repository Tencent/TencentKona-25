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
 * @test TestHumongousAllocationStackLog
 * @summary Ensure successful humongous object allocations log thread and Java stack at gc+humongous=debug.
 * @requires vm.gc.G1
 * @library /test/lib
 * @modules java.base/jdk.internal.misc
 *          java.management
 * @run driver gc.g1.TestHumongousAllocationStackLog
 */

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

import java.util.concurrent.CountDownLatch;

public class TestHumongousAllocationStackLog {
    private static final String LOG_LINE_PREFIX = "Humongous object allocation (object size";
    private static final String STACK_FRAME_SUBSTRING =
        "gc.g1.TestHumongousAllocationStackLog$HumongousAllocator.allocateHumongous";
    private static final String VIRTUAL_THREAD_NAME = "virtual-humongous-allocator";
    private static final String CONCURRENT_THREAD_NAME_PREFIX = "humongous-allocator-";
    private static final int CONCURRENT_THREAD_COUNT = 8;

    public static void main(String[] args) throws Exception {
        OutputAnalyzer debugOutput = runWithLogLevel("debug");
        debugOutput.shouldHaveExitValue(0);
        debugOutput.shouldContainMultiLinePattern("thread main", STACK_FRAME_SUBSTRING);
        debugOutput.shouldContainMultiLinePattern("thread " + VIRTUAL_THREAD_NAME,
                                                  STACK_FRAME_SUBSTRING);
        for (int i = 0; i < CONCURRENT_THREAD_COUNT; i++) {
            debugOutput.shouldContainMultiLinePattern("thread " + CONCURRENT_THREAD_NAME_PREFIX + i,
                                                      STACK_FRAME_SUBSTRING);
        }

        OutputAnalyzer infoOutput = runWithLogLevel("info");
        infoOutput.shouldHaveExitValue(0);
        infoOutput.shouldNotContain(LOG_LINE_PREFIX);
    }

    private static OutputAnalyzer runWithLogLevel(String level) throws Exception {
        return ProcessTools.executeLimitedTestJava(
            "-XX:+UseG1GC",
            "-XX:G1HeapRegionSize=1M",
            "-Xms64M",
            "-Xmx64M",
            "-Xlog:gc+humongous=" + level,
            HumongousAllocator.class.getName());
    }

    static class HumongousAllocator {
        private static volatile Object sink;

        private static void allocateHumongous() {
            sink = new byte[700 * 1024];
        }

        private static void allocateConcurrently() throws Exception {
            CountDownLatch ready = new CountDownLatch(CONCURRENT_THREAD_COUNT);
            CountDownLatch start = new CountDownLatch(1);
            Thread[] threads = new Thread[CONCURRENT_THREAD_COUNT];

            for (int i = 0; i < threads.length; i++) {
                final int index = i;
                threads[i] = Thread.ofPlatform()
                    .name(CONCURRENT_THREAD_NAME_PREFIX + index)
                    .start(() -> {
                        ready.countDown();
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        allocateHumongous();
                    });
            }

            ready.await();
            start.countDown();
            for (Thread thread : threads) {
                thread.join();
            }
        }

        public static void main(String[] args) throws Exception {
            allocateHumongous();
            Thread.ofVirtual().name(VIRTUAL_THREAD_NAME).start(HumongousAllocator::allocateHumongous).join();
            allocateConcurrently();
            System.out.println(sink);
        }
    }
}
