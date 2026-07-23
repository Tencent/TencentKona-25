/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
 * @summary Verify that the requirefullprofile CompileCommand routes a hot
 *          method through Tier 3 (full profile) instead of Tier 2 (limited
 *          profile) when the C2 compile queue is congested.
 * @library /test/lib
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm TestRequireFullProfile
 */

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import jdk.test.whitebox.WhiteBox;

public class TestRequireFullProfile {

    private static final int TIER_LIMITED_PROFILE = 2; // Tier 2
    private static final int TIER_FULL_PROFILE = 3;    // Tier 3
    private static final int TIER_FULL_OPT = 4;        // Tier 4 (C2)

    static volatile int sink;

    // ---------------- entry point ----------------

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            runParent();
        } else {
            runChild(args[0].equals("withOption"));
        }
    }

    private static void runParent() throws Exception {
        // Baseline: no option -> under C2 congestion the hot method is compiled
        // at Tier 2 (limited profile).
        runChildJvm(false);
        // With requirefullprofile -> the hot method skips Tier 2 and is compiled
        // at Tier 3 (full profile) even under the same congestion.
        runChildJvm(true);
        System.out.println("ALL PASSED");
    }

    private static void runChildJvm(boolean withOption) throws Exception {
        String javaBin = System.getProperty("java.home") + java.io.File.separator
                + "bin" + java.io.File.separator + "java";
        String cp = System.getProperty("java.class.path");

        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-Xbootclasspath/a:" + cp);
        cmd.add("-XX:+UnlockDiagnosticVMOptions");
        cmd.add("-XX:+WhiteBoxAPI");
        cmd.add("-XX:+TieredCompilation");
        cmd.add("-XX:CICompilerCount=2");
        cmd.add("-XX:Tier3DelayOn=1");
        // Keep the driver out of the compile queues so only targetMethod's
        // policy decision is observed.
        cmd.add("-XX:CompileCommand=exclude,TestRequireFullProfile::hammer");
        if (withOption) {
            cmd.add("-XX:CompileCommand=requirefullprofile,TestRequireFullProfile::targetMethod");
        }
        cmd.add("-cp");
        cmd.add(cp);
        cmd.add("TestRequireFullProfile");
        cmd.add(withOption ? "withOption" : "baseline");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        int rc = p.waitFor();

        System.out.println("==== child (" + (withOption ? "withOption" : "baseline")
                + ") exit=" + rc + " ====");
        System.out.println(out.strip());
        if (rc != 0 || !out.contains("PASSED")) {
            throw new AssertionError("child ("
                    + (withOption ? "withOption" : "baseline") + ") failed");
        }
    }

    // ---------------- child ----------------

    private static void runChild(boolean withOption) throws Exception {
        WhiteBox wb = WhiteBox.getWhiteBox();
        Method target = method("targetMethod");
        Method[] dummies = {
            method("dummy1"), method("dummy2"), method("dummy3"),
            method("dummy4"), method("dummy5"), method("dummy6"),
        };

        wb.clearMethodState(target);

        // Freeze the compiler threads so the C2 queue we build up stays
        // congested while targetMethod's level is decided.
        wb.lockCompilation();
        boolean unlocked = false;
        try {
            for (Method d : dummies) {
                wb.enqueueMethodForCompilation(d, TIER_FULL_OPT);
            }
            int q4 = wb.getCompileQueueSize(TIER_FULL_OPT);
            System.out.println("C2 (Tier 4) queue size = " + q4);
            check(q4 >= 2, "failed to inflate C2 queue (size=" + q4 + ")");

            // Drive targetMethod hot with the *minimum* work needed to make the
            // policy decide its level and enqueue it. Stop as soon as it is
            // queued so it does not become "fully profiled" (which would let the
            // task selector legitimately downgrade a Tier 3 task back to Tier 2).
            boolean queued = false;
            for (int r = 0; r < 20_000 && !queued; r++) {
                hammer(100);
                queued = wb.isMethodQueuedForCompilation(target);
            }
            check(queued, "targetMethod was never enqueued under congestion");
        } finally {
            wb.unlockCompilation();
            unlocked = true;
        }

        // Let the queued task compile. Do NOT drive the method any further, so
        // its profile stays minimal and the enqueued level is what gets compiled.
        long deadline = System.currentTimeMillis() + 30_000;
        while (!wb.isMethodCompiled(target)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        int level = wb.getMethodCompilationLevel(target);
        System.out.println("compiled=" + wb.isMethodCompiled(target)
                + " level=" + level);

        if (withOption) {
            check(level != TIER_LIMITED_PROFILE,
                  "requirefullprofile set, but targetMethod was compiled at "
                  + "Tier 2 (limited profile)");
            check(level == TIER_FULL_PROFILE || level == TIER_FULL_OPT,
                  "requirefullprofile set, but targetMethod was compiled at "
                  + "level " + level + " (expected Tier 3)");
            System.out.println("RESULT: Tier " + level + " - Tier 2 skipped, option honored");
        } else {
            check(level == TIER_LIMITED_PROFILE,
                  "baseline: expected targetMethod at Tier 2 (limited profile) "
                  + "under C2 congestion, but level was " + level);
            System.out.println("RESULT: Tier 2 (limited profile) - baseline");
        }
        System.out.println("PASSED");
    }

    // ---------------- workload ----------------

    static void hammer(int n) {
        int s = 0;
        for (int i = 0; i < n; i++) {
            s += targetMethod(i);
        }
        sink = s;
    }

    // Non-trivial so the policy does not classify it as trivial (-> Tier 1).
    static int targetMethod(int x) {
        int s = x;
        for (int i = 0; i < 8; i++) {
            s += (x ^ (i * 31)) * 7 + i;
        }
        return s;
    }

    static int dummy1(int x) { int s = x; for (int i = 0; i < 8; i++) s += x * i + 1; return s; }
    static int dummy2(int x) { int s = x; for (int i = 0; i < 8; i++) s += x * i + 2; return s; }
    static int dummy3(int x) { int s = x; for (int i = 0; i < 8; i++) s += x * i + 3; return s; }
    static int dummy4(int x) { int s = x; for (int i = 0; i < 8; i++) s += x * i + 4; return s; }
    static int dummy5(int x) { int s = x; for (int i = 0; i < 8; i++) s += x * i + 5; return s; }
    static int dummy6(int x) { int s = x; for (int i = 0; i < 8; i++) s += x * i + 6; return s; }

    private static Method method(String name) throws Exception {
        return TestRequireFullProfile.class.getDeclaredMethod(name, int.class);
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }
}
