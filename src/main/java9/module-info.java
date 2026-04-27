/*
 * Copyright (c) 2001, 2025, Oracle and/or its affiliates. All rights reserved.
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
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/**
 * JPMS module descriptor for the Pack200 implementation.
 *
 * <p>Placed in {@code META-INF/versions/9/} so that it is only visible on
 * Java 9+ runtimes (the JAR is a Multi-Release JAR).  On Java 8 runtimes the
 * base class files in the root of the JAR are used and the module system is
 * not active, so no module declaration is needed there.
 *
 * <p>Design notes:
 * <ul>
 *   <li>Only {@code net.pack200} is exported: it contains the public API
 *       ({@link net.pack200.Pack200}) that callers depend on.</li>
 *   <li>The implementation package {@code au.net.zeus.util.jar.pack} is
 *       <em>not</em> exported; it is an implementation detail.</li>
 *   <li>{@code java.desktop} is declared {@code requires static} (optional
 *       compile-time dependency) because {@code java.beans.PropertyChangeListener}
 *       is used via reflection in {@code PropMap.Beans}; the code works without
 *       it at runtime if {@code java.beans} is absent.</li>
 *   <li>The {@code provides} clauses register {@code PackerImpl} and
 *       {@code UnpackerImpl} as {@link java.util.ServiceLoader} providers so
 *       that callers on the module path can obtain instances without relying on
 *       the internal class names.</li>
 * </ul>
 */
module au.net.zeus.util.jar.pack {

    // ---- Exported packages ------------------------------------------------

    /** The public Pack200 API. */
    exports net.pack200;

    // ---- Required modules --------------------------------------------------

    /** java.util.logging.Logger is used throughout the implementation. */
    requires java.logging;

    /**
     * java.beans.PropertyChangeListener / PropertyChangeEvent are used
     * optionally (via reflection in PropMap.Beans).  Declared static so the
     * module can load without java.desktop on the module path.
     */
    requires static java.desktop;

    // ---- Service registrations --------------------------------------------

    /**
     * Register the default packer implementation so that callers using the
     * ServiceLoader pattern can obtain an instance without knowing internal
     * class names.
     */
    provides net.pack200.Pack200.Packer
        with au.net.zeus.util.jar.pack.PackerImpl;

    /**
     * Register the default unpacker implementation so that callers using the
     * ServiceLoader pattern can obtain an instance without knowing internal
     * class names.
     */
    provides net.pack200.Pack200.Unpacker
        with au.net.zeus.util.jar.pack.UnpackerImpl;
}
