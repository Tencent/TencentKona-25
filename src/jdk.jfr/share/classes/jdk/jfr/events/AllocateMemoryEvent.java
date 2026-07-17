/*
 * Copyright (c) 2025, Tencent and/or its affiliates. All rights reserved.
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
 * Please contact Tencent for additional information.
 */

package jdk.jfr.events;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.DataAmount;
import jdk.jfr.MemoryAddress;
import jdk.jfr.Name;
import jdk.jfr.internal.Type;
import jdk.jfr.internal.MirrorEvent;

@Name(Type.EVENT_NAME_PREFIX + "AllocateMemory")
@Label("Allocate Memory")
@Category("Java Application")
@Description("Allocation of native memory via Unsafe.allocateMemory")
public final class AllocateMemoryEvent extends MirrorEvent {

    @Label("Bytes")
    @Description("Number of bytes requested")
    @DataAmount
    public long bytes;

    @Label("Address")
    @Description("Returned native memory address")
    @MemoryAddress
    public long address;

}
