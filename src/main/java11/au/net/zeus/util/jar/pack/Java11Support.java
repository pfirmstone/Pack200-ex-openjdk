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
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package au.net.zeus.util.jar.pack;

/**
 * Java 11 specific support class.
 * Loaded by the JVM when running on Java 11 or later (via Multi-Release JAR).
 * Java 11 support is largely covered by the base implementation; this class
 * is a placeholder for any Java 11 specific enhancements.
 */
public class Java11Support {

    private Java11Support() {}

    /**
     * Returns the maximum package version supported by this Java 11 runtime.
     * @return "190.0"
     */
    public static String getMaxPackageVersion() {
        return Constants.JAVA11_PACKAGE_VERSION.toString();
    }
}
