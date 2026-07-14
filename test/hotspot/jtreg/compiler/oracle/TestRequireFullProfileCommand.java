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
 * @summary The requirefullprofile CompileCommand is accepted via both the direct
 *          (requirefullprofile,<method>) and the option (option,<method>,requirefullprofile)
 *          syntaxes, and is reported through the WhiteBox getMethodBooleanOption API.
 * @library /test/lib
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:CompileCommand=requirefullprofile,compiler.oracle.TestRequireFullProfileCommand::marked
 *      compiler.oracle.TestRequireFullProfileCommand
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:CompileCommand=option,compiler.oracle.TestRequireFullProfileCommand::marked,requirefullprofile
 *      compiler.oracle.TestRequireFullProfileCommand
 */

package compiler.oracle;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestRequireFullProfileCommand {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();

    static void marked() { }
    static void other() { }

    public static void main(String[] args) throws Exception {
        Method marked = TestRequireFullProfileCommand.class.getDeclaredMethod("marked");
        Method other = TestRequireFullProfileCommand.class.getDeclaredMethod("other");

        Boolean onMarked = WB.getMethodBooleanOption(marked, "requirefullprofile");
        Asserts.assertNotNull(onMarked, "requirefullprofile should be reported for the marked method");
        Asserts.assertTrue(onMarked, "requirefullprofile should be true for the marked method");

        Asserts.assertNull(WB.getMethodBooleanOption(other, "requirefullprofile"),
                "requirefullprofile should not be set on an unmarked method");

        System.out.println("TestRequireFullProfileCommand passed");
    }
}
