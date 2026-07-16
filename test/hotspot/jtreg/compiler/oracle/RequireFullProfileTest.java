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
 * @summary Checks that CompileCommand=requirefullprofile is parsed, prevents level 2 compilation,
 *          keeps non-trivial Java methods on the full-profile path to level 4 when available,
 *          and preserves the simple path for trivial methods that cannot create MDOs.
 * @requires vm.compiler1.enabled & vm.compiler2.enabled
 * @requires vm.compMode != "Xint"
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox compiler.whitebox.CompilerWhiteBoxTest
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *                   -XX:+TieredCompilation -XX:TieredStopAtLevel=4 -XX:-BackgroundCompilation
 *                   -XX:CompileThresholdScaling=0.01
 *                   -XX:CompileCommand=requirefullprofile,compiler.oracle.RequireFullProfileTest::test
 *                   compiler.oracle.RequireFullProfileTest
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *                   -XX:+TieredCompilation -XX:TieredStopAtLevel=4 -XX:-BackgroundCompilation
 *                   -XX:CompileThresholdScaling=0.01
 *                   -XX:CompileCommand=dontinline,compiler.oracle.RequireFullProfileTest::fieldReader
 *                   -XX:CompileCommand=requirefullprofile,compiler.oracle.RequireFullProfileTest::fieldReader
 *                   compiler.oracle.RequireFullProfileTest fieldReader
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *                   -XX:+TieredCompilation -XX:TieredStopAtLevel=4 -XX:-BackgroundCompilation
 *                   -XX:CompileThresholdScaling=0.01
 *                   -XX:CompileCommand=dontinline,compiler.oracle.RequireFullProfileTest$Accessor::getValue
 *                   -XX:CompileCommand=requirefullprofile,compiler.oracle.RequireFullProfileTest$Accessor::getValue
 *                   compiler.oracle.RequireFullProfileTest accessor
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *                   -XX:+TieredCompilation -XX:TieredStopAtLevel=4 -XX:-BackgroundCompilation
 *                   -XX:CompileThresholdScaling=0.01
 *                   -XX:CompileCommand=dontinline,compiler.oracle.RequireFullProfileTest::constantGetter
 *                   -XX:CompileCommand=requirefullprofile,compiler.oracle.RequireFullProfileTest::constantGetter
 *                   compiler.oracle.RequireFullProfileTest constant
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *                   -XX:+TieredCompilation -XX:TieredStopAtLevel=4 -XX:-BackgroundCompilation
 *                   -XX:CompileThresholdScaling=0.01
 *                   -XX:CompileCommand=option,compiler.oracle.RequireFullProfileTest::test,requirefullprofile
 *                   compiler.oracle.RequireFullProfileTest
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *                   -XX:+TieredCompilation -XX:TieredStopAtLevel=4 -XX:-BackgroundCompilation
 *                   -XX:CompileCommand=requirefullprofile,compiler.oracle.RequireFullProfileTest::osrTest
 *                   compiler.oracle.RequireFullProfileTest osr
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *                   -XX:+TieredCompilation -XX:TieredStopAtLevel=2 -XX:-BackgroundCompilation
 *                   -XX:CompileCommand=requirefullprofile,compiler.oracle.RequireFullProfileTest::test
 *                   compiler.oracle.RequireFullProfileTest stopAt2
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *                   -XX:+TieredCompilation -XX:TieredStopAtLevel=2 -XX:-BackgroundCompilation
 *                   -XX:CompileThresholdScaling=0.01
 *                   -XX:CompileCommand=requirefullprofile,compiler.oracle.RequireFullProfileTest::test
 *                   compiler.oracle.RequireFullProfileTest stopAt2Natural
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *                   -XX:+TieredCompilation -XX:TieredStopAtLevel=3 -XX:-BackgroundCompilation
 *                   -XX:CompileCommand=requirefullprofile,compiler.oracle.RequireFullProfileTest::test
 *                   compiler.oracle.RequireFullProfileTest stopAt3
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *                   -XX:+TieredCompilation -XX:TieredStopAtLevel=4 -XX:+BackgroundCompilation
 *                   -XX:CompileCommand=requirefullprofile,compiler.oracle.RequireFullProfileTest::test
 *                   compiler.oracle.RequireFullProfileTest queueDowngrade
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *                   -XX:+TieredCompilation -XX:TieredStopAtLevel=3 -XX:-BackgroundCompilation
 *                   -XX:+CompilerDirectivesIgnoreCompileCommands
 *                   -XX:CompileCommand=requirefullprofile,compiler.oracle.RequireFullProfileTest::test
 *                   compiler.oracle.RequireFullProfileTest ignoreCommands
 */

package compiler.oracle;

