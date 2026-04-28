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
package xmlkit; // -*- mode: java; indent-tabs-mode: nil -*-

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.TypeReference;
import xmlkit.XMLKit.Element;

import java.io.*;
import java.util.*;
import java.util.jar.*;

/*
 * ASM-based class file reader that produces XMLKit.Element trees.
 * Uses org.ow2.asm:asm 9.x – no internal JDK APIs, no --add-exports.
 *
 * Replaces ClassReader (com.sun.tools.classfile), ConstantPoolVisitor,
 * AttributeVisitor, StackMapVisitor, InstructionVisitor, and
 * AnnotationsElementVisitor.
 *
 * @author jrose, ksrini (original); rewritten for ASM 9.x
 */

// ========================================================================
// XmlClassVisitor
// ========================================================================

class XmlClassVisitor extends ClassVisitor {

    final ClassReader x;
    Element klass;

    // Pending type-annotation containers (lazy-create per visible/invisible)
    private Element rvTypeAnnotations;
    private Element riTypeAnnotations;

    // Pending NestMembers, PermittedSubclasses (collect into one element each)
    private Element nestMembersElement;
    private Element permittedSubclassesElement;

    XmlClassVisitor(ClassReader x) {
        super(Opcodes.ASM9);
        this.x = x;
    }

    @Override
    public void visit(int version, int access, String name, String signature,
                      String superName, String[] interfaces) {
        int minor = (version >>> 16) & 0xFFFF;
        int major = version & 0xFFFF;
        x.cfile.setAttr("minver", "" + minor);
        x.cfile.setAttr("majver", "" + major);

        klass = new Element("Class");
        x.cfile.add(klass);
        klass.setAttr("name", name);
        klass.setAttr("flags", ClassReader.flagString(access, "Class"));
        if (!"java/lang/Object".equals(name) && superName != null) {
            klass.setAttr("super", superName);
        }
        if (interfaces != null) {
            for (String iface : interfaces) {
                Element ie = new Element("Interface");
                ie.setAttr("name", iface);
                klass.add(ie);
            }
        }
        if (signature != null) {
            Element se = new Element("Signature");
            se.add(signature);
            klass.add(se);
        }
        if ((access & Opcodes.ACC_DEPRECATED) != 0) {
            klass.add(new Element("Deprecated"));
        }
    }

    @Override
    public void visitSource(String source, String debug) {
        if (source != null) {
            Element se = new Element("SourceFile");
            se.add(source);
            klass.add(se);
        }
        if (debug != null) {
            Element de = new Element("SourceDebugExtension");
            de.setAttr("val", debug);
            klass.add(de);
        }
    }

    @Override
    public ModuleVisitor visitModule(String name, int access, String version) {
        Element mod = new Element("Module");
        klass.add(mod);
        return new XmlModuleVisitor(mod);
    }

    @Override
    public void visitNestHost(String nestHost) {
        Element e = new Element("NestHost");
        e.add(nestHost);
        klass.add(e);
    }

    @Override
    public void visitOuterClass(String owner, String name, String descriptor) {
        Element e = new Element("EnclosingMethod");
        e.setAttr("class", owner);
        String desc = (name != null) ? name + " " + descriptor : null;
        e.setAttr("desc", desc);
        klass.add(e);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        String containerName = visible ? "RuntimeVisibleAnnotations"
                                       : "RuntimeInvisibleAnnotations";
        Element container = new Element(containerName);
        klass.add(container);
        Element annotation = new Element("Annotation");
        annotation.setAttr("name", descriptor);
        container.add(annotation);
        return new XmlAnnotationBodyVisitor(annotation, true);
    }

