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

package jdk.jfr.event.runtime;

import java.util.List;
import java.util.stream.Collectors;

import jdk.internal.misc.Unsafe;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.test.lib.Asserts;
import jdk.test.lib.jfr.EventNames;
import jdk.test.lib.jfr.Events;

/**
 * @test
 * @requires vm.flagless
 * @requires vm.hasJFR
 * @library /test/lib
 * @modules java.base/jdk.internal.misc
 * @run main/othervm jdk.jfr.event.runtime.TestUnsafeMemoryEvents
 */
public class TestUnsafeMemoryEvents {
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();

    public static void main(String[] args) throws Throwable {
        testUnsafeMemoryEvents();
        testReallocateMemoryToZero();
        testNoEventsForNoOps();
    }

    private static void testUnsafeMemoryEvents() throws Throwable {
        long allocated = 0;
        long reallocated = 0;

        try (Recording recording = new Recording()) {
            recording.enable(EventNames.UnsafeMemoryAllocation);
            recording.enable(EventNames.UnsafeMemoryReallocation);
            recording.enable(EventNames.UnsafeMemoryFree);
            recording.start();

            allocated = UNSAFE.allocateMemory(64);
            reallocated = UNSAFE.reallocateMemory(allocated, 128);
            allocated = 0;
            UNSAFE.freeMemory(reallocated);
            reallocated = 0;

            recording.stop();

            List<RecordedEvent> events = Events.fromRecording(recording);
            RecordedEvent allocateEvent = findEvent(events, EventNames.UnsafeMemoryAllocation);
            long allocatedAddress = allocateEvent.getLong("address");
            assertAllocateEvent(allocateEvent, 64L, allocatedAddress);

            RecordedEvent reallocateEvent = findEvent(events, EventNames.UnsafeMemoryReallocation);
            long reallocatedAddress = reallocateEvent.getLong("address");
            assertReallocateEvent(reallocateEvent, allocatedAddress, 128L, reallocatedAddress);

            RecordedEvent freeEvent = findEvent(events, EventNames.UnsafeMemoryFree);
            assertFreeEvent(freeEvent, reallocatedAddress);
        } finally {
            if (allocated != 0) {
                UNSAFE.freeMemory(allocated);
            }
            if (reallocated != 0) {
                UNSAFE.freeMemory(reallocated);
            }
        }
    }

    private static void testReallocateMemoryToZero() throws Throwable {
        long allocated = 0;

        try (Recording recording = new Recording()) {
            recording.enable(EventNames.UnsafeMemoryReallocation);
            recording.enable(EventNames.UnsafeMemoryFree);
            recording.start();

            allocated = UNSAFE.allocateMemory(64);
            long oldAddress = allocated;
            long reallocated = UNSAFE.reallocateMemory(allocated, 0);
            allocated = 0;

            Asserts.assertEquals(0L, reallocated, "Reallocation to size 0 should return 0");

            recording.stop();

            List<RecordedEvent> events = Events.fromRecording(recording);
            RecordedEvent reallocateEvent = findEvent(events, EventNames.UnsafeMemoryReallocation);
            assertReallocateEvent(reallocateEvent, oldAddress, 0L, 0L);
            assertNoEvent(events, EventNames.UnsafeMemoryFree);
        } finally {
            if (allocated != 0) {
                UNSAFE.freeMemory(allocated);
            }
        }
    }

    private static void testNoEventsForNoOps() throws Throwable {
        try (Recording recording = new Recording()) {
            recording.enable(EventNames.UnsafeMemoryAllocation);
            recording.enable(EventNames.UnsafeMemoryReallocation);
            recording.enable(EventNames.UnsafeMemoryFree);
            recording.start();

            Asserts.assertEquals(0L, UNSAFE.allocateMemory(0), "Allocation of size 0 should return 0");
            Asserts.assertEquals(0L, UNSAFE.reallocateMemory(0, 0), "Reallocation of address 0 to size 0 should return 0");
            UNSAFE.freeMemory(0);

            recording.stop();

            List<RecordedEvent> events = Events.fromRecording(recording);
            assertNoEvent(events, EventNames.UnsafeMemoryAllocation);
            assertNoEvent(events, EventNames.UnsafeMemoryReallocation);
            assertNoEvent(events, EventNames.UnsafeMemoryFree);
        }
    }

    private static RecordedEvent findEvent(List<RecordedEvent> events, String eventName) throws Exception {
        List<RecordedEvent> matchingEvents = events.stream()
                .filter(e -> eventName.equals(e.getEventType().getName()))
                .collect(Collectors.toList());
        Asserts.assertEquals(1, matchingEvents.size(), "Wrong number of " + eventName + " events");
        return matchingEvents.get(0);
    }

    private static void assertNoEvent(List<RecordedEvent> events, String eventName) throws Exception {
        long eventCount = events.stream()
                .filter(e -> eventName.equals(e.getEventType().getName()))
                .count();
        Asserts.assertEquals(0L, eventCount, "Unexpected " + eventName + " event");
    }

    private static void assertAllocateEvent(RecordedEvent event, long size, long address) {
        Events.assertField(event, "size").equal(size);
        Events.assertField(event, "address").equal(address);
    }

    private static void assertReallocateEvent(RecordedEvent event, long oldAddress, long size, long address) {
        Events.assertField(event, "oldAddress").equal(oldAddress);
        Events.assertField(event, "size").equal(size);
        Events.assertField(event, "address").equal(address);
    }

    private static void assertFreeEvent(RecordedEvent event, long address) {
        Events.assertField(event, "address").equal(address);
    }
}
