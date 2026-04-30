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
 *          (spec §7.3, attribute index 32, requires AO_HAVE_CLASS_FLAGS_HI;
 *          RC sub-attributes require AO_HAVE_RC_ATTRS).
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
 * Exercises the following record configurations:
 *   1. A plain record with primitive components (no sub-attrs).
 *   2. A generic record (exercises Signature sub-attrs on components).
 *   3. A record with an annotated component (exercises RuntimeVisibleAnnotations).
 *   4. A record with mixed sub-attributes (Signature + RuntimeVisibleAnnotations).
 *   5. Multiple components with selective sub-attributes (some attrs, some not).
 *   6. An empty record (0 components — edge case).
 *   7. A record with many components (scaling test).
 *
 * All cases are verified with --unknown-attribute=error to confirm native encoding.
 * Cases 2–5 additionally exercise the AO_HAVE_RC_ATTRS archive option bit path.
 */
public class RecordAttributeTest {

    public static void main(String... args) throws Exception {
        testPlainRecord();
        testGenericRecord();
        testAnnotatedRecord();
        testRecordWithMixedSubAttributes();
        testRecordWithMultipleComponentAttributes();
        testEmptyRecord();
        testRecordWithManyComponents();
        Utils.cleanup();
        System.out.println("C-04 RecordAttributeTest: ALL PASSED");
    }

    /** record Point(int x, int y) {} — two primitive components, no sub-attrs. */
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

    /**
     * A generic record whose components also carry a runtime-visible annotation,
     * exercising both Signature and RuntimeVisibleAnnotations on the same component.
     * This is the primary test for the AO_HAVE_RC_ATTRS band path.
     *
     * <p>Equivalent Java source:
     * <pre>
     *   public record AnnotatedPairR04{@code <T>}({@code @NonNullR04m} T first, {@code @NonNullR04m} T second) {}
     * </pre>
     */
    static void testRecordWithMixedSubAttributes() throws Exception {
        // Annotation type that can appear on a record component
        List<String> annoSrc = new ArrayList<>();
        annoSrc.add("import java.lang.annotation.*;");
        annoSrc.add("@Retention(RetentionPolicy.RUNTIME)");
        annoSrc.add("@Target(ElementType.RECORD_COMPONENT)");
        annoSrc.add("public @interface NonNullR04m {}");
        File annoJava = new File("NonNullR04m.java");
        Utils.createFile(annoJava, annoSrc);

        // Generic record: each component gets both Signature and RVA sub-attrs
        List<String> recSrc = new ArrayList<>();
        recSrc.add("public record AnnotatedPairR04<T>(@NonNullR04m T first, @NonNullR04m T second) {}");
        File recJava = new File("AnnotatedPairR04.java");
        Utils.createFile(recJava, recSrc);

        Utils.compiler("--release", "17", annoJava.getName(), recJava.getName());

        File jar = new File("mixed-record.jar");
        Utils.jar("cvf", jar.getName(), "NonNullR04m.class", "AnnotatedPairR04.class");
        Utils.testWithRepack(jar, "--unknown-attribute=error");
        System.out.println("  testRecordWithMixedSubAttributes: PASS");
    }

    /**
     * A record with several components where only some carry sub-attributes,
     * verifying that selective per-component attribute encoding is preserved.
     *
     * <p>Equivalent Java source:
     * <pre>
     *   public record MixedR04(@NonNullR04s String label, int count, String plain) {}
     * </pre>
     * Here only {@code label} has a RuntimeVisibleAnnotation; the other two components
     * have no sub-attributes.
     */
    static void testRecordWithMultipleComponentAttributes() throws Exception {
        // Annotation type
        List<String> annoSrc = new ArrayList<>();
        annoSrc.add("import java.lang.annotation.*;");
        annoSrc.add("@Retention(RetentionPolicy.RUNTIME)");
        annoSrc.add("@Target(ElementType.RECORD_COMPONENT)");
        annoSrc.add("public @interface NonNullR04s {}");
        File annoJava = new File("NonNullR04s.java");
        Utils.createFile(annoJava, annoSrc);

        // Record: first component annotated, remaining two are plain
        List<String> recSrc = new ArrayList<>();
        recSrc.add("public record MixedR04(@NonNullR04s String label, int count, String plain) {}");
        File recJava = new File("MixedR04.java");
        Utils.createFile(recJava, recSrc);

        Utils.compiler("--release", "17", annoJava.getName(), recJava.getName());

        File jar = new File("selective-record.jar");
        Utils.jar("cvf", jar.getName(), "NonNullR04s.class", "MixedR04.class");
        Utils.testWithRepack(jar, "--unknown-attribute=error");
        System.out.println("  testRecordWithMultipleComponentAttributes: PASS");
    }

    /**
     * Edge case: a record with zero components.
     * Verifies that the packer handles an empty Record attribute gracefully and
     * that AO_HAVE_RC_ATTRS is NOT set (no sub-attributes exist).
     *
     * <p>Equivalent Java source: {@code public record EmptyR04() {}}
     */
    static void testEmptyRecord() throws Exception {
        List<String> src = new ArrayList<>();
        src.add("public record EmptyR04() {}");
        File java = new File("EmptyR04.java");
        Utils.createFile(java, src);
        Utils.compiler("--release", "17", java.getName());

        File jar = new File("empty-record.jar");
        Utils.jar("cvf", jar.getName(), "EmptyR04.class");
        Utils.testWithRepack(jar, "--unknown-attribute=error");
        System.out.println("  testEmptyRecord: PASS");
    }

    /**
     * Scaling test: a record with ten primitive components.
     * Verifies that larger component counts are packed and unpacked correctly.
     *
     * <p>Equivalent Java source:
     * <pre>
     *   public record LargeR04(int a1, int a2, ..., int a10) {}
     * </pre>
     */
    static void testRecordWithManyComponents() throws Exception {
        StringBuilder sb = new StringBuilder("public record LargeR04(");
        for (int i = 1; i <= 10; i++) {
            if (i > 1) sb.append(", ");
            sb.append("int a").append(i);
        }
        sb.append(") {}");

        List<String> src = new ArrayList<>();
        src.add(sb.toString());
        File java = new File("LargeR04.java");
        Utils.createFile(java, src);
        Utils.compiler("--release", "17", java.getName());

        File jar = new File("large-record.jar");
        Utils.jar("cvf", jar.getName(), "LargeR04.class");
        Utils.testWithRepack(jar, "--unknown-attribute=error");
        System.out.println("  testRecordWithManyComponents: PASS");
    }
}
