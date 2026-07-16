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
 */

/*
 * @test
 * @summary -XX:CompileCommand=requirefullprofile forces a marked method through the
 *          full-profile tier (level 3): it is never compiled at limited profile (level 2)
 *          and never skips level 3 on its way to full optimization (level 4). Simple
 *          (level 1) decisions are not rewritten, so a marked trivial method keeps the
 *          stock level-1 policy. Verifies the actual compilation levels, not just that
 *          the command parses.
 * @requires vm.compMode != "Xcomp"
 * @requires vm.compiler1.enabled & vm.compiler2.enabled
 * @requires (vm.opt.TieredStopAtLevel == null | vm.opt.TieredStopAtLevel == 4)
 * @requires (vm.opt.CompilationMode == null | vm.opt.CompilationMode == "default" | vm.opt.CompilationMode == "normal")
 * @library /test/lib
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm/timeout=240 -Xbootclasspath/a:. -Xmixed
 *      -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:+TieredCompilation -XX:TieredStopAtLevel=4 -XX:-BackgroundCompilation
 *      -XX:CompileCommand=requirefullprofile,compiler.oracle.TestRequireFullProfileLevels::markedTrivial
 *      -XX:CompileCommand=requirefullprofile,compiler.oracle.TestRequireFullProfileLevels::markedLoop
 *      -XX:CompileCommand=requirefullprofile,compiler.oracle.TestRequireFullProfileLevels::markedReprofile
 *      -XX:CompileCommand=requirefullprofile,compiler.oracle.TestRequireFullProfileLevels::markedOsr
 *      compiler.oracle.TestRequireFullProfileLevels
 */

package compiler.oracle;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;
import jtreg.SkippedException;

public class TestRequireFullProfileLevels {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();

    // Mirror of the HotSpot CompLevel enum.
    private static final int NONE = 0, SIMPLE = 1, LIMITED_PROFILE = 2, FULL_PROFILE = 3, FULL_OPTIMIZATION = 4;

    private static final int WARMUP_ITERATIONS = 2_000_000;

    // Prevents dead-code elimination of the trivial getters below.
    static int sink;

    // Constant getters -- CompilationPolicy::is_trivial() is true for these.
    static int markedTrivial()  { return 42; }
    static int controlTrivial() { return 7; }

    // A non-trivial method used for the forced level-2 -> level-3 check.
    static int markedLoop() {
        int a = 0;
        for (int i = 0; i < 4; i++) {
            a += i * sink;
        }
        return a;
    }

    // A method with a hot back-edge loop, used to drive an OSR compilation.
    static long markedOsr() {
        long a = 0;
        for (int i = 0; i < 200_000; i++) {
            a += (long) i * sink;
        }
        return a;
    }

    // An invocation-driven method used for the deopt-and-reprofile check.
    static int markedReprofile() {
        int a = 0;
        for (int i = 0; i < 4; i++) {
            a += i * sink;
        }
        return a;
    }

    // Unmarked counterparts of markedLoop / markedReprofile, used as same-JVM negative controls: under
    // the identical WhiteBox setup they must reach full optimization (4), proving the level-2 -> 4 and
    // level-0 -> 4 shortcuts the feature suppresses are genuinely present -- so the marked assertions
    // are real discriminators, not vacuously satisfied by a method that would reach level 3 on its own.
    static int controlLoop() {
        int a = 0;
        for (int i = 0; i < 4; i++) {
            a += i * sink;
        }
        return a;
    }

    static int controlReprofile() {
        int a = 0;
        for (int i = 0; i < 4; i++) {
            a += i * sink;
        }
        return a;
    }

    public static void main(String[] args) throws Exception {
        // The trajectory assertions below only hold under the default (normal) 4-tier tiered
        // configuration, which is the only mode that has a full-profile (level 3) tier.
        Boolean tiered = WB.getBooleanVMFlag("TieredCompilation");
        Long stopAt = WB.getIntxVMFlag("TieredStopAtLevel");
        String mode = WB.getStringVMFlag("CompilationMode");
        boolean normalMode = mode == null || mode.isEmpty() || mode.equals("default") || mode.equals("normal");
        if (tiered == null || !tiered || stopAt == null || stopAt != FULL_OPTIMIZATION || !normalMode) {
            throw new SkippedException("requires TieredCompilation, TieredStopAtLevel=4, and the normal CompilationMode");
        }

        testMarkedTrivialKeepsSimple();
        testForcedLimitedProfileStepsToFullProfile();
        testDeoptReprofileDoesNotSkipFullProfile();
        testOsrMarkedMethodProfilesThroughFullProfile();

        System.out.println("TestRequireFullProfileLevels passed");
    }

