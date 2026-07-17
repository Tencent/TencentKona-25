/*
 * Copyright (c) 2025, Tencent and/or its affiliates. All rights reserved.
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
 * Please contact Tencent for additional information.
 */
package jdk.jfr.event.runtime;

import java.time.Duration;
import java.util.List;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;
import jdk.test.lib.jfr.EventNames;
import jdk.test.lib.jfr.Events;

/**
 * @test
 * @summary Verify JFR events for Unsafe allocateMemory, reallocateMemory and freeMemory
 * @requires vm.hasJFR
 * @modules java.base/jdk.internal.misc
 * @library /test/lib
 * @run main/othervm jdk.jfr.event.runtime.TestUnsafeMemoryEvents
 */
public class TestUnsafeMemoryEvents {

    private static final Unsafe U = Unsafe.getUnsafe();
    private static final long ALLOC_SIZE = 1024;

    public static void main(String[] args) throws Exception {
        testNormalPath();
        testAllocateMemoryZeroSize();
        testFreeMemoryZeroAddress();
        testReallocateMemoryZeroSize();
        testReallocateMemoryZeroAddress();
    }

    private static void testNormalPath() throws Exception {
        long allocAddr = 0;
        long allocAddrSaved = 0;
        long reallocAddr = 0;
        long reallocAddrSaved = 0;

        try (Recording recording = new Recording()) {
            recording.enable(EventNames.AllocateMemory)
                    .withThreshold(Duration.ofNanos(0));
            recording.enable(EventNames.ReallocateMemory)
                    .withThreshold(Duration.ofNanos(0));
            recording.enable(EventNames.FreeMemory)
                    .withThreshold(Duration.ofNanos(0));
            recording.start();

            try {
                allocAddr = U.allocateMemory(ALLOC_SIZE);
                allocAddrSaved = allocAddr;

                reallocAddr = U.reallocateMemory(allocAddr, ALLOC_SIZE * 2);
                reallocAddrSaved = reallocAddr;
                allocAddr = 0;

                U.freeMemory(reallocAddr);
                reallocAddr = 0;
            } finally {
                if (reallocAddr != 0) {
                    U.freeMemory(reallocAddr);
                }
                if (allocAddr != 0) {
                    U.freeMemory(allocAddr);
                }
            }

            recording.stop();

            List<RecordedEvent> events = Events.fromRecording(recording);
            verifyAllocateMemoryEvent(events, allocAddrSaved);
            verifyReallocateMemoryEvent(events, allocAddrSaved, reallocAddrSaved);
            verifyFreeMemoryEvent(events, reallocAddrSaved);
        }
    }

    // ---- edge cases ----

    private static void testAllocateMemoryZeroSize() throws Exception {
        try (Recording recording = new Recording()) {
            recording.enable(EventNames.AllocateMemory)
                    .withThreshold(Duration.ofNanos(0));
            recording.start();

            long addr = U.allocateMemory(0);

            recording.stop();

            Asserts.assertEquals(0L, addr, "allocateMemory(0) should return 0");
            List<RecordedEvent> events = Events.fromRecording(recording);
            long count = events.stream()
                    .filter(e -> e.getEventType().getName().equals(EventNames.AllocateMemory))
                    .count();
            Asserts.assertEquals(0L, count,
                    "allocateMemory(0) should not trigger AllocateMemory event");
        }
    }

    private static void testFreeMemoryZeroAddress() throws Exception {
        try (Recording recording = new Recording()) {
            recording.enable(EventNames.FreeMemory)
                    .withThreshold(Duration.ofNanos(0));
            recording.start();

            U.freeMemory(0);

            recording.stop();

            List<RecordedEvent> events = Events.fromRecording(recording);
            long count = events.stream()
                    .filter(e -> e.getEventType().getName().equals(EventNames.FreeMemory))
                    .count();
            Asserts.assertEquals(0L, count,
                    "freeMemory(0) should not trigger FreeMemory event");
        }
    }

