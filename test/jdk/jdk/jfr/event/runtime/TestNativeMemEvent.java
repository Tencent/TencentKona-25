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

package jdk.jfr.event.runtime;

import java.util.List;

import jdk.internal.misc.Unsafe;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.test.lib.Asserts;
import jdk.test.lib.Platform;
import jdk.test.lib.jfr.EventNames;
import jdk.test.lib.jfr.Events;

/**
 * @test
 * @key jfr
 * @requires vm.hasJFR
 * @library /test/lib
 * @modules java.base/jdk.internal.misc
 * @run main/othervm jdk.jfr.event.runtime.TestNativeMemEvent
 */
public class TestNativeMemEvent {

    private static final Unsafe UNSAFE = Unsafe.getUnsafe();

    public static void main(String[] args) throws Throwable {
        testNativeMemEvents();
        testEventsDisabledByDefault();
        testNoEventsForNoOps();
    }

    private static void testNativeMemEvents() throws Throwable {
        long addr;
        long reallocAddr;
        try (Recording recording = new Recording()) {
            recording.enable(EventNames.JavaNativeAllocation);
            recording.enable(EventNames.JavaNativeReallocate);
            recording.enable(EventNames.JavaNativeFree);
            recording.start();

            addr = UNSAFE.allocateMemory(100);
            reallocAddr = UNSAFE.reallocateMemory(addr, 1000);
            UNSAFE.freeMemory(reallocAddr);

            recording.stop();

            List<RecordedEvent> events = Events.fromRecording(recording);
            Events.hasEvents(events);

            // Other threads may perform native memory operations as well,
            // so match the events by the addresses used here.
            RecordedEvent allocation = findEvent(events, EventNames.JavaNativeAllocation, addr);
            // Unsafe.allocateMemory aligns bytes to heap word size, exactly 8 on 64-bit platforms.
            // The allocated size is 104, not 100, since the former is a multiple of 8.
            long expectedSize = Platform.is64bit() ? 104 : 100;
            Asserts.assertEquals(expectedSize, allocation.getLong("allocationSize"),
                    "Unexpected allocation size");
            Asserts.assertNotNull(allocation.getStackTrace(), "Expected a stack trace");

            RecordedEvent reallocate = findEvent(events, EventNames.JavaNativeReallocate, reallocAddr, "allocAddr");
            Asserts.assertEquals(addr, reallocate.getLong("freeAddr"), "Unexpected free address");
            Asserts.assertEquals(1000L, reallocate.getLong("allocationSize"),
                    "Unexpected reallocation size");
            Asserts.assertNotNull(reallocate.getStackTrace(), "Expected a stack trace");

            RecordedEvent free = findEvent(events, EventNames.JavaNativeFree, reallocAddr);
            Asserts.assertNotNull(free.getStackTrace(), "Expected a stack trace");
        }
    }

    private static void testEventsDisabledByDefault() throws Throwable {
        try (Recording recording = new Recording()) {
            recording.start();

            long addr = UNSAFE.allocateMemory(64);
            UNSAFE.freeMemory(addr);

            recording.stop();

            List<RecordedEvent> events = Events.fromRecording(recording);
            assertNoEvent(events, EventNames.JavaNativeAllocation);
            assertNoEvent(events, EventNames.JavaNativeReallocate);
            assertNoEvent(events, EventNames.JavaNativeFree);
        }
    }

    private static void testNoEventsForNoOps() throws Throwable {
        try (Recording recording = new Recording()) {
            recording.enable(EventNames.JavaNativeAllocation);
            recording.enable(EventNames.JavaNativeReallocate);
            recording.enable(EventNames.JavaNativeFree);
            recording.start();

            Asserts.assertEquals(0L, UNSAFE.allocateMemory(0), "Allocation of size 0 should return 0");
            Asserts.assertEquals(0L, UNSAFE.reallocateMemory(0, 0),
                    "Reallocation of address 0 to size 0 should return 0");
            UNSAFE.freeMemory(0);

            recording.stop();

            // No-op calls must not generate events. Events from other threads
            // always carry a non-zero address and a non-zero size.
            List<RecordedEvent> events = Events.fromRecording(recording);
            for (RecordedEvent event : events) {
                String name = event.getEventType().getName();
                if (EventNames.JavaNativeAllocation.equals(name)
                        || EventNames.JavaNativeReallocate.equals(name)) {
                    Asserts.assertNotEquals(0L, event.getLong("allocationSize"),
                            "Unexpected no-op event: " + event);
                }
                if (EventNames.JavaNativeFree.equals(name)) {
                    Asserts.assertNotEquals(0L, event.getLong("addr"),
                            "Unexpected no-op event: " + event);
                }
            }
        }
    }

    private static RecordedEvent findEvent(List<RecordedEvent> events, String eventName, long addr)
            throws Exception {
        return findEvent(events, eventName, addr, "addr");
    }

    private static RecordedEvent findEvent(List<RecordedEvent> events, String eventName, long addr,
            String addressField) throws Exception {
        List<RecordedEvent> matchingEvents = events.stream()
                .filter(e -> eventName.equals(e.getEventType().getName()))
                .filter(e -> addr == e.getLong(addressField))
                .toList();
        Asserts.assertEquals(1, matchingEvents.size(),
                "Wrong number of " + eventName + " events with " + addressField + "=" + addr);
        return matchingEvents.get(0);
    }

    private static void assertNoEvent(List<RecordedEvent> events, String eventName) throws Exception {
        long eventCount = events.stream()
                .filter(e -> eventName.equals(e.getEventType().getName()))
                .count();
        Asserts.assertEquals(0L, eventCount, "Unexpected " + eventName + " event");
    }
}
