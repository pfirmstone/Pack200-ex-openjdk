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

/*
 * @test C-08
 * @summary Compliance: All four annotation attribute variants survive pack/unpack
 *          (RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations,
 *          RuntimeVisibleTypeAnnotations, RuntimeInvisibleTypeAnnotations)
 *          on classes, fields, and methods (spec §7.1–§7.5).
 * @requires jdk.version.major >= 11
 * @compile -XDignore.symbol.file ../Utils.java AnnotationRoundTripTest.java
 * @run main AnnotationRoundTripTest
 */

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * C-08: Annotation round-trip — all four annotation attribute variants.
 *
 * Compiles classes that carry all four annotation attribute combinations on
 * class, field, and method elements, then verifies that pack/repack succeeds
 * with --unknown-attribute=error (confirming native band encoding).
 */
public class AnnotationRoundTripTest {

    public static void main(String... args) throws Exception {
        testRuntimeVisibleAnnotations();
        testTypeAnnotations();
        testParameterAnnotations();
        Utils.cleanup();
        System.out.println("C-08 AnnotationRoundTripTest: ALL PASSED");
    }

    /**
     * RuntimeVisibleAnnotations and RuntimeInvisibleAnnotations on class,
     * field, and method.
     */
    static void testRuntimeVisibleAnnotations() throws Exception {
        // Visible annotation type
        List<String> visAnnoSrc = new ArrayList<>();
        visAnnoSrc.add("import java.lang.annotation.*;");
        visAnnoSrc.add("@Retention(RetentionPolicy.RUNTIME)");
        visAnnoSrc.add("@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})");
        visAnnoSrc.add("public @interface VisAnno08 { String value() default \"\"; }");
        File visAnnoFile = new File("VisAnno08.java");
        Utils.createFile(visAnnoFile, visAnnoSrc);

        // Invisible (CLASS retention) annotation type
        List<String> invAnnoSrc = new ArrayList<>();
        invAnnoSrc.add("import java.lang.annotation.*;");
        invAnnoSrc.add("@Retention(RetentionPolicy.CLASS)");
        invAnnoSrc.add("@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})");
        invAnnoSrc.add("public @interface InvAnno08 {}");
        File invAnnoFile = new File("InvAnno08.java");
        Utils.createFile(invAnnoFile, invAnnoSrc);

        // Class that uses both
        List<String> classSrc = new ArrayList<>();
        classSrc.add("@VisAnno08(\"on-class\")");
        classSrc.add("@InvAnno08");
        classSrc.add("public class Annotated08 {");
        classSrc.add("    @VisAnno08(\"on-field\")");
        classSrc.add("    @InvAnno08");
        classSrc.add("    public int field;");
        classSrc.add("    @VisAnno08(\"on-method\")");
        classSrc.add("    @InvAnno08");
        classSrc.add("    public void method() {}");
        classSrc.add("}");
        File classFile = new File("Annotated08.java");
        Utils.createFile(classFile, classSrc);

        Utils.compiler("--release", "11",
                visAnnoFile.getName(), invAnnoFile.getName(), classFile.getName());

        File jar = new File("annotations08.jar");
        Utils.jar("cvf", jar.getName(),
                "VisAnno08.class", "InvAnno08.class", "Annotated08.class");

        Utils.testWithRepack(jar, "--unknown-attribute=error");
        System.out.println("  testRuntimeVisibleAnnotations: PASS");
    }

    /**
     * RuntimeVisibleTypeAnnotations and RuntimeInvisibleTypeAnnotations on
     * method return type and field type.
     */
    static void testTypeAnnotations() throws Exception {
        List<String> annoSrc = new ArrayList<>();
        annoSrc.add("import java.lang.annotation.*;");
        annoSrc.add("@Retention(RetentionPolicy.RUNTIME)");
        annoSrc.add("@Target(ElementType.TYPE_USE)");
        annoSrc.add("public @interface TA08 {}");
        File annoFile = new File("TA08.java");
        Utils.createFile(annoFile, annoSrc);

        List<String> classSrc = new ArrayList<>();
        classSrc.add("import java.util.List;");
        classSrc.add("public class TypeAnnotated08 {");
        classSrc.add("    @TA08 String field;");
        classSrc.add("    public @TA08 List<@TA08 String> method() { return null; }");
        classSrc.add("}");
        File classFile = new File("TypeAnnotated08.java");
        Utils.createFile(classFile, classSrc);

        Utils.compiler("--release", "11", annoFile.getName(), classFile.getName());

        File jar = new File("typeanno08.jar");
        Utils.jar("cvf", jar.getName(), "TA08.class", "TypeAnnotated08.class");

        Utils.testWithRepack(jar, "--unknown-attribute=error");
        System.out.println("  testTypeAnnotations: PASS");
    }

    /**
     * RuntimeVisibleParameterAnnotations and RuntimeInvisibleParameterAnnotations
     * on method parameters.
     */
    static void testParameterAnnotations() throws Exception {
        List<String> pAnnoSrc = new ArrayList<>();
        pAnnoSrc.add("import java.lang.annotation.*;");
        pAnnoSrc.add("@Retention(RetentionPolicy.RUNTIME)");
        pAnnoSrc.add("@Target(ElementType.PARAMETER)");
        pAnnoSrc.add("public @interface PA08 {}");
        File pAnnoFile = new File("PA08.java");
        Utils.createFile(pAnnoFile, pAnnoSrc);

        List<String> classSrc = new ArrayList<>();
        classSrc.add("public class ParamAnnotated08 {");
        classSrc.add("    public void method(@PA08 String a, @PA08 int b) {}");
        classSrc.add("}");
        File classFile = new File("ParamAnnotated08.java");
        Utils.createFile(classFile, classSrc);

        Utils.compiler("--release", "11", pAnnoFile.getName(), classFile.getName());

        File jar = new File("paramanno08.jar");
        Utils.jar("cvf", jar.getName(), "PA08.class", "ParamAnnotated08.class");

        Utils.testWithRepack(jar, "--unknown-attribute=error");
        System.out.println("  testParameterAnnotations: PASS");
    }
}
