/*
 * Copyright (c) 2017, Oracle and/or its affiliates. All rights reserved.
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

/**
 * @test
 * @bug 8179645
 * @summary Verify Pack200 initialization with security manager
 * @run main/othervm/policy=policy SecurityTest
 */

import net.pack200.Pack200;

public class SecurityTest {
    public static void main(String... args) {
        // Check if Security Manager is allowed via the java.security.manager property.
        // When set to "disallow", setSecurityManager() will throw UnsupportedOperationException
        // (e.g. OpenJDK 17+). On JDK 8 and DirtyChai builds the property is null/unset,
        // meaning Security Manager is available.
        String secMgrProp = System.getProperty("java.security.manager");
        boolean securityManagerAllowed = !"disallow".equals(secMgrProp);

        if (securityManagerAllowed) {
            System.setSecurityManager(new SecurityManager());
        } else {
            System.out.println("Note: Security Manager is not allowed on this JVM, skipping setSecurityManager()");
        }

        Pack200.newPacker();
        Pack200.newUnpacker();
    }
}