    @Override
    public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
                                                  String descriptor, boolean visible) {
        Element container = visible ? getRvTypeAnnotations() : getRiTypeAnnotations();
        return addTypeAnnotation(container, typeRef, descriptor, true);
    }

    @Override
    public void visitAttribute(org.objectweb.asm.Attribute attribute) {
        Element e = new Element(attribute.type);
        klass.add(e);
    }

    @Override
    public void visitNestMember(String nestMember) {
        if (nestMembersElement == null) {
            nestMembersElement = new Element("NestMembers");
            klass.add(nestMembersElement);
        }
        Element item = new Element("Item");
        item.setAttr("class", nestMember);
        nestMembersElement.add(item);
    }

    @Override
    public void visitPermittedSubclass(String permittedSubclass) {
        if (permittedSubclassesElement == null) {
            permittedSubclassesElement = new Element("PermittedSubclasses");
            klass.add(permittedSubclassesElement);
        }
        Element item = new Element("Item");
        item.setAttr("class", permittedSubclass);
        permittedSubclassesElement.add(item);
    }

    @Override
    public void visitInnerClass(String name, String outerName, String innerName, int access) {
        // Original emits one <InnerClasses> element per entry (no shared container)
        Element e = new Element("InnerClasses");
        e.setAttr("class", name);
        e.setAttr("outer", outerName);
        e.setAttr("name", innerName);
        e.setAttr("flags", ClassReader.flagString(access, "InnerClass"));
        klass.add(e);
    }

    @Override
    public RecordComponentVisitor visitRecordComponent(String name, String descriptor,
                                                        String signature) {
        if (!klass.contains("Record")) {
            // Create Record element lazily
        }
        // Find or create the Record element
        Element recordElement = null;
        for (int i = 0; i < klass.size(); i++) {
            Object child = klass.get(i);
            if (child instanceof Element && "Record".equals(((Element) child).getName())) {
                recordElement = (Element) child;
                break;
            }
        }
        if (recordElement == null) {
            recordElement = new Element("Record");
            klass.add(recordElement);
        }
        Element comp = new Element("Component");
        comp.setAttr("name", name);
        comp.setAttr("descriptor", descriptor);
        recordElement.add(comp);
        // RecordComponentVisitor can handle annotations on components
        return new XmlRecordComponentVisitor(comp);
    }

    @Override
    public FieldVisitor visitField(int access, String name, String descriptor,
                                   String signature, Object value) {
        Element field = new Element("Field");
        field.setAttr("name", name);
        field.setAttr("type", descriptor);
        field.setAttr("flags", ClassReader.flagString(access, "Field"));
        if (value != null) {
            Element cv = new Element("ConstantValue");
            cv.add(ClassReader.ldcArgToString(value));
            field.add(cv);
        }
        if (signature != null) {
            Element se = new Element("Signature");
            se.add(signature);
            field.add(se);
        }
        if ((access & Opcodes.ACC_DEPRECATED) != 0) {
            field.add(new Element("Deprecated"));
        }
        if (!x.keepOrder) {
            // fields will be sorted later - for now just add
        }
        klass.add(field);
        return new XmlFieldVisitor(x, field);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        Element method = new Element("Method");
        method.setAttr("name", name);
        method.setAttr("type", descriptor);
        method.setAttr("flags", ClassReader.flagString(access, "Method"));
        if (signature != null) {
            Element se = new Element("Signature");
            se.add(signature);
            method.add(se);
        }
        if (exceptions != null && exceptions.length > 0) {
            Element ee = new Element("Exceptions");
            for (String exc : exceptions) {
                Element item = new Element("Item");
                item.setAttr("class", exc);
                ee.add(item);
            }
            method.add(ee);
        }
        if ((access & Opcodes.ACC_DEPRECATED) != 0) {
            method.add(new Element("Deprecated"));
        }
        klass.add(method);
        return new XmlMethodVisitor(x, method);
    }

    @Override
    public void visitEnd() {
        klass.trimToSize();
    }

    // ---- helpers ----

    private Element getRvTypeAnnotations() {
        if (rvTypeAnnotations == null) {
            rvTypeAnnotations = new Element("RuntimeVisibleTypeAnnotations");
            klass.add(rvTypeAnnotations);
        }
        return rvTypeAnnotations;
    }

    private Element getRiTypeAnnotations() {
        if (riTypeAnnotations == null) {
            riTypeAnnotations = new Element("RuntimeInvisibleTypeAnnotations");
            klass.add(riTypeAnnotations);
        }
        return riTypeAnnotations;
    }

    // Shared helper for type annotations
    static AnnotationVisitor addTypeAnnotation(Element container, int typeRef,
                                                String descriptor, boolean outer) {
        Element pta = new Element("RuntimeVisibleTypeAnnotation");
        container.add(pta);
        buildPositionElement(typeRef, null, null, null, null, pta);
        Element annotation = new Element("Annotation");
        annotation.setAttr("name", descriptor);
        pta.add(annotation);
        return new XmlAnnotationBodyVisitor(annotation, true);
    }

    static void buildPositionElement(int typeRef, Label[] start, Label[] end,
                                      int[] index, String descriptor, Element parent) {
        TypeReference ref = new TypeReference(typeRef);
        int sort = ref.getSort();
        Element te = new Element();
        switch (sort) {
            case TypeReference.CLASS_TYPE_PARAMETER:
                te.setName("CLASS_TYPE_PARAMETER");
                te.setAttr("idx", "" + ref.getTypeParameterIndex());
                break;
            case TypeReference.METHOD_TYPE_PARAMETER:
                te.setName("METHOD_TYPE_PARAMETER");
                te.setAttr("idx", "" + ref.getTypeParameterIndex());
                break;
            case TypeReference.CLASS_EXTENDS:
                te.setName("CLASS_EXTENDS");
                te.setAttr("idx", "" + ref.getSuperTypeIndex());
                break;
            case TypeReference.CLASS_TYPE_PARAMETER_BOUND:
                te.setName("CLASS_TYPE_PARAMETER_BOUND");
                te.setAttr("idx1", "" + ref.getTypeParameterIndex());
                te.setAttr("idx2", "" + ref.getTypeParameterBoundIndex());
                break;
            case TypeReference.METHOD_TYPE_PARAMETER_BOUND:
                te.setName("METHOD_TYPE_PARAMETER_BOUND");
                te.setAttr("idx1", "" + ref.getTypeParameterIndex());
                te.setAttr("idx2", "" + ref.getTypeParameterBoundIndex());
                break;
            case TypeReference.FIELD:
                te.setName("FIELD");
                break;
            case TypeReference.METHOD_RETURN:
                te.setName("METHOD_RETURN");
                break;
            case TypeReference.METHOD_RECEIVER:
                te.setName("METHOD_RECEIVER");
                break;
            case TypeReference.METHOD_FORMAL_PARAMETER:
                te.setName("METHOD_FORMAL_PARAMETER");
                te.setAttr("idx", "" + ref.getFormalParameterIndex());
                break;
            case TypeReference.THROWS:
                te.setName("THROWS");
                te.setAttr("idx", "" + ref.getExceptionIndex());
                break;
            case TypeReference.LOCAL_VARIABLE:
            case TypeReference.RESOURCE_VARIABLE:
                te.setName(sort == TypeReference.LOCAL_VARIABLE
                           ? "LOCAL_VARIABLE" : "RESOURCE_VARIABLE");
                // lvar labels are resolved by caller (XmlMethodVisitor) when needed
                break;
            case TypeReference.EXCEPTION_PARAMETER:
                te.setName("EXCEPTION_PARAMETER");
                te.setAttr("idx", "" + ref.getTryCatchBlockIndex());
                break;
            case TypeReference.INSTANCEOF:
                te.setName("INSTANCE_OF");
                te.setAttr("off", "" + ((typeRef >> 8) & 0xFFFF));
                break;
            case TypeReference.NEW:
                te.setName("NEW");
                te.setAttr("off", "" + ((typeRef >> 8) & 0xFFFF));
                break;
            case TypeReference.CONSTRUCTOR_REFERENCE:
                te.setName("CONSTRUCTOR_REFERENCE_RECEIVER");
                te.setAttr("off", "" + ((typeRef >> 8) & 0xFFFF));
                break;
            case TypeReference.METHOD_REFERENCE:
                te.setName("METHOD_REFERENCE_RECEIVER");
                te.setAttr("off", "" + ((typeRef >> 8) & 0xFFFF));
                break;
            case TypeReference.CAST:
                te.setName("CAST");
                te.setAttr("off", "" + ((typeRef >> 8) & 0xFFFF));
                te.setAttr("idx", "" + (typeRef & 0xFF));
                break;
            case TypeReference.CONSTRUCTOR_INVOCATION_TYPE_ARGUMENT:
                te.setName("CONSTRUCTOR_INVOCATION_TYPE_ARGUMENT");
                te.setAttr("off", "" + ((typeRef >> 8) & 0xFFFF));
                te.setAttr("idx", "" + (typeRef & 0xFF));
                break;
            case TypeReference.METHOD_INVOCATION_TYPE_ARGUMENT:
                te.setName("METHOD_INVOCATION_TYPE_ARGUMENT");
                te.setAttr("off", "" + ((typeRef >> 8) & 0xFFFF));
                te.setAttr("idx", "" + (typeRef & 0xFF));
                break;
            case TypeReference.CONSTRUCTOR_REFERENCE_TYPE_ARGUMENT:
                te.setName("CONSTRUCTOR_REFERENCE_TYPE_ARGUMENT");
                te.setAttr("off", "" + ((typeRef >> 8) & 0xFFFF));
                te.setAttr("idx", "" + (typeRef & 0xFF));
                break;
            case TypeReference.METHOD_REFERENCE_TYPE_ARGUMENT:
                te.setName("METHOD_REFERENCE_TYPE_ARGUMENT");
                te.setAttr("off", "" + ((typeRef >> 8) & 0xFFFF));
                te.setAttr("idx", "" + (typeRef & 0xFF));
                break;
            default:
                te.setName("UNKNOWN_TARGET");
                te.setAttr("sort", Integer.toHexString(sort));
                break;
        }
        te.trimToSize();
        parent.add(te);
    }
}

