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

/*
 * @test
 * @summary Tests -XX:CompileCommand=requirefullprofile.
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 *          java.management
 * @requires vm.flavor == "server" & (vm.opt.TieredStopAtLevel == null | vm.opt.TieredStopAtLevel == 4) & vm.compMode != "Xcomp"
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *                   -XX:+TieredCompilation -XX:+BackgroundCompilation
 *                   -XX:CompileCommand=compileonly,compiler.oracle.TestRequireFullProfileCommand::*
 *                   -XX:CompileCommand=dontinline,compiler.oracle.TestRequireFullProfileCommand::*
 *                   -XX:CompileCommand=requirefullprofile,compiler.oracle.TestRequireFullProfileCommand::target
 *                   compiler.oracle.TestRequireFullProfileCommand
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *                   -XX:+TieredCompilation -XX:+BackgroundCompilation
 *                   -XX:CompileCommand=compileonly,compiler.oracle.TestRequireFullProfileCommand::*
 *                   -XX:CompileCommand=dontinline,compiler.oracle.TestRequireFullProfileCommand::*
 *                   -XX:CompileCommand=option,compiler.oracle.TestRequireFullProfileCommand::target,requirefullprofile
 *                   compiler.oracle.TestRequireFullProfileCommand
 */

package compiler.oracle;

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

import java.lang.reflect.Executable;

public class TestRequireFullProfileCommand {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();

    private static final String OPTION = "requirefullprofile";

    private static final int COMP_LEVEL_LIMITED_PROFILE = 2;
    private static final int COMP_LEVEL_FULL_PROFILE = 3;
    private static final int COMP_LEVEL_FULL_OPTIMIZATION = 4;
    private static final long COMPILATION_TIMEOUT_MILLIS = 10_000;

    public static void main(String[] args) throws Exception {
        Executable baseline = getMethod("baseline");
        Executable target = getMethod("target");
        Executable other = getMethod("other");

        verifyOption(target, other);
        verifyNoTier2Downgrade(baseline, target);
        verifyTier4Compilation(target);
    }

    private static void verifyOption(Executable target, Executable other) {
        Asserts.assertEQ(Boolean.TRUE, WB.getMethodBooleanOption(target, OPTION),
                "requirefullprofile should be set for target method");
        Asserts.assertNull(WB.getMethodBooleanOption(other, OPTION),
                "requirefullprofile should not be set for other method");
    }

    private static void verifyNoTier2Downgrade(Executable baseline, Executable target) throws InterruptedException {
        int baselineLevel = compileProfiledAtFullProfileRequest(baseline);
        int targetLevel = compileProfiledAtFullProfileRequest(target);

        System.out.println("baseline requested=3 actual=" + baselineLevel);
        System.out.println("requirefullprofile requested=3 actual=" + targetLevel);

        Asserts.assertEQ(COMP_LEVEL_LIMITED_PROFILE, baselineLevel,
                "baseline method should exercise the existing level 3 to level 2 downgrade");
        Asserts.assertEQ(COMP_LEVEL_FULL_PROFILE, targetLevel,
                "requirefullprofile method must not be downgraded to level 2");
    }

    private static int compileProfiledAtFullProfileRequest(Executable method) throws InterruptedException {
        WB.clearMethodState(method);
        WB.testSetDontInlineMethod(method, true);
        WB.markMethodProfiled(method);

        Asserts.assertTrue(WB.enqueueMethodForCompilation(method, COMP_LEVEL_FULL_PROFILE),
                "method should be enqueueable for full-profile compilation");
        waitCompiled(method, false);
        return WB.getMethodCompilationLevel(method);
    }

    private static void verifyTier4Compilation(Executable target) throws InterruptedException {
        WB.clearMethodState(target);
        WB.testSetDontInlineMethod(target, true);

        Asserts.assertTrue(WB.enqueueMethodForCompilation(target, COMP_LEVEL_FULL_PROFILE),
                "target should be enqueueable for full-profile compilation");
        waitCompiledAtLevel(target, COMP_LEVEL_FULL_PROFILE, false);

        for (int i = 0; i < 200_000; i++) {
            target();
        }

        int level = waitCompiledAtLevel(target, COMP_LEVEL_FULL_OPTIMIZATION, false);
        System.out.println("requirefullprofile after warmup actual=" + level);
        Asserts.assertEQ(COMP_LEVEL_FULL_OPTIMIZATION, level,
                "requirefullprofile method should reach level 4 after full profiling");
    }

    private static void waitCompiled(Executable method, boolean isOsr) throws InterruptedException {
        long deadline = System.currentTimeMillis() + COMPILATION_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (!WB.isMethodQueuedForCompilation(method) && WB.isMethodCompiled(method, isOsr)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new RuntimeException("Timed out waiting for compilation of " + method);
    }

    private static int waitCompiledAtLevel(Executable method, int level, boolean isOsr) throws InterruptedException {
        long deadline = System.currentTimeMillis() + COMPILATION_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            int current = WB.getMethodCompilationLevel(method, isOsr);
            if (!WB.isMethodQueuedForCompilation(method) && current == level) {
                return current;
            }
            Thread.sleep(10);
        }
        int current = WB.getMethodCompilationLevel(method, isOsr);
        throw new RuntimeException("Timed out waiting for compilation of " + method
                + " at level " + level + ", current level is " + current);
    }

    private static int baseline() {
        return 1;
    }

    private static int target() {
        return 2;
    }

    private static int other() {
        return 3;
    }

    private static Executable getMethod(String name) throws Exception {
        return TestRequireFullProfileCommand.class.getDeclaredMethod(name);
    }
}
