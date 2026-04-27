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
 * Java 21 specific support class for Pack200.
 * Loaded by the JVM when running on Java 21 or later (via Multi-Release JAR).
 *
 * <p>This class is a placeholder for Java 21 specific features.
 * Java 21 introduces:
 * <ul>
 *   <li>Pattern matching for switch (finalized)</li>
 *   <li>Record patterns (finalized)</li>
 *   <li>Virtual threads (Project Loom)</li>
 *   <li>Sequenced collections</li>
 * </ul>
 *
 * <p>Future enhancements can be added here as the Pack200 format is extended
 * to support Java 21 class file features.
 */
public class Java21Support {

    private Java21Support() {}

    /**
     * Returns the maximum package version supported by this Java 21+ runtime.
     * @return "220.0" (Java 21 package version)
     */
    public static String getMaxPackageVersion() {
        return Constants.JAVA21_PACKAGE_VERSION.toString();
    }
}