// ========================================================================
// XmlFieldVisitor
// ========================================================================

class XmlFieldVisitor extends FieldVisitor {

    final ClassReader x;
    final Element field;

    XmlFieldVisitor(ClassReader x, Element field) {
        super(Opcodes.ASM9);
        this.x = x;
        this.field = field;
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        String containerName = visible ? "RuntimeVisibleAnnotations"
                                       : "RuntimeInvisibleAnnotations";
        Element container = new Element(containerName);
        field.add(container);
        Element annotation = new Element("Annotation");
        annotation.setAttr("name", descriptor);
        container.add(annotation);
        return new XmlAnnotationBodyVisitor(annotation, true);
    }

    @Override
    public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
                                                  String descriptor, boolean visible) {
        String containerName = visible ? "RuntimeVisibleTypeAnnotations"
                                       : "RuntimeInvisibleTypeAnnotations";
        Element container = new Element(containerName);
        field.add(container);
        return XmlClassVisitor.addTypeAnnotation(container, typeRef, descriptor, true);
    }

    @Override
    public void visitAttribute(org.objectweb.asm.Attribute attribute) {
        Element e = new Element(attribute.type);
        field.add(e);
    }

    @Override
    public void visitEnd() {
        field.trimToSize();
    }
}

// ========================================================================
// XmlMethodVisitor
// ========================================================================

