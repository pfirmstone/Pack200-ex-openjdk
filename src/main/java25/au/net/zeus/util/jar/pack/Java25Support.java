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
 * Java 25 specific support class for Pack200.
 * Loaded by the JVM when running on Java 25 or later (via Multi-Release JAR).
 *
 * <p>This class is a placeholder for Java 25 specific features.
 * Future enhancements can be added here as the Pack200 format is extended
 * to support Java 25 class file features.
 */
public class Java25Support {

    private Java25Support() {}

    /**
     * Returns the maximum package version supported by this Java 25+ runtime.
     * @return "230.0" (Java 25 package version)
     */
    public static String getMaxPackageVersion() {
        return Constants.JAVA25_PACKAGE_VERSION.toString();
    }
}
