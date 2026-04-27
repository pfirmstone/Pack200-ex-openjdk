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

import com.sun.tools.classfile.ClassFile;
import com.sun.tools.classfile.PermittedSubclasses_attribute;
import com.sun.tools.classfile.Record_attribute;
import xmlkit.XMLKit.Element;

/**
 * Concrete AttributeVisitor for JDK 15+, where PermittedSubclasses_attribute
 * and Record_attribute exist in com.sun.tools.classfile, and where
 * Attribute.Visitor declares visitRecord() and visitPermittedSubclasses()
 * as abstract methods that must be implemented.
 *
 * This class is excluded from compilation on JDK versions older than 15
 * (see Utils.java and build.xml). On those older JDKs, LegacyAttributeVisitor
 * is used instead.
 */
class ModernAttributeVisitor extends AttributeVisitor {

    ModernAttributeVisitor(ClassReader x, ClassFile cf) {
        super(x, cf);
    }

    @Override
    public Element visitPermittedSubclasses(PermittedSubclasses_attribute attr, Element p) {
        Element ee = new Element(x.getCpString(attr.attribute_name_index));
        for (int idx : attr.subtypes) {
            Element n = new Element("Item");
            n.setAttr("class", x.getCpString(idx));
            ee.add(n);
        }
        ee.trimToSize();
        p.add(ee);
        return null;
    }

    @Override
    public Element visitRecord(Record_attribute attr, Element p) {
        Element ee = new Element(x.getCpString(attr.attribute_name_index));
        try {
            for (Record_attribute.ComponentInfo ci : attr.component_info_arr) {
                Element comp = new Element("Component");
                comp.setAttr("name", x.getCpString(ci.name_index));
                comp.setAttr("descriptor", ci.descriptor.getValue(cf.constant_pool));
                ee.add(comp);
            }
        } catch (Exception e) {
            // ignore parse errors — treat as unknown
        }
        ee.trimToSize();
        p.add(ee);
        return null;
    }
}