class XmlMethodVisitor extends MethodVisitor {

    final ClassReader x;
    final Element method;

    // Code-level state
    private Element codeElement;
    private final List<Element> instrList = new ArrayList<>();
    private final List<Element> handlerList = new ArrayList<>();

    // Nested-Code attributes collected during instruction walk
    private Element rvCodeTypeAnnotations;
    private Element riCodeTypeAnnotations;

    // Pending LineNumberTable / LocalVariableTable / LocalVariableTypeTable
    // (collected to emit as individual elements, matching original format)
    private final List<Element> lineNumbers = new ArrayList<>();
    private final List<Element> localVars = new ArrayList<>();
    private final List<Element> localVarTypes = new ArrayList<>();

    // Pending parameter annotations (visible/invisible)
    private final List<Element> rvParamAnnotations = new ArrayList<>();
    private final List<Element> riParamAnnotations = new ArrayList<>();

    // Label tracking: ASM labels in read-mode do not have FLAG_RESOLVED set.
    // We assign sequential IDs to labels as they are first encountered.
    // Forward references (jump to a label not yet visited) are deferred.
    private int labelSeq = 0;
    private final IdentityHashMap<Label, Integer> labelIds = new IdentityHashMap<>();
    // pendingRefs: {element, attrName, label} for forward-reference resolution
    private final List<Object[]> pendingRefs = new ArrayList<>();

    // Assign (or return existing) sequential ID for a label
    private int getLabelId(Label label) {
        Integer id = labelIds.get(label);
        if (id == null) {
            id = labelSeq++;
            labelIds.put(label, id);
        }
        return id;
    }

    // Set a label-referencing attribute; defers if label not yet seen
    private void setLabelAttr(Element e, String attr, Label label) {
        Integer id = labelIds.get(label);
        if (id != null) {
            e.setAttr(attr, "" + id);
        } else {
            e.setAttr(attr, "-1"); // placeholder
            pendingRefs.add(new Object[]{e, attr, label});
        }
    }

    XmlMethodVisitor(ClassReader x, Element method) {
        super(Opcodes.ASM9);
        this.x = x;
        this.method = method;
    }

    @Override
    public void visitParameter(String name, int access) {
        Element e = new Element("MethodParameters");
        e.setAttr("name", name);
        e.setAttr("flag", "" + access);
        method.add(e);
    }

    @Override
    public AnnotationVisitor visitAnnotationDefault() {
        Element defaultElement = new Element("AnnotationDefault");
        return new XmlAnnotationDefaultVisitor(method, defaultElement);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        String containerName = visible ? "RuntimeVisibleAnnotations"
                                       : "RuntimeInvisibleAnnotations";
        Element container = new Element(containerName);
        method.add(container);
        Element annotation = new Element("Annotation");
        annotation.setAttr("name", descriptor);
        container.add(annotation);
        return new XmlAnnotationBodyVisitor(annotation, true);
    }

