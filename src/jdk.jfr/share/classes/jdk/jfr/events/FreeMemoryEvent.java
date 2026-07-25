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
import jdk.jfr.MemoryAddress;
import jdk.jfr.Name;
import jdk.jfr.internal.Type;
import jdk.jfr.internal.MirrorEvent;

@Name(Type.EVENT_NAME_PREFIX + "FreeMemory")
@Label("Free Memory")
@Category("Java Application")
@Description("Deallocation of native memory via Unsafe.freeMemory")
@StackFilter("jdk.internal.misc.Unsafe")
public final class FreeMemoryEvent extends MirrorEvent {

    @Label("Address")
    @Description("Native memory address being freed")
    @MemoryAddress
    public long address;

}