    // Simple (level 1) decisions are deliberately NOT rewritten by requirefullprofile: the option
    // governs the path to full optimization (level 4), and a trivial method the policy routes to
    // simple never takes that path -- forcing it to collect a profile would only pin it in slower
    // profiling code at level 3. A marked trivial method must therefore behave exactly like its
    // unmarked sibling and stay at simple (1). This is a regression test against re-widening the
    // clamp to level-1 decisions.
    private static void testMarkedTrivialKeepsSimple() throws Exception {
        Method marked = method("markedTrivial");
        Method control = method("controlTrivial");
        WB.testSetDontInlineMethod(marked, true);
        WB.testSetDontInlineMethod(control, true);

        warmUntilCompiled(marked, TestRequireFullProfileLevels::markedTrivial);
        warmUntilCompiled(control, TestRequireFullProfileLevels::controlTrivial);

        int markedLevel = WB.getMethodCompilationLevel(marked, false);
        int controlLevel = WB.getMethodCompilationLevel(control, false);

        Asserts.assertEquals(markedLevel, SIMPLE,
                "marked trivial method must keep the stock simple (1) decision, got " + markedLevel);
        Asserts.assertEquals(controlLevel, SIMPLE,
                "unmarked trivial method (control) must stay at simple (1), got " + controlLevel);
    }

    // Force the method to exactly level 2, then let natural counter events drive the next step and
    // assert it is level 3 -- a marked method must never jump limited profile (2) -> full opt (4).
    private static void testForcedLimitedProfileStepsToFullProfile() throws Exception {
        Method m = method("markedLoop");
        WB.testSetDontInlineMethod(m, true);

        Asserts.assertTrue(WB.enqueueMethodForCompilation(m, LIMITED_PROFILE),
                "could not enqueue markedLoop at limited profile (2)");
        Asserts.assertEQ(WB.getMethodCompilationLevel(m, false), LIMITED_PROFILE,
                "setup: markedLoop should be compiled at limited profile (2)");

        // Seed a mature profile so that WITHOUT the feature the ordinary policy takes the
        // is_method_profiled() short-circuit and would jump limited profile (2) -> full optimization
        // (4). The feature must instead cap the step at full profile (3); asserting exactly 3 below is
        // therefore regression-sensitive.
        WB.markMethodProfiled(m);

        int level = LIMITED_PROFILE;
        for (int i = 0; i < WARMUP_ITERATIONS && level == LIMITED_PROFILE; i++) {
            sink = markedLoop();
            level = WB.getMethodCompilationLevel(m, false);
        }
        Asserts.assertEquals(level, FULL_PROFILE,
                "marked method must step from limited profile (2) to full profile (3), got " + level);

        // Negative control: the identical setup on an unmarked method must take the limited profile (2)
        // -> full optimization (4) shortcut, proving that shortcut exists and the assertion above is a
        // genuine discriminator rather than a method that would settle at level 3 on its own.
        Method control = method("controlLoop");
        WB.testSetDontInlineMethod(control, true);
        Asserts.assertTrue(WB.enqueueMethodForCompilation(control, LIMITED_PROFILE),
                "could not enqueue controlLoop at limited profile (2)");
        WB.markMethodProfiled(control);
        int controlLevel = LIMITED_PROFILE;
        for (int i = 0; i < WARMUP_ITERATIONS && controlLevel != FULL_OPTIMIZATION; i++) {
            sink = controlLoop();
            controlLevel = WB.getMethodCompilationLevel(control, false);
        }
        Asserts.assertEquals(controlLevel, FULL_OPTIMIZATION,
                "unmarked control must take the limited profile (2) -> full optimization (4) shortcut, got " + controlLevel);
    }