    @Override
    public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
                                                  String descriptor, boolean visible) {
        String containerName = visible ? "RuntimeVisibleTypeAnnotations"
                                       : "RuntimeInvisibleTypeAnnotations";
        Element container = new Element(containerName);
        method.add(container);
        return XmlClassVisitor.addTypeAnnotation(container, typeRef, descriptor, true);
    }

    @Override
    public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor,
                                                       boolean visible) {
        // Collect into single container (no per-parameter grouping, matching original)
        if (visible) {
            if (rvParamAnnotations.isEmpty()) {
                rvParamAnnotations.add(new Element("RuntimeVisibleParameterAnnotations"));
            }
            Element container = rvParamAnnotations.get(0);
            Element annotation = new Element("Annotation");
            annotation.setAttr("name", descriptor);
            container.add(annotation);
            return new XmlAnnotationBodyVisitor(annotation, true);
        } else {
            if (riParamAnnotations.isEmpty()) {
                riParamAnnotations.add(new Element("RuntimeInvisibleParameterAnnotations"));
            }
            Element container = riParamAnnotations.get(0);
            Element annotation = new Element("Annotation");
            annotation.setAttr("name", descriptor);
            container.add(annotation);
            return new XmlAnnotationBodyVisitor(annotation, true);
        }
    }

    @Override
    public void visitAttribute(org.objectweb.asm.Attribute attribute) {
        Element e = new Element(attribute.type);
        method.add(e);
    }

    @Override
    public void visitCode() {
        codeElement = new Element("Code");
        // stack and local will be set in visitMaxs
    }

    @Override
    public void visitFrame(int type, int nLocal, Object[] local,
                           int nStack, Object[] stack) {
        // With EXPAND_FRAMES, all frames are delivered as F_NEW (full frames)
        Element frame = new Element("FullFrame");
        if (local != null) {
            for (int i = 0; i < nLocal; i++) {
                Element kindE = new Element("Local");
                kindE.setAttr("tag", "" + ClassReader.verificationTypeTag(local[i]));
                kindE.add(ClassReader.verificationTypeElement(local[i]));
                frame.add(kindE);
            }
        }
        if (stack != null) {
            for (int i = 0; i < nStack; i++) {
                Element kindE = new Element("Stack");
                kindE.setAttr("tag", "" + ClassReader.verificationTypeTag(stack[i]));
                kindE.add(ClassReader.verificationTypeElement(stack[i]));
                frame.add(kindE);
            }
        }
        frame.trimToSize();
        instrList.add(frame);
    }

    @Override
    public void visitInsn(int opcode) {
        instrList.add(ClassReader.simpleInsn(opcode));
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
        Element ie = new Element(ClassReader.OPCODE_NAMES[opcode]);
        if (opcode == Opcodes.NEWARRAY) {
            ie.setAttr("num", "" + operand);
            String typeName = (operand >= 4 && operand <= 11)
                              ? ClassReader.NEWARRAY_TYPES[operand] : "unknown";
            ie.setAttr("val", typeName);
        } else {
            // bipush, sipush
            ie.setAttr("num", "" + operand);
        }
        ie.trimToSize();
        instrList.add(ie);
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
        Element ie = new Element(ClassReader.OPCODE_NAMES[opcode]);
        ie.setAttr("loc", "" + var);
        ie.trimToSize();
        instrList.add(ie);
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        Element ie = new Element(ClassReader.OPCODE_NAMES[opcode]);
        ie.setAttr("ref", type);
        ie.trimToSize();
        instrList.add(ie);
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
        Element ie = new Element(ClassReader.OPCODE_NAMES[opcode]);
        ie.setAttr("ref", owner + " " + name + " " + descriptor);
        ie.trimToSize();
        instrList.add(ie);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name,
                                 String descriptor, boolean isInterface) {
        Element ie = new Element(ClassReader.OPCODE_NAMES[opcode]);
        ie.setAttr("ref", owner + " " + name + " " + descriptor);
        ie.trimToSize();
        instrList.add(ie);
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsm,
                                        Object... bsmArgs) {
        Element ie = new Element("invokedynamic");
        // Build ref string: "REF_kind owner name desc[,arg1,arg2...] name desc"
        StringBuilder bsmStr = new StringBuilder(ClassReader.handleToString(bsm));
        for (Object arg : bsmArgs) {
            bsmStr.append(",").append(ClassReader.ldcArgToString(arg));
        }
        ie.setAttr("ref", bsmStr + " " + name + " " + descriptor);
        ie.trimToSize();
        instrList.add(ie);
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        Element ie = new Element(ClassReader.OPCODE_NAMES[opcode]);
        setLabelAttr(ie, "lab", label);
        ie.trimToSize();
        instrList.add(ie);
    }

    @Override
    public void visitLabel(Label label) {
        // Assign sequential ID to this label position (resolves pending forward refs)
        int id = getLabelId(label);
        // Resolve any pending forward references to this label
        if (!pendingRefs.isEmpty()) {
            Iterator<Object[]> iter = pendingRefs.iterator();
            while (iter.hasNext()) {
                Object[] ref = iter.next();
                if (ref[2] == label) {
                    ((Element) ref[0]).setAttr((String) ref[1], "" + id);
                    iter.remove();
                }
            }
        }
    }

    @Override
    public void visitLdcInsn(Object value) {
        Element ie = new Element("ldc");
        ie.setAttr("ref", ClassReader.ldcArgToString(value));
        ie.trimToSize();
        instrList.add(ie);
    }

    @Override
    public void visitIincInsn(int var, int increment) {
        Element ie = new Element("iinc");
        ie.setAttr("loc", "" + var);
        ie.setAttr("num", "" + increment);
        ie.trimToSize();
        instrList.add(ie);
    }

    @Override
    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... cases) {
        Element ie = new Element("tableswitch");
        setLabelAttr(ie, "lab", dflt);
        for (int i = 0; i < cases.length; i++) {
            Element c = new Element("Case");
            c.setAttr("num", "" + (min + i));
            setLabelAttr(c, "lab", cases[i]);
            c.trimToSize();
            ie.add(c);
        }
        ie.trimToSize();
        instrList.add(ie);
    }

    @Override
    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
        Element ie = new Element("lookupswitch");
        setLabelAttr(ie, "lab", dflt);
        for (int k = 0; k < keys.length; k++) {
            Element c = new Element("Case");
            c.setAttr("num", "" + keys[k]);
            setLabelAttr(c, "lab", labels[k]);
            c.trimToSize();
            ie.add(c);
        }
        ie.trimToSize();
        instrList.add(ie);
    }

    @Override
    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
        Element ie = new Element("multianewarray");
        ie.setAttr("ref", descriptor);
        ie.setAttr("val", "" + numDimensions);
        ie.trimToSize();
        instrList.add(ie);
    }

    @Override
    public AnnotationVisitor visitInsnAnnotation(int typeRef, TypePath typePath,
                                                  String descriptor, boolean visible) {
        Element container = visible ? getRvCodeTypeAnnotations()
                                    : getRiCodeTypeAnnotations();
        return XmlClassVisitor.addTypeAnnotation(container, typeRef, descriptor, true);
    }

    @Override
    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
        Element h = new Element("Handler");
        setLabelAttr(h, "start", start);
        setLabelAttr(h, "end", end);
        setLabelAttr(h, "catch", handler);
        h.setAttr("class", type); // null for finally (catch-all)
        handlerList.add(h);
    }

    @Override
    public AnnotationVisitor visitTryCatchAnnotation(int typeRef, TypePath typePath,
                                                      String descriptor, boolean visible) {
        Element container = visible ? getRvCodeTypeAnnotations()
                                    : getRiCodeTypeAnnotations();
        return XmlClassVisitor.addTypeAnnotation(container, typeRef, descriptor, true);
    }

    @Override
    public void visitLocalVariable(String name, String descriptor, String signature,
                                    Label start, Label end, int index) {
        int startId = getLabelId(start);
        int endId = getLabelId(end);
        Element l = new Element("LocalVariableTable");
        l.setAttr("bci", "" + startId);
        l.setAttr("span", "" + (endId - startId));
        l.setAttr("name", name);
        l.setAttr("type", descriptor);
        l.setAttr("slot", "" + index);
        localVars.add(l);
        if (signature != null) {
            Element lt = new Element("LocalVariableTypeTable");
            lt.setAttr("bci", "" + startId);
            lt.setAttr("span", "" + (endId - startId));
            lt.setAttr("name", name);
            lt.setAttr("type", signature);
            lt.setAttr("slot", "" + index);
            localVarTypes.add(lt);
        }
    }

    @Override
    public AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath typePath,
                                                           Label[] start, Label[] end,
                                                           int[] index, String descriptor,
                                                           boolean visible) {
        Element container = visible ? getRvCodeTypeAnnotations()
                                    : getRiCodeTypeAnnotations();
        Element pta = new Element("RuntimeVisibleTypeAnnotation");
        container.add(pta);
        // Build the type element inline, resolving lvar labels via getLabelId
        TypeReference ref = new TypeReference(typeRef);
        int sort = ref.getSort();
        Element te = new Element("Target");
        if (sort == TypeReference.LOCAL_VARIABLE || sort == TypeReference.RESOURCE_VARIABLE) {
            te.setName(sort == TypeReference.LOCAL_VARIABLE ? "LOCAL_VARIABLE" : "RESOURCE_VARIABLE");
            if (start != null) {
                for (int i = 0; i < start.length; i++) {
                    te.setAttr("lvar_idx_" + i, "" + index[i]);
                    int startId = getLabelId(start[i]);
                    int endId = getLabelId(end[i]);
                    te.setAttr("lvar_len_" + i, "" + (endId - startId));
                    te.setAttr("lvar_off_" + i, "" + startId);
                }
            }
        } else {
            XmlClassVisitor.buildPositionElement(typeRef, null, null, null, descriptor, pta);
            pta.remove(te); // undo the extra Target added by buildPositionElement
        }
        pta.add(te);
        Element annotation = new Element("Annotation");
        annotation.setAttr("name", descriptor);
        pta.add(annotation);
        return new XmlAnnotationBodyVisitor(annotation, true);
    }

    @Override
    public void visitLineNumber(int line, Label start) {
        Element l = new Element("LineNumberTable");
        l.setAttr("bci", "" + getLabelId(start));
        l.setAttr("line", "" + line);
        lineNumbers.add(l);
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        if (codeElement != null) {
            codeElement.setAttr("stack", "" + maxStack);
            codeElement.setAttr("local", "" + maxLocals);
        }
    }

    @Override
    public void visitEnd() {
        // Resolve any remaining forward label references (shouldn't normally happen)
        for (Object[] ref : pendingRefs) {
            Label label = (Label) ref[2];
            ((Element) ref[0]).setAttr((String) ref[1], "" + getLabelId(label));
        }
        pendingRefs.clear();

        // Add parameter annotations to method
        for (Element e : rvParamAnnotations) { method.add(e); }
        for (Element e : riParamAnnotations) { method.add(e); }

        if (codeElement != null) {
            // Build Instructions element
            Element insnsElement = new Element("Instructions");
            for (Element instr : instrList) {
                insnsElement.add(instr);
            }
            insnsElement.trimToSize();
            codeElement.add(insnsElement);

            // Add exception handlers
            for (Element h : handlerList) {
                codeElement.add(h);
            }

            // Add nested Code attributes (StackMapTable is already in instrList as frames)
            for (Element l : lineNumbers) { codeElement.add(l); }
            for (Element l : localVars) { codeElement.add(l); }
            for (Element l : localVarTypes) { codeElement.add(l); }

            if (rvCodeTypeAnnotations != null) { codeElement.add(rvCodeTypeAnnotations); }
            if (riCodeTypeAnnotations != null) { codeElement.add(riCodeTypeAnnotations); }

            codeElement.trimToSize();
            method.add(codeElement);
        }
        method.trimToSize();
    }

    private Element getRvCodeTypeAnnotations() {
        if (rvCodeTypeAnnotations == null) {
            rvCodeTypeAnnotations = new Element("RuntimeVisibleTypeAnnotations");
        }
        return rvCodeTypeAnnotations;
    }

    private Element getRiCodeTypeAnnotations() {
        if (riCodeTypeAnnotations == null) {
            riCodeTypeAnnotations = new Element("RuntimeInvisibleTypeAnnotations");
        }
        return riCodeTypeAnnotations;
    }
}