import compiler.whitebox.CompilerWhiteBoxTest;
import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

import java.lang.reflect.Method;

public class RequireFullProfileTest {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();
    private static final Accessor ACCESSOR = new Accessor();
    private static int field = 42;
    private static volatile int sink;

    public static int test(int value) {
        int result = value;
        for (int i = 0; i < 16; i++) {
            result = (result * 31) + i;
        }
        return result;
    }

    public static int fieldReader() {
        return field;
    }

    public static int constantGetter() {
        return 7;
    }

    public static int osrTest(int limit) {
        int result = 0;
        for (int i = 0; i < limit; i++) {
            result = (result * 17) ^ i;
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "";
        Method method = switch (mode) {
            case "fieldReader" -> RequireFullProfileTest.class.getMethod("fieldReader");
            case "accessor" -> Accessor.class.getDeclaredMethod("getValue");
            case "constant" -> RequireFullProfileTest.class.getMethod("constantGetter");
            case "osr" -> RequireFullProfileTest.class.getMethod("osrTest", int.class);
            default -> RequireFullProfileTest.class.getMethod("test", int.class);
        };

        Asserts.assertEQ(Boolean.TRUE, WB.getMethodBooleanOption(method, "requirefullprofile"),
                "requirefullprofile should be available as a boolean method option");

        if (mode.equals("stopAt2")) {
            boolean enqueued = WB.enqueueMethodForCompilation(method, CompilerWhiteBoxTest.COMP_LEVEL_LIMITED_PROFILE);
            Asserts.assertFalse(enqueued,
                    "Level 2 compilation should be rejected when requirefullprofile cannot promote to level 3");
            Asserts.assertNE(CompilerWhiteBoxTest.COMP_LEVEL_LIMITED_PROFILE, WB.getMethodCompilationLevel(method),
                    "requirefullprofile should not allow level 2 compilation");
            return;
        }

        if (mode.equals("stopAt2Natural")) {
            assertNaturalStopAt2DoesNotCompileAtLevel2(method);
            return;
        }

        if (mode.equals("stopAt3")) {
            boolean enqueued = WB.enqueueMethodForCompilation(method, CompilerWhiteBoxTest.COMP_LEVEL_LIMITED_PROFILE);
            Asserts.assertTrue(enqueued,
                    "Level 2 compilation request should be promoted when level 3 is available");
            Asserts.assertEQ(CompilerWhiteBoxTest.COMP_LEVEL_FULL_PROFILE, WB.getMethodCompilationLevel(method),
                    "requirefullprofile should promote a level 2 compilation request to level 3");
            return;
        }

        if (mode.equals("osr")) {
            assertOsrRequestUsesFullProfilePath(method);
            return;
        }

        if (mode.equals("queueDowngrade")) {
            WB.clearMethodState(method);
            WB.markMethodProfiled(method);
            WB.lockCompilation();
            try {
                boolean enqueued = WB.enqueueMethodForCompilation(method, CompilerWhiteBoxTest.COMP_LEVEL_FULL_PROFILE);
                Asserts.assertTrue(enqueued, "Level 3 compilation should be enqueued");
                Asserts.assertTrue(WB.isMethodQueuedForCompilation(method), "Method should be queued while compilation is locked");
            } finally {
                WB.unlockCompilation();
            }
            waitForCompilation(method);
            Asserts.assertEQ(CompilerWhiteBoxTest.COMP_LEVEL_FULL_PROFILE, WB.getMethodCompilationLevel(method),
                    "Queued level 3 compilation must not be downgraded to level 2");
            return;
        }

        if (mode.equals("ignoreCommands")) {
            boolean enqueued = WB.enqueueMethodForCompilation(method, CompilerWhiteBoxTest.COMP_LEVEL_LIMITED_PROFILE);
            Asserts.assertTrue(enqueued,
                    "Level 2 compilation should be accepted when CompileCommands are ignored");
            Asserts.assertEQ(CompilerWhiteBoxTest.COMP_LEVEL_LIMITED_PROFILE, WB.getMethodCompilationLevel(method),
                    "requirefullprofile should not take effect when CompileCommands are ignored");
            return;
        }

        if (mode.equals("accessor") || mode.equals("constant")) {
            assertTrivialMethodUsesSimplePath(method, mode);
            return;
        }

        if (mode.equals("fieldReader")) {
            assertFieldReaderUsesFullProfilePath(method);
            return;
        }

        for (int i = 0; i < 1000; i++) {
            test(i);
        }

        boolean enqueued = WB.enqueueMethodForCompilation(method, CompilerWhiteBoxTest.COMP_LEVEL_LIMITED_PROFILE);
        Asserts.assertTrue(enqueued, "Method should be accepted for compilation");
        Asserts.assertEQ(CompilerWhiteBoxTest.COMP_LEVEL_FULL_PROFILE, WB.getMethodCompilationLevel(method),
                "requirefullprofile should promote a level 2 compilation request to level 3");

        int level = WB.getMethodCompilationLevel(method);
        for (int i = 0; i < 200_000 && level != CompilerWhiteBoxTest.COMP_LEVEL_FULL_OPTIMIZATION; i++) {
            test(i);
            level = WB.getMethodCompilationLevel(method);
            Asserts.assertNE(CompilerWhiteBoxTest.COMP_LEVEL_LIMITED_PROFILE, level,
                    "requirefullprofile should not allow level 2 compilation");
        }
        Asserts.assertEQ(CompilerWhiteBoxTest.COMP_LEVEL_FULL_OPTIMIZATION, level,
                "requirefullprofile method should eventually compile at level 4");
    }

    private static void assertNaturalStopAt2DoesNotCompileAtLevel2(Method method) {
        WB.clearMethodState(method);
        int level = WB.getMethodCompilationLevel(method);
        for (int i = 0; i < 200_000; i++) {
            test(i);
            level = WB.getMethodCompilationLevel(method);
            Asserts.assertNE(CompilerWhiteBoxTest.COMP_LEVEL_LIMITED_PROFILE, level,
                    "requirefullprofile should not allow natural compilation to be clipped to level 2");
        }
    }

    private static void assertFieldReaderUsesFullProfilePath(Method method) {
        int level = WB.getMethodCompilationLevel(method);
        for (int i = 0; i < 200_000 && level != CompilerWhiteBoxTest.COMP_LEVEL_FULL_OPTIMIZATION; i++) {
            sink = fieldReader();
            level = WB.getMethodCompilationLevel(method);
            if (level != CompilerWhiteBoxTest.COMP_LEVEL_NONE) {
                Asserts.assertNE(CompilerWhiteBoxTest.COMP_LEVEL_SIMPLE, level,
                        "requirefullprofile should keep non-trivial Java methods off the level 1 shortcut");
                Asserts.assertNE(CompilerWhiteBoxTest.COMP_LEVEL_LIMITED_PROFILE, level,
                        "requirefullprofile should not allow level 2 compilation");
            }
        }
        Asserts.assertEQ(CompilerWhiteBoxTest.COMP_LEVEL_FULL_OPTIMIZATION, level,
                "requirefullprofile non-trivial Java method should eventually compile at level 4");
    }

    private static void assertTrivialMethodUsesSimplePath(Method method, String mode) {
        int level = WB.getMethodCompilationLevel(method);
        for (int i = 0; i < 200_000 && level != CompilerWhiteBoxTest.COMP_LEVEL_SIMPLE; i++) {
            sink = switch (mode) {
                case "accessor" -> ACCESSOR.getValue();
                case "constant" -> constantGetter();
                default -> throw new IllegalArgumentException(mode);
            };
            level = WB.getMethodCompilationLevel(method);
            Asserts.assertNE(CompilerWhiteBoxTest.COMP_LEVEL_LIMITED_PROFILE, level,
                    "requirefullprofile should not allow level 2 compilation");
        }
        Asserts.assertEQ(CompilerWhiteBoxTest.COMP_LEVEL_SIMPLE, level,
                "requirefullprofile accessor and constant getter methods should use the level 1 trivial path");
    }

    private static void assertOsrRequestUsesFullProfilePath(Method method) {
        WB.markMethodProfiled(method);
        boolean enqueued = WB.enqueueMethodForCompilation(method, CompilerWhiteBoxTest.COMP_LEVEL_LIMITED_PROFILE, 0);
        Asserts.assertTrue(enqueued,
                "OSR level 2 compilation request should be promoted when level 3 is available");

        int level = WB.getMethodCompilationLevel(method, true);
        Asserts.assertEQ(CompilerWhiteBoxTest.COMP_LEVEL_FULL_PROFILE, level,
                "requirefullprofile should promote an OSR level 2 compilation request to level 3");
        Asserts.assertNE(CompilerWhiteBoxTest.COMP_LEVEL_LIMITED_PROFILE, level,
                "requirefullprofile should not allow OSR level 2 compilation");
    }

    private static void waitForCompilation(Method method) throws InterruptedException {
        for (int i = 0; i < 10_000 && WB.isMethodQueuedForCompilation(method); i++) {
            Thread.sleep(1);
        }
        Asserts.assertFalse(WB.isMethodQueuedForCompilation(method), "Method should leave the compile queue");
        Asserts.assertTrue(WB.isMethodCompiled(method), "Method should be compiled");
    }

    private static class Accessor {
        private int value = 42;

        public int getValue() {
            return value;
        }
    }
}
