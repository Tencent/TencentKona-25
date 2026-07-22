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
 *          Java thread name and stack trace at debug level.
 * @requires vm.gc.G1
 * @requires vm.continuations
 * @requires vm.flagless
 * @library /test/lib
 * @modules java.base/jdk.internal.misc
 *          java.management
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run driver gc.g1.TestHumongousAllocationStackTrace
 */

import java.util.concurrent.CountDownLatch;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestHumongousAllocationStackTrace {
    private static final int HUMONGOUS_OBJECT_SIZE = 768 * 1024;
    private static final int FAILING_HUMONGOUS_OBJECT_SIZE = 64 * 1024 * 1024;
    private static final String LOG_PREFIX = "Humongous object allocation succeeded:";
    private static final String PLATFORM_THREAD_NAME = "humongous-platform";
    private static final String VIRTUAL_THREAD_NAME = "humongous-virtual";

    private static final CountDownLatch ALLOCATION_READY = new CountDownLatch(2);
    private static final CountDownLatch START_ALLOCATION = new CountDownLatch(1);
    private static volatile Object sink;

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            if (args[0].equals("allocate")) {
                allocateHumongousObjects();
            } else if (args[0].equals("fail")) {
                failHumongousAllocation();
            } else {
                throw new IllegalArgumentException("Unknown mode: " + args[0]);
            }
            return;
        }

        OutputAnalyzer debugOutput = runWithLogging("debug");
        debugOutput.shouldHaveExitValue(0);
        System.out.println(debugOutput.getStdout());

        String stdout = debugOutput.getStdout();
        Asserts.assertEQ(countOccurrences(stdout, LOG_PREFIX), 2,
                         "Expected one log entry per successful humongous allocation");
        assertAllocationLog(stdout, PLATFORM_THREAD_NAME,
                            "allocateFromPlatformThread", "allocateFromVirtualThread");
        assertAllocationLog(stdout, VIRTUAL_THREAD_NAME,
                            "allocateFromVirtualThread", "allocateFromPlatformThread");

        OutputAnalyzer infoOutput = runWithLogging("info");
        infoOutput.shouldHaveExitValue(0);
        infoOutput.shouldNotContain(LOG_PREFIX);

        OutputAnalyzer failureOutput = runFailingAllocation();
        failureOutput.shouldHaveExitValue(0);
        failureOutput.shouldContain("Caught expected OutOfMemoryError");
        failureOutput.shouldNotContain(LOG_PREFIX);
    }

    private static OutputAnalyzer runWithLogging(String level) throws Exception {
        return ProcessTools.executeLimitedTestJava(
                "-Xbootclasspath/a:.",
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+WhiteBoxAPI",
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

    private static void allocateHumongousObjects() throws Exception {
        allocateLargestNonHumongousObject();

        Thread platformThread = new Thread(
                TestHumongousAllocationStackTrace::allocateFromPlatformThread,
                PLATFORM_THREAD_NAME);
        platformThread.start();

        Thread virtualThread = Thread.ofVirtual()
                .name(VIRTUAL_THREAD_NAME)
                .start(TestHumongousAllocationStackTrace::allocateFromVirtualThread);

        // Release both allocators together to exercise concurrent multi-line logging.
        ALLOCATION_READY.await();
        START_ALLOCATION.countDown();
        platformThread.join();
        virtualThread.join();
    }

    private static void allocateLargestNonHumongousObject() {
        WhiteBox whiteBox = WhiteBox.getWhiteBox();
        // G1 classifies only objects larger than half a region as humongous.
        int threshold = whiteBox.g1RegionSize() / 2;
        int overhead = detectByteArrayAllocationOverhead(whiteBox);
        byte[] allocation = new byte[threshold - overhead];

        Asserts.assertEQ(whiteBox.getObjectSize(allocation), (long) threshold,
                         "Object size must be exactly the humongous threshold");
        Asserts.assertFalse(whiteBox.g1IsHumongous(allocation),
                            "An object at the threshold must not be humongous");
        sink = allocation;
    }

    private static int detectByteArrayAllocationOverhead(WhiteBox whiteBox) {
        // Infer the header and alignment overhead instead of assuming an object layout.
        long zeroLengthSize = whiteBox.getObjectSize(new byte[0]);
        for (int length = 1; length <= 256; length++) {
            long allocationSize = whiteBox.getObjectSize(new byte[length]);
            if (allocationSize != zeroLengthSize) {
                return (int) zeroLengthSize - (length - 1);
            }
        }
        throw new AssertionError("Unable to determine byte array allocation overhead");
    }

    private static void failHumongousAllocation() {
        try {
            // Failed allocations must not produce a successful-allocation log entry.
            sink = new byte[FAILING_HUMONGOUS_OBJECT_SIZE];
            throw new AssertionError("Allocation unexpectedly succeeded");
        } catch (OutOfMemoryError expected) {
            System.out.println("Caught expected OutOfMemoryError");
        }
    }

    private static void allocateFromPlatformThread() {
        awaitStart();
        sink = new byte[HUMONGOUS_OBJECT_SIZE];
    }

    private static void allocateFromVirtualThread() {
        awaitStart();
        sink = new byte[HUMONGOUS_OBJECT_SIZE];
    }

    private static void awaitStart() {
        ALLOCATION_READY.countDown();
        try {
            START_ALLOCATION.await();
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    private static void assertAllocationLog(String output,
                                            String threadName,
                                            String expectedFrame,
                                            String unexpectedFrame) {
        String threadMarker = "Java thread \"" + threadName + "\"";
        Asserts.assertEQ(countOccurrences(output, threadMarker), 1,
                         "Expected exactly one log entry for " + threadName);

        int entryStart = output.indexOf(threadMarker);
        int nextEntry = output.indexOf(LOG_PREFIX, entryStart + threadMarker.length());
        String entry = nextEntry < 0 ? output.substring(entryStart)
                                     : output.substring(entryStart, nextEntry);
        Asserts.assertTrue(entry.contains(
                TestHumongousAllocationStackTrace.class.getName() + "." + expectedFrame),
                "Missing allocating frame " + expectedFrame + " for " + threadName);
        Asserts.assertFalse(entry.contains(
                TestHumongousAllocationStackTrace.class.getName() + "." + unexpectedFrame),
                "Interleaved allocating frame " + unexpectedFrame + " in log for " + threadName);
    }

    private static int countOccurrences(String value, String target) {
        int count = 0;
        for (int index = 0; (index = value.indexOf(target, index)) >= 0;
             index += target.length()) {
            count++;
        }
        return count;
    }
}
