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
 * @test TestHumongousAllocationSourceLog
 * @summary G1 humongous allocation logs include the allocating Java thread name and active stack.
 * @requires vm.gc.G1
 * @requires vm.continuations
 * @library /test/lib
 * @modules java.base/jdk.internal.misc
 *          java.management
 * @run driver gc.g1.TestHumongousAllocationSourceLog
 */

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestHumongousAllocationSourceLog {
    private static final String LOG_HEADER = "Humongous object allocation succeeded:";
    private static final String CLASS_NAME = Pattern.quote(
            TestHumongousAllocationSourceLog.HumongousAllocationApp.class.getName());

    public static void main(String[] args) throws Exception {
        OutputAnalyzer output = ProcessTools.executeLimitedTestJava(
                "-XX:+UseG1GC",
                "-Xms128M",
                "-Xmx128M",
                "-XX:G1HeapRegionSize=1M",
                "-XX:MaxJavaStackTraceDepth=64",
                "-Xlog:gc+humongous=debug",
                HumongousAllocationApp.class.getName());

        output.shouldHaveExitValue(0);
        String stdout = output.getStdout();
        System.out.println(stdout);

        verifyBlock(stdout, "humongous-main-platform", "mainPlatformAllocate",
                HumongousAllocationApp.HUMONGOUS_SIZE);
        verifyBlock(stdout, "humongous-platform-0", "platformAllocate0",
                HumongousAllocationApp.HUMONGOUS_SIZE + 1);
        verifyBlock(stdout, "humongous-platform-1", "platformAllocate1",
                HumongousAllocationApp.HUMONGOUS_SIZE + 2);
        verifyBlock(stdout, "humongous-virtual-0", "virtualAllocate0",
                HumongousAllocationApp.HUMONGOUS_SIZE + 3);
        verifyBlock(stdout, "humongous-virtual-1", "virtualAllocate1",
                HumongousAllocationApp.HUMONGOUS_SIZE + 4);
    }

    private static void verifyBlock(String output, String threadName, String methodName, int requestedSize) {
        Matcher matcher = Pattern.compile(LOG_HEADER
                + " thread=\"" + Pattern.quote(threadName) + "\" size=(\\d+)B regions=(\\d+) address=(0x[0-9a-fA-F]+)"
                + "(?:(?!" + LOG_HEADER + ").)*"
                + "Java stack for humongous allocation:"
                + "(?:(?!" + LOG_HEADER + ").)*"
                + "at " + CLASS_NAME + "\\." + Pattern.quote(methodName) + "\\(",
                Pattern.DOTALL).matcher(output);

        if (!matcher.find()) {
            throw new AssertionError("Missing humongous allocation source log block for thread "
                    + threadName + " and method " + methodName + "\nOutput:\n" + output);
        }

        long size = Long.parseLong(matcher.group(1));
        long regions = Long.parseLong(matcher.group(2));
        String address = matcher.group(3);

        if (size <= 0 || size < requestedSize) {
            throw new AssertionError("Unexpected allocation size " + size + " for thread "
                    + threadName + ", requested " + requestedSize);
        }
        if (regions != 1) {
            throw new AssertionError("Unexpected humongous region count " + regions
                    + " for thread " + threadName);
        }
        if (address.matches("0x0+")) {
            throw new AssertionError("Unexpected null allocation address for thread " + threadName);
        }
    }

    static class HumongousAllocationApp {
        private static final int HUMONGOUS_SIZE = 768 * 1024;
        private static volatile Object[] keepAlive = new Object[5];

        public static void main(String[] args) throws Exception {
            Thread.currentThread().setName("humongous-main-platform");
            mainPlatformEntry();
            runPlatformThreads();
            runVirtualThreads();
            System.out.println("keepAlive=" + Arrays.toString(keepAlive));
        }

        private static void mainPlatformEntry() {
            mainPlatformAllocate();
        }

        private static void mainPlatformAllocate() {
            keepAlive[0] = new byte[HUMONGOUS_SIZE];
        }

        private static void runPlatformThreads() throws Exception {
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> failed = new AtomicReference<>();
            runConcurrent(new Thread[] {
                    Thread.ofPlatform().name("humongous-platform-0").unstarted(
                            () -> runAfterStart(start, failed, () -> platformEntry(0))),
                    Thread.ofPlatform().name("humongous-platform-1").unstarted(
                            () -> runAfterStart(start, failed, () -> platformEntry(1)))
            }, start, failed);
        }

        private static void runVirtualThreads() throws Exception {
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> failed = new AtomicReference<>();
            runConcurrent(new Thread[] {
                    Thread.ofVirtual().name("humongous-virtual-0").unstarted(
                            () -> runAfterStart(start, failed, () -> virtualEntry(0))),
                    Thread.ofVirtual().name("humongous-virtual-1").unstarted(
                            () -> runAfterStart(start, failed, () -> virtualEntry(1)))
            }, start, failed);
        }

        private static void runConcurrent(Thread[] threads, CountDownLatch start,
                                          AtomicReference<Throwable> failed) throws Exception {
            for (Thread thread : threads) {
                thread.start();
            }

            start.countDown();
            for (Thread thread : threads) {
                thread.join();
            }
            Throwable error = failed.get();
            if (error != null) {
                throw new AssertionError("Allocation thread failed", error);
            }
        }

        private static void runAfterStart(CountDownLatch start, AtomicReference<Throwable> failed, Runnable task) {
            try {
                start.await();
                task.run();
            } catch (Throwable t) {
                failed.compareAndSet(null, t);
            }
        }

        private static void platformEntry(int index) {
            if (index == 0) {
                platformAllocate0();
            } else {
                platformAllocate1();
            }
        }

        private static void platformAllocate0() {
            keepAlive[1] = new byte[HUMONGOUS_SIZE + 1];
        }

        private static void platformAllocate1() {
            keepAlive[2] = new byte[HUMONGOUS_SIZE + 2];
        }

        private static void virtualEntry(int index) {
            if (index == 0) {
                virtualAllocate0();
            } else {
                virtualAllocate1();
            }
        }

        private static void virtualAllocate0() {
            keepAlive[3] = new byte[HUMONGOUS_SIZE + 3];
        }

        private static void virtualAllocate1() {
            keepAlive[4] = new byte[HUMONGOUS_SIZE + 4];
        }
    }
}