    // Post-deopt reprofiling: after a marked method deoptimizes back to the interpreter it keeps its
    // MDO, so transition_from_none's opening branch would jump straight to full optimization (4). The
    // feature must cap that first post-deopt (re)compilation at full profile (3). Asserting exactly 3
    // makes this regression-sensitive for the MDO-at-none path.
    private static void testDeoptReprofileDoesNotSkipFullProfile() throws Exception {
        Method m = method("markedReprofile");
        WB.testSetDontInlineMethod(m, true);
        for (int i = 0; i < WARMUP_ITERATIONS && WB.getMethodCompilationLevel(m, false) < FULL_PROFILE; i++) {
            sink = markedReprofile();
        }
        WB.markMethodProfiled(m);
        WB.deoptimizeMethod(m);
        int level = NONE;
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            sink = markedReprofile();
            level = WB.getMethodCompilationLevel(m, false);
            if (level > NONE) {
                break; // capture the first post-deopt compiled level
            }
        }
        Asserts.assertTrue(level > NONE, "marked method should recompile after deoptimization");
        Asserts.assertNotEquals(level, LIMITED_PROFILE, "post-deopt marked method must not recompile at level 2");
        Asserts.assertEquals(level, FULL_PROFILE,
                "post-deopt marked method must recompile at full profile (3) first, got " + level);

        // Negative control: the identical warm + profile + deopt sequence on an unmarked method must
        // recompile straight to full optimization (4), confirming the level-0 -> 4 jump the feature
        // caps is genuinely present (so the marked assertion above is a real discriminator).
        Method control = method("controlReprofile");
        WB.testSetDontInlineMethod(control, true);
        for (int i = 0; i < WARMUP_ITERATIONS && WB.getMethodCompilationLevel(control, false) < FULL_PROFILE; i++) {
            sink = controlReprofile();
        }
        WB.markMethodProfiled(control);
        WB.deoptimizeMethod(control);
        int controlLevel = NONE;
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            sink = controlReprofile();
            controlLevel = WB.getMethodCompilationLevel(control, false);
            if (controlLevel > NONE) {
                break; // capture the first post-deopt compiled level
            }
        }
        Asserts.assertEquals(controlLevel, FULL_OPTIMIZATION,
                "unmarked control must recompile straight to full optimization (4) after deopt, got " + controlLevel);
    }

    // OSR smoke/coverage: a marked method warmed through a hot back-edge loop compiles an OSR nmethod
    // and never lands at limited profile (2). OSR routes through the same common<LoopPredicate>
    // template as the invocation path, so the level-3-skip clamp is shared code; the
    // regression-sensitive assertions live in the invocation-driven tests above. Note that
    // getMethodCompilationLevel reports the HIGHEST installed OSR level, and a marked level-3 OSR
    // method may legitimately re-OSR to level 4 within a long loop, so an exact-level assertion is not
    // a valid discriminator here.
    private static void testOsrMarkedMethodProfilesThroughFullProfile() throws Exception {
        Method m = method("markedOsr");
        WB.testSetDontInlineMethod(m, true);
        for (int i = 0; i < 20 && WB.getMethodCompilationLevel(m, true) <= NONE; i++) {
            sink = (int) markedOsr();
        }
        int osr = WB.getMethodCompilationLevel(m, true);
        Asserts.assertTrue(osr > NONE, "an OSR nmethod for the marked method should have been produced");
        Asserts.assertNotEquals(osr, LIMITED_PROFILE, "marked OSR method must never be at limited profile (2), got " + osr);
        Asserts.assertGreaterThanOrEqual(osr, FULL_PROFILE, "marked OSR method must be at full profile (3) or above, got " + osr);
    }

    private static void warmUntilCompiled(Method m, Runnable body) {
        for (int i = 0; i < WARMUP_ITERATIONS && !WB.isMethodCompiled(m, false); i++) {
            body.run();
        }
        Asserts.assertTrue(WB.isMethodCompiled(m, false),
                "method " + m.getName() + " was never compiled after warmup");
    }

    private static Method method(String name) throws Exception {
        return TestRequireFullProfileLevels.class.getDeclaredMethod(name);
    }
}
