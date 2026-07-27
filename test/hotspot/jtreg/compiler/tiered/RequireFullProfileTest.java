/*
 * Copyright (c) 2025, 2026, Tencent and/or its affiliates. All rights reserved.
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

/**
 * @test RequireFullProfileTest
 * @summary Verify requirefullprofile keeps standard tiered paths out of level 2
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 *          java.management
 *
 * @comment the test can't be run w/ TieredStopAtLevel < 4
 * @requires vm.flavor == "server" & (vm.opt.TieredStopAtLevel == null | vm.opt.TieredStopAtLevel == 4) & vm.compMode != "Xcomp"
 *
 * @build jdk.test.whitebox.WhiteBox
 *        compiler.tiered.RequireFullProfileTest
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm/timeout=240 -Xmixed -Xbootclasspath/a:. -XX:+TieredCompilation
 *                   -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *                   -XX:-BackgroundCompilation
 *                   -XX:CompileCommand=compileonly,compiler.whitebox.SimpleTestCaseHelper::*
 *                   -XX:CompileCommand=compileonly,compiler.tiered.RequireFullProfileTest$CompileMethodHolder::*
 *                   -XX:CompileCommand=requirefullprofile,compiler.whitebox.SimpleTestCaseHelper::*
 *                   -XX:CompileCommand=requirefullprofile,compiler.tiered.RequireFullProfileTest$CompileMethodHolder::*
 *                   compiler.tiered.RequireFullProfileTest
 * @run main/othervm/timeout=240 -Xmixed -Xbootclasspath/a:. -XX:+TieredCompilation
 *                   -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *                   -XX:-BackgroundCompilation
 *                   -XX:CompileCommand=compileonly,compiler.whitebox.SimpleTestCaseHelper::*
 *                   -XX:CompileCommand=compileonly,compiler.tiered.RequireFullProfileTest$CompileMethodHolder::*
 *                   -XX:CompileCommand=option,compiler.whitebox.SimpleTestCaseHelper::*,requirefullprofile
 *                   -XX:CompileCommand=option,compiler.tiered.RequireFullProfileTest$CompileMethodHolder::*,requirefullprofile
 *                   compiler.tiered.RequireFullProfileTest
 */

package compiler.tiered;

import compiler.whitebox.CompilerWhiteBoxTest;
import compiler.whitebox.SimpleTestCase;
import jtreg.SkippedException;

import java.lang.reflect.Executable;
import java.util.concurrent.Callable;

public class RequireFullProfileTest extends CompLevelsTest {
    private static final String OPTION_NAME = "requirefullprofile";
    private static final long LEVEL_CHANGE_TIMEOUT_MS = 120_000L;

    private final Scenario scenario;

    private enum Scenario {
        QUEUE_DOWNGRADE,
        STANDARD_TRANSITION,
        EXPLICIT_LEVEL_REQUEST
    }

    private enum ExtraTestCase implements CompilerWhiteBoxTest.TestCase {
        NONTRIVIAL_METHOD_TEST("nonTrivialMethod");

        private final Executable executable;
        private final Callable<Integer> callable;

        ExtraTestCase(String methodName) {
            this.executable = MethodHelper.getMethod(CompileMethodHolder.class, methodName);
            this.callable = MethodHelper.getCallable(new CompileMethodHolder(), methodName);
        }

        @Override
        public Executable getExecutable() {
            return executable;
        }

        @Override
        public Callable<Integer> getCallable() {
            return callable;
        }

        @Override
        public boolean isOsr() {
            return false;
        }
    }

    private static class CompileMethodHolder {
        private final int iter = 1;

        public int nonTrivialMethod() {
            int acc = 0;
            for (int i = 0; i < iter; i++) {
                acc += i;
            }
            return acc;
        }
    }

    public static void main(String[] args) throws Throwable {
        if (CompilerWhiteBoxTest.skipOnTieredCompilation(false)) {
            throw new SkippedException("Test isn't applicable for non-tiered mode");
        }

        run(SimpleTestCase.METHOD_TEST, Scenario.QUEUE_DOWNGRADE);
        run(SimpleTestCase.OSR_STATIC_TEST, Scenario.QUEUE_DOWNGRADE);
        run(SimpleTestCase.OSR_STATIC_TEST, Scenario.STANDARD_TRANSITION);
        run(ExtraTestCase.NONTRIVIAL_METHOD_TEST, Scenario.STANDARD_TRANSITION);
        run(ExtraTestCase.NONTRIVIAL_METHOD_TEST, Scenario.EXPLICIT_LEVEL_REQUEST);
    }