// ========================================================================
// XmlModuleVisitor
// ========================================================================

class XmlModuleVisitor extends ModuleVisitor {

    final Element module;
    // Module provides: replicate original bug – one <Provides> element, attrs overwritten
    private Element providesElement;

    XmlModuleVisitor(Element module) {
        super(Opcodes.ASM9);
        this.module = module;
    }

    @Override
    public void visitRequire(String aModule, int access, String version) {
        Element er = new Element("Requires");
        er.setAttr("module", aModule);
        er.setAttr("flags", Integer.toString(access));
        module.add(er);
    }

    @Override
    public void visitExport(String packaze, int access, String... modules) {
        // Original: one <Exports> container; inner <exports> with package + last module
        // Find or create Exports container
        Element exportsContainer = null;
        for (int i = 0; i < module.size(); i++) {
            Object child = module.get(i);
            if (child instanceof Element && "Exports".equals(((Element) child).getName())) {
                exportsContainer = (Element) child;
                break;
            }
        }
        if (exportsContainer == null) {
            exportsContainer = new Element("Exports");
            module.add(exportsContainer);
        }
        Element exto = new Element("exports");
        exto.setAttr("package", packaze);
        if (modules != null) {
            for (String mod : modules) {
                exto.setAttr("module", mod); // overwrites – matches original bug
            }
        }
        exportsContainer.add(exto);
    }

