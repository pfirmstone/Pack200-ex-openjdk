/*
 * Copyright (c) 2010, 2024, Oracle and/or its affiliates. All rights reserved.
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
package xmlkit;

/**
 * Concrete AttributeVisitor for JDK versions older than 15, where
 * PermittedSubclasses_attribute and Record_attribute do not exist
 * in com.sun.tools.classfile, and where Attribute.Visitor does not
 * declare visitRecord() or visitPermittedSubclasses() as abstract methods.
 *
 * This class is excluded from compilation on JDK 15+ (see Utils.java
 * and build.xml). On those newer JDKs, ModernAttributeVisitor is used instead.
 */
class LegacyAttributeVisitor extends AttributeVisitor {

    LegacyAttributeVisitor(ClassReader x, com.sun.tools.classfile.ClassFile cf) {
        super(x, cf);
    }
}
