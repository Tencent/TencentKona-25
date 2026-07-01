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

public class TestHumongousAllocationStackLog {
    private static final String LOG_LINE_PREFIX = "Humongous object allocation (object size";
    private static final String STACK_FRAME_SUBSTRING =
        "gc.g1.TestHumongousAllocationStackLog$HumongousAllocator.allocateHumongous";

    public static void main(String[] args) throws Exception {
        OutputAnalyzer debugOutput = runWithLogLevel("debug");
        debugOutput.shouldHaveExitValue(0);
        debugOutput.shouldContain(LOG_LINE_PREFIX);
        debugOutput.shouldContain(STACK_FRAME_SUBSTRING);

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

        public static void main(String[] args) {
            for (int i = 0; i < 5; i++) {
                allocateHumongous();
            }
            System.out.println(sink);
        }
    }
}