    @Override
    public void visitOpen(String packaze, int access, String... modules) {
        // Similar to exports but "opens" – treat as unknown attribute (not in original)
    }

    @Override
    public void visitUse(String service) {
        Element ei = new Element("Uses");
        ei.setAttr("used_class", service);
        module.add(ei);
    }

    @Override
    public void visitProvide(String service, String... providers) {
        // Original: ONE <Provides> element; attrs overwritten by last entry
        if (providesElement == null) {
            providesElement = new Element("Provides");
            module.add(providesElement);
        }
        providesElement.setAttr("provides", service);
        if (providers != null) {
            for (String p : providers) {
                providesElement.setAttr("with", p); // overwrites – matches original bug
            }
        }
    }

    @Override
    public void visitEnd() {
        module.trimToSize();
    }
}

// ========================================================================
// XmlRecordComponentVisitor
// ========================================================================

class XmlRecordComponentVisitor extends RecordComponentVisitor {

    final Element component;

    XmlRecordComponentVisitor(Element component) {
        super(Opcodes.ASM9);
        this.component = component;
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        Element container = new Element(visible ? "RuntimeVisibleAnnotations"
                                                : "RuntimeInvisibleAnnotations");
        component.add(container);
        Element annotation = new Element("Annotation");
        annotation.setAttr("name", descriptor);
        container.add(annotation);
        return new XmlAnnotationBodyVisitor(annotation, true);
    }

    @Override
    public void visitEnd() {
        component.trimToSize();
    }
}

// ========================================================================
// XmlAnnotationBodyVisitor – builds the contents of an <Annotation> element.
//
//   outer=true  : each element pair is wrapped in <Element tag="..." value="...">
//   outer=false : element values are added directly (no wrapper) – for nested
//                 annotations and arrays used as element values
// ========================================================================

class XmlAnnotationBodyVisitor extends AnnotationVisitor {