    private static void testReallocateMemoryZeroSize() throws Exception {
        try (Recording recording = new Recording()) {
            recording.enable(EventNames.ReallocateMemory)
                    .withThreshold(Duration.ofNanos(0));
            recording.enable(EventNames.FreeMemory)
                    .withThreshold(Duration.ofNanos(0));
            recording.start();

            long addr = U.allocateMemory(ALLOC_SIZE);
            long newAddr = U.reallocateMemory(addr, 0);
            // addr was freed internally by reallocateMemory(bytes==0)

            recording.stop();

            Asserts.assertEquals(0L, newAddr, "reallocateMemory(addr, 0) should return 0");

            List<RecordedEvent> events = Events.fromRecording(recording);

            // Should NOT have ReallocateMemory event
            long reallocCount = events.stream()
                    .filter(e -> e.getEventType().getName().equals(EventNames.ReallocateMemory))
                    .count();
            Asserts.assertEquals(0L, reallocCount,
                    "reallocateMemory(addr, 0) should not trigger ReallocateMemory event");

            List<RecordedEvent> freeEvents =
                events.stream()
                    .filter(e -> e.getEventType()
                    .getName()
                    .equals(EventNames.FreeMemory))
                    .toList();

            Asserts.assertEquals(1, freeEvents.size());

            RecordedEvent event = freeEvents.get(0);

            Asserts.assertEquals(
                addr,
                event.getLong("address"),
                "free address should match original address"
            );
        }
    }

    private static void testReallocateMemoryZeroAddress() throws Exception {
        try (Recording recording = new Recording()) {
            recording.enable(EventNames.ReallocateMemory)
                    .withThreshold(Duration.ofNanos(0));
            recording.start();

            long newAddr = 0;
            try {
                newAddr = U.reallocateMemory(0, ALLOC_SIZE);
            } finally {
                if (newAddr != 0) {
                    U.freeMemory(newAddr);
                }
            }

            recording.stop();

            Asserts.assertNotEquals(0L, newAddr, "reallocateMemory(0, bytes) should allocate");

            List<RecordedEvent> events = Events.fromRecording(recording);
            List<RecordedEvent> matched = events.stream()
                    .filter(e -> e.getEventType().getName().equals(EventNames.ReallocateMemory))
                    .toList();

            Asserts.assertEquals(1, matched.size(),
                    "reallocateMemory(0, bytes) should trigger ReallocateMemory event");
            RecordedEvent event = matched.get(0);

            Asserts.assertEquals(0L, event.getLong("oldAddress"),
                    "oldAddress should be 0 when called with address=0");
            Asserts.assertEquals(newAddr, event.getLong("newAddress"),
                    "newAddress should match the allocated address");
            Asserts.assertEquals(ALLOC_SIZE, event.getLong("bytes"),
                    "bytes should match requested size");
        }
    }

    private static void verifyAllocateMemoryEvent(List<RecordedEvent> events, long expectedAddr) {
        List<RecordedEvent> matched = events.stream()
                .filter(e -> e.getEventType().getName().equals(EventNames.AllocateMemory))
                .toList();

        Asserts.assertEquals(1, matched.size(),
                "Expected exactly one AllocateMemory event");
        RecordedEvent event = matched.get(0);

        Asserts.assertEquals(ALLOC_SIZE, event.getLong("bytes"),
                "bytes should match allocated size");
        Asserts.assertEquals(expectedAddr, event.getLong("address"),
                "address should match the allocated address");
        Asserts.assertGTE(event.getDuration(), Duration.ZERO,
                "duration should be >= 0");
    }

    private static void verifyReallocateMemoryEvent(List<RecordedEvent> events, long oldAddr, long newAddr) {
        List<RecordedEvent> matched = events.stream()
                .filter(e -> e.getEventType().getName().equals(EventNames.ReallocateMemory))
                .toList();

        Asserts.assertEquals(1, matched.size(),
                "Expected exactly one ReallocateMemory event");
        RecordedEvent event = matched.get(0);

        Asserts.assertEquals(oldAddr, event.getLong("oldAddress"),
                "oldAddress should match original address");
        Asserts.assertEquals(newAddr, event.getLong("newAddress"),
                "newAddress should match reallocated address");
        Asserts.assertEquals(ALLOC_SIZE * 2, event.getLong("bytes"),
                "bytes should match requested size");
        Asserts.assertGTE(event.getDuration(), Duration.ZERO,
                "duration should be >= 0");
    }

    private static void verifyFreeMemoryEvent(List<RecordedEvent> events, long freedAddr) {
        List<RecordedEvent> matched = events.stream()
                .filter(e -> e.getEventType().getName().equals(EventNames.FreeMemory))
                .toList();

        Asserts.assertEquals(1, matched.size(),
                "Expected exactly one FreeMemory event");
        RecordedEvent event = matched.get(0);

        Asserts.assertEquals(freedAddr, event.getLong("address"),
                "address should match the freed address");
        Asserts.assertGTE(event.getDuration(), Duration.ZERO,
                "duration should be >= 0");
    }
}
