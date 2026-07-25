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
 * @key jfr
 * @summary Verify JFR events for Unsafe native memory operations
 * @requires vm.hasJFR
 * @modules java.base/jdk.internal.misc
 * @run main/othervm jdk.jfr.event.runtime.TestJavaNativeMemoryEvents
 */
package jdk.jfr.event.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import jdk.internal.misc.Unsafe;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

public class TestJavaNativeMemoryEvents {

    private static final Unsafe UNSAFE = Unsafe.getUnsafe();

    private static final String ALLOCATE = "jdk.JavaNativeAllocation";
    private static final String REALLOCATE = "jdk.JavaNativeReallocate";
    private static final String FREE = "jdk.JavaNativeFree";

    private static final long ALLOC_BYTES = 100;
    private static final long REALLOC_BYTES = 1000;

    public static void main(String[] args) throws Exception {
        Recording r = new Recording();
        r.enable(ALLOCATE);
        r.enable(REALLOCATE);
        r.enable(FREE);
        r.start();

        long a1 = UNSAFE.allocateMemory(ALLOC_BYTES);
        long a2 = UNSAFE.reallocateMemory(a1, REALLOC_BYTES);
        UNSAFE.freeMemory(a2);

        r.stop();
        Path p = Files.createTempFile("javanative", ".jfr");
        r.dump(p);
        List<RecordedEvent> events = RecordingFile.readAllEvents(p);
        r.close();

        boolean allocOk = false;
        boolean reallocOk = false;
        boolean freeOk = false;

        for (RecordedEvent e : events) {
            String name = e.getEventType().getName();
            if (ALLOCATE.equals(name)
                    && e.getLong("addr") == a1
                    && isAligned(e.getLong("allocationSize"), ALLOC_BYTES)) {
                allocOk = true;
            } else if (REALLOCATE.equals(name)
                    && e.getLong("freeAddr") == a1
                    && e.getLong("allocAddr") == a2
                    && isAligned(e.getLong("allocationSize"), REALLOC_BYTES)) {
                reallocOk = true;
            } else if (FREE.equals(name)
                    && e.getLong("addr") == a2) {
                freeOk = true;
            }
        }

        assertTrue(allocOk, ALLOCATE + " event not found (addr=" + a1 + ")");
        assertTrue(reallocOk, REALLOCATE + " event not found (freeAddr="
                + a1 + ", allocAddr=" + a2 + ")");
        assertTrue(freeOk, FREE + " event not found (addr=" + a2 + ")");

        System.out.println("PASSED: allocate/reallocate/free events recorded"
                + " with correct addresses and sizes");
    }

    // allocateMemory/reallocateMemory align the request up to the heap word
    // size (<= 8 bytes on supported platforms), so the recorded size is the
    // requested value rounded up to the next word boundary.
    private static boolean isAligned(long recorded, long requested) {
        return recorded >= requested && recorded < requested + 8;
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }
}
