/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
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

package au.net.zeus.util.jar.pack;

/**
 * Java 21 version-specific support stub.
 * This class is loaded on Java 21+ via the Multi-Release JAR mechanism.
 * Extend this class to add Java 21+ specific features (e.g., virtual threads,
 * pattern matching enhancements) as they become relevant to Pack200.
 */
class Java21Support {

    private Java21Support() {}

    /**
     * Returns the Java release version this support class targets.
     */
    static int targetRelease() {
        return 21;
    }
}