    final Element body; // element being populated (Annotation or Array)
    final boolean outer;

    XmlAnnotationBodyVisitor(Element body, boolean outer) {
        super(Opcodes.ASM9);
        this.body = body;
        this.outer = outer;
    }

    @Override
    public void visit(String name, Object value) {
        Element child;
        if (value instanceof Type) {
            child = new Element("Class");
            Type t = (Type) value;
            child.setAttr("name", t.getSort() == Type.METHOD
                                  ? t.getDescriptor() : t.getInternalName());
        } else {
            child = new Element("String");
            child.setAttr("val", value.toString());
        }
        child.trimToSize();
        if (outer) {
            Element wrapper = new Element("Element");
            wrapper.setAttr("tag", "" + inferTag(value));
            if (name != null) wrapper.setAttr("value", name);
            wrapper.add(child);
            body.add(wrapper);
        } else {
            body.add(child);
        }
    }

    @Override
    public void visitEnum(String name, String descriptor, String value) {
        Element child = new Element("Enum");
        child.setAttr("name", value);
        child.setAttr("type", descriptor);
        child.trimToSize();
        if (outer) {
            Element wrapper = new Element("Element");
            wrapper.setAttr("tag", "e");
            if (name != null) wrapper.setAttr("value", name);
            wrapper.add(child);
            body.add(wrapper);
        } else {
            body.add(child);
        }
    }

    @Override
    public AnnotationVisitor visitAnnotation(String name, String descriptor) {
        Element innerAnnot = new Element("Annotation");
        if (outer) {
            Element wrapper = new Element("Element");
            wrapper.setAttr("tag", "@");
            if (name != null) wrapper.setAttr("value", name);
            wrapper.add(innerAnnot);
            body.add(wrapper);
        } else {
            body.add(innerAnnot);
        }
        return new XmlAnnotationBodyVisitor(innerAnnot, false);
    }

    @Override
    public AnnotationVisitor visitArray(String name) {
        Element arr = new Element("Array");
        if (outer) {
            Element wrapper = new Element("Element");
            wrapper.setAttr("tag", "[");
            if (name != null) wrapper.setAttr("value", name);
            wrapper.add(arr);
            body.add(wrapper);
        } else {
            body.add(arr);
        }
        return new XmlAnnotationBodyVisitor(arr, false);
    }

    @Override
    public void visitEnd() {
        body.trimToSize();
    }

    private static char inferTag(Object v) {
        if (v instanceof Integer) return 'I';
        if (v instanceof Long)    return 'J';
        if (v instanceof Float)   return 'F';
        if (v instanceof Double)  return 'D';
        if (v instanceof String)  return 's';
        if (v instanceof Type)    return 'c';
        return 'I';
    }
}

// ========================================================================
// XmlAnnotationDefaultVisitor – handles the AnnotationDefault attribute.
// ========================================================================

class XmlAnnotationDefaultVisitor extends AnnotationVisitor {

    final Element method;
    final Element defaultElement;

    XmlAnnotationDefaultVisitor(Element method, Element defaultElement) {
        super(Opcodes.ASM9);
        this.method = method;
        this.defaultElement = defaultElement;
    }

    @Override
    public void visit(String name, Object value) {
        defaultElement.setAttr("tag", "" + inferTag(value));
        Element child;
        if (value instanceof Type) {
            child = new Element("Class");
            Type t = (Type) value;
            child.setAttr("name", t.getSort() == Type.METHOD
                                  ? t.getDescriptor() : t.getInternalName());
        } else {
            child = new Element("String");
            child.setAttr("val", value.toString());
        }
        child.trimToSize();
        defaultElement.add(child);
    }

    @Override
    public void visitEnum(String name, String descriptor, String value) {
        defaultElement.setAttr("tag", "e");
        Element child = new Element("Enum");
        child.setAttr("name", value);
        child.setAttr("type", descriptor);
        child.trimToSize();
        defaultElement.add(child);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String name, String descriptor) {
        defaultElement.setAttr("tag", "@");
        Element inner = new Element("Annotation");
        defaultElement.add(inner);
        return new XmlAnnotationBodyVisitor(inner, false);
    }

    @Override
    public AnnotationVisitor visitArray(String name) {
        defaultElement.setAttr("tag", "[");
        Element arr = new Element("Array");
        defaultElement.add(arr);
        return new XmlAnnotationBodyVisitor(arr, false);
    }

    @Override
    public void visitEnd() {
        defaultElement.trimToSize();
        method.add(defaultElement);
    }

    private static char inferTag(Object v) {
        if (v instanceof Integer) return 'I';
        if (v instanceof Long)    return 'J';
        if (v instanceof Float)   return 'F';
        if (v instanceof Double)  return 'D';
        if (v instanceof String)  return 's';
        if (v instanceof Type)    return 'c';
        return 'I';
    }
}
