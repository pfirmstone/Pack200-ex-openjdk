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
 * Java 17 specific support utilities for Pack200.
 * Loaded by the JVM when running on Java 17 or later (via Multi-Release JAR).
 *
 * <p>Provides reflection-based helpers for Java 17+ language features:
 * <ul>
 *   <li>Records (Java 16+) — {@link #isRecord(Class)}, {@link #getRecordComponents(Class)}</li>
 *   <li>Sealed classes (Java 17+) — {@link #isSealed(Class)}, {@link #getPermittedSubclasses(Class)}</li>
 * </ul>
 *
 * <p>This class compiles only under {@code --release 17} or higher and is placed in
 * {@code META-INF/versions/17/} in the Multi-Release JAR.
 */
public class Java17Support {

    private Java17Support() {}

    /**
     * Returns {@code true} if the given class is a record (Java 16+).
     *
     * @param clazz the class to test
     * @return {@code true} if the class is a record
     */
    public static boolean isRecord(Class<?> clazz) {
        return clazz.isRecord();
    }

    /**
     * Returns the record components of the given record class.
     * Returns an empty array if the class is not a record.
     *
     * @param clazz the record class
     * @return array of record component descriptors (name:descriptor pairs), or empty array
     */
    public static String[] getRecordComponents(Class<?> clazz) {
        if (!clazz.isRecord()) {
            return new String[0];
        }
        java.lang.reflect.RecordComponent[] components = clazz.getRecordComponents();
        if (components == null) {
            return new String[0];
        }
        String[] result = new String[components.length];
        for (int i = 0; i < components.length; i++) {
            result[i] = components[i].getName() + ":" + components[i].getGenericSignature();
        }
        return result;
    }

    /**
     * Returns {@code true} if the given class is sealed (Java 17+).
     *
     * @param clazz the class to test
     * @return {@code true} if the class is sealed
     */
    public static boolean isSealed(Class<?> clazz) {
        return clazz.isSealed();
    }

    /**
     * Returns the permitted subclasses of the given sealed class.
     * Returns an empty array if the class is not sealed.
     *
     * @param clazz the sealed class
     * @return array of permitted subclass names, or empty array
     */
    public static Class<?>[] getPermittedSubclasses(Class<?> clazz) {
        if (!clazz.isSealed()) {
            return new Class<?>[0];
        }
        Class<?>[] permitted = clazz.getPermittedSubclasses();
        return permitted != null ? permitted : new Class<?>[0];
    }

    /**
     * Returns the Java 17 pack200 package version.
     * @return "210.0" (Java 17 package version)
     */
    public static String getMaxPackageVersion() {
        return Constants.JAVA17_PACKAGE_VERSION.toString();
    }
}