    private static void run(CompilerWhiteBoxTest.TestCase testCase, Scenario scenario) {
        new RequireFullProfileTest(testCase, scenario).runTest();
    }

    protected RequireFullProfileTest(CompilerWhiteBoxTest.TestCase testCase, Scenario scenario) {
        super(testCase);
        this.scenario = scenario;
    }

    @Override
    protected void test() throws Exception {
        if (skipXcompOSR()) {
            return;
        }

        resetMethodState();
        assertRequireFullProfileOption();
        checkNotCompiled();

        switch (scenario) {
            case QUEUE_DOWNGRADE:
                testQueueDowngrade();
                break;
            case STANDARD_TRANSITION:
                testStandardTransition();
                break;
            case EXPLICIT_LEVEL_REQUEST:
                testExplicitLevelRequest();
                break;
            default:
                throw new RuntimeException("Unexpected scenario: " + scenario);
        }
    }

    @Override
    protected void checkLevel(int expected, int actual) {
        assertNotLimitedProfile(actual, "unexpected level");
        super.checkLevel(expected, actual);
    }

    private void testQueueDowngrade() {
        int bci = entryBci();
        WHITE_BOX.markMethodProfiled(method);

        WHITE_BOX.enqueueMethodForCompilation(method, COMP_LEVEL_FULL_PROFILE, bci);
        checkCompiled();
        assertNotLimitedProfile(getCompLevel(), "initial queue compilation");

        for (int i = 0; i < 1_000; ++i) {
            WHITE_BOX.enqueueMethodForCompilation(method, COMP_LEVEL_FULL_PROFILE, bci);
            waitBackgroundCompilation();
            assertNotLimitedProfile(getCompLevel(), "queue recompilation #" + i);
        }
    }

    private void testStandardTransition() throws Exception {
        int level = getCompLevel();

        while (level != COMP_LEVEL_FULL_OPTIMIZATION) {
            level = changeCompLevel(level);
        }

        compile();
        waitBackgroundCompilation();
        checkLevel(COMP_LEVEL_FULL_OPTIMIZATION, getCompLevel());
    }

    private void testExplicitLevelRequest() {
        WHITE_BOX.enqueueMethodForCompilation(method, COMP_LEVEL_LIMITED_PROFILE);
        checkCompiled();
        checkLevel(COMP_LEVEL_FULL_PROFILE, getCompLevel());

        resetMethodState();
        checkNotCompiled();

        WHITE_BOX.enqueueMethodForCompilation(method, COMP_LEVEL_FULL_OPTIMIZATION);
        checkCompiled();
        checkLevel(COMP_LEVEL_FULL_PROFILE, getCompLevel());

        WHITE_BOX.markMethodProfiled(method);
        WHITE_BOX.enqueueMethodForCompilation(method, COMP_LEVEL_FULL_OPTIMIZATION);
        checkCompiled();
        checkLevel(COMP_LEVEL_FULL_OPTIMIZATION, getCompLevel());
    }

    private int changeCompLevel(int currentLevel) throws Exception {
        long deadline = System.currentTimeMillis() + LEVEL_CHANGE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            compile();
            waitBackgroundCompilation();
            int newLevel = getCompLevel();
            assertNotLimitedProfile(newLevel, "standard transition");
            if (newLevel != currentLevel) {
                return newLevel;
            }
        }
        throw new RuntimeException("timed out waiting for level change from " + currentLevel);
    }

    private void assertRequireFullProfileOption() {
        Boolean option = WHITE_BOX.getMethodBooleanOption(method, OPTION_NAME);
        if (!Boolean.TRUE.equals(option)) {
            throw new RuntimeException("missing " + OPTION_NAME + " option on " + method + ": " + option);
        }
    }

    private void assertNotLimitedProfile(int level, String context) {
        if (level == COMP_LEVEL_LIMITED_PROFILE) {
            throw new RuntimeException(context + ": method with " + OPTION_NAME + " reached level 2");
        }
    }

    private int entryBci() {
        if (testCase.isOsr()) {
            return 0;
        }
        return WHITE_BOX.getMethodEntryBci(method);
    }

    private void resetMethodState() {
        waitBackgroundCompilation();
        WHITE_BOX.deoptimizeMethod(method, false);
        WHITE_BOX.deoptimizeMethod(method, true);
        WHITE_BOX.clearMethodState(method);
    }
}
