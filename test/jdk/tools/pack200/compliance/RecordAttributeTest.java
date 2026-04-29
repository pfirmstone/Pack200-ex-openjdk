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
 * @test C-04
 * @summary Compliance: Record attribute and all its component sub-attributes
 *          (name, type, Signature, RuntimeVisibleAnnotations) survive pack/unpack
 *          (spec §7.3, attribute index 32, requires AO_HAVE_CLASS_FLAGS_HI).
 * @requires jdk.version.major >= 17
 * @compile -XDignore.symbol.file ../Utils.java RecordAttributeTest.java
 * @run main RecordAttributeTest
 */

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * C-04: Record attribute round-trip.
 *
 * Exercises three record classes:
 *   1. A plain record with primitive components.
 *   2. A generic record (exercises Signature sub-attrs on components).
 *   3. A record with an annotated component (exercises RuntimeVisibleAnnotations).
 *
 * All three are verified with --unknown-attribute=error to confirm native encoding.
 */
public class RecordAttributeTest {

    public static void main(String... args) throws Exception {
        testPlainRecord();
        testGenericRecord();
        testAnnotatedRecord();
        Utils.cleanup();
        System.out.println("C-04 RecordAttributeTest: ALL PASSED");
    }

    /** record Point(int x, int y) {} — two primitive components. */
    static void testPlainRecord() throws Exception {
        List<String> src = new ArrayList<>();
        src.add("public record PointR04(int x, int y) {}");
        File java = new File("PointR04.java");
        Utils.createFile(java, src);
        Utils.compiler("--release", "17", java.getName());

        File jar = new File("point-record.jar");
        Utils.jar("cvf", jar.getName(), "PointR04.class");
        Utils.testWithRepack(jar, "--unknown-attribute=error");
        System.out.println("  testPlainRecord: PASS");
    }

    /** record Pair<A,B>(A first, B second) {} — generic; exercises Signature on components. */
    static void testGenericRecord() throws Exception {
        List<String> src = new ArrayList<>();
        src.add("public record PairR04<A,B>(A first, B second) {}");
        File java = new File("PairR04.java");
        Utils.createFile(java, src);
        Utils.compiler("--release", "17", java.getName());

        File jar = new File("pair-record.jar");
        Utils.jar("cvf", jar.getName(), "PairR04.class");
        Utils.testWithRepack(jar, "--unknown-attribute=error");
        System.out.println("  testGenericRecord: PASS");
    }

    /**
     * A record with a component annotation.  Compiles an annotation type and
     * a record that uses it on a component, exercising RuntimeVisibleAnnotations
     * on record components.
     */
    static void testAnnotatedRecord() throws Exception {
        List<String> annoSrc = new ArrayList<>();
        annoSrc.add("import java.lang.annotation.*;");
        annoSrc.add("@Retention(RetentionPolicy.RUNTIME)");
        annoSrc.add("@Target(ElementType.RECORD_COMPONENT)");
        annoSrc.add("public @interface NonNullR04 {}");
        File annoJava = new File("NonNullR04.java");
        Utils.createFile(annoJava, annoSrc);

        List<String> recSrc = new ArrayList<>();
        recSrc.add("public record TaggedR04(@NonNullR04 String tag, int value) {}");
        File recJava = new File("TaggedR04.java");
        Utils.createFile(recJava, recSrc);

        Utils.compiler("--release", "17", annoJava.getName(), recJava.getName());

        File jar = new File("tagged-record.jar");
        Utils.jar("cvf", jar.getName(), "NonNullR04.class", "TaggedR04.class");
        Utils.testWithRepack(jar, "--unknown-attribute=error");
        System.out.println("  testAnnotatedRecord: PASS");
    }
}
