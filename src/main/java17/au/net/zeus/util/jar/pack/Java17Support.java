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
 * Java 17 version-specific support using native Java 17+ reflection APIs.
 * This class is loaded on Java 17+ via the Multi-Release JAR mechanism.
 * It provides support for Records and Sealed classes introduced in Java 17.
 */
class Java17Support {

    private Java17Support() {}

    /**
     * Returns the Java release version this support class targets.
     */
    static int targetRelease() {
        return 17;
    }

    /**
     * Returns {@code true} if the given class is a record class.
     *
     * @param clazz the class to check
     * @return {@code true} if {@code clazz} is a record
     */
    static boolean isRecord(Class<?> clazz) {
        return clazz.isRecord();
    }

    /**
     * Returns {@code true} if the given class is a sealed class or interface.
     *
     * @param clazz the class to check
     * @return {@code true} if {@code clazz} is sealed
     */
    static boolean isSealed(Class<?> clazz) {
        return clazz.isSealed();
    }

    /**
     * Returns the record components of the given record class, or an empty
     * array if the class is not a record.
     *
     * @param clazz the class to inspect
     * @return array of {@link java.lang.reflect.RecordComponent} objects
     */
    static java.lang.reflect.RecordComponent[] getRecordComponents(Class<?> clazz) {
        java.lang.reflect.RecordComponent[] components = clazz.getRecordComponents();
        return components != null ? components : new java.lang.reflect.RecordComponent[0];
    }

    /**
     * Returns the permitted subclasses of the given sealed class or interface,
     * or an empty array if the class is not sealed.
     *
     * @param clazz the class to inspect
     * @return array of permitted subclass {@link Class} objects
     */
    static Class<?>[] getPermittedSubclasses(Class<?> clazz) {
        Class<?>[] subclasses = clazz.getPermittedSubclasses();
        return subclasses != null ? subclasses : new Class<?>[0];
    }
}
