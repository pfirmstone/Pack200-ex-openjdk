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
 * Java 17 specific support class for Pack200.
 * Provides helpers for Java 17 features: Records and Sealed classes.
 * Uses Java 17 reflection APIs ({@code Class.isRecord()} and
 * {@code Class.isSealed()}) that are not available in earlier versions.
 * This class is loaded in preference to any lower-version implementation
 * when running on Java 17 or later.
 */
class Java17Support {

    private Java17Support() {}

    /**
     * Returns the Java version this support class targets.
     * @return 17
     */
    static int targetVersion() {
        return 17;
    }

    /**
     * Returns {@code true} if the given class is a record class.
     * Uses the Java 16+ {@code Class.isRecord()} API.
     *
     * @param clazz the class to test
     * @return {@code true} if the class is a record
     */
    static boolean isRecord(Class<?> clazz) {
        return clazz.isRecord();
    }

    /**
     * Returns {@code true} if the given class or interface is sealed.
     * Uses the Java 17+ {@code Class.isSealed()} API.
     *
     * @param clazz the class or interface to test
     * @return {@code true} if the class is sealed
     */
    static boolean isSealed(Class<?> clazz) {
        return clazz.isSealed();
    }

    /**
     * Returns the permitted subclasses of the given sealed class or interface.
     * Uses the Java 17+ {@code Class.getPermittedSubclasses()} API.
     *
     * @param clazz the sealed class or interface
     * @return array of permitted subclasses, or {@code null} if not sealed
     */
    static Class<?>[] getPermittedSubclasses(Class<?> clazz) {
        return clazz.getPermittedSubclasses();
    }
}
