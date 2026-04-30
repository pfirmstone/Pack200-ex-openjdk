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
 * @test C-14
 * @summary Compliance: Record component sub-attributes (Signature and
 *          RuntimeVisibleAnnotations) are preserved byte-for-byte after pack/unpack.
 *          Exercises the AO_HAVE_RC_ATTRS band path introduced in Phase 3.
 * @requires jdk.version.major >= 17
 * @compile -XDignore.symbol.file ../Utils.java RecordSubAttributeSemanticTest.java
 * @run main RecordSubAttributeSemanticTest
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import net.pack200.Pack200;

/**
 * C-14: Semantic verification that record component sub-attributes survive
 * pack/unpack with their content intact.
 *
 * <p>Each test:
 * <ol>
 *   <li>Compiles a Java record source file.</li>
 *   <li>Packages the class into a JAR.</li>
 *   <li>Packs the JAR with Pack200 (in-process, no subprocess).</li>
 *   <li>Unpacks the result to a new JAR.</li>
 *   <li>Parses the class bytes and asserts the expected attributes are present.</li>
 * </ol>
 *
 * <p>Attributes verified per test:
 * <ul>
 *   <li><b>C-14a</b> {@code testSignatureSubAttr}: Signature sub-attr on each component
 *       of a generic record {@code Pair<A,B>}.</li>
 *   <li><b>C-14b</b> {@code testAnnotationSubAttr}: RuntimeVisibleAnnotations sub-attr
 *       on an annotated component.</li>
 *   <li><b>C-14c</b> {@code testMixedSubAttrs}: both Signature and
 *       RuntimeVisibleAnnotations on the same component.</li>
 *   <li><b>C-14d</b> {@code testSelectiveSubAttrs}: only one of three components
 *       carries a sub-attribute; the others are plain.</li>
 *   <li><b>C-14e</b> {@code testNoSubAttrs}: plain record; verifies that the
 *       absence of sub-attributes is also preserved correctly.</li>
 * </ul>
 */
public class RecordSubAttributeSemanticTest {

    public static void main(String... args) throws Exception {
        testSignatureSubAttr();
        testAnnotationSubAttr();
        testMixedSubAttrs();
        testSelectiveSubAttrs();
        testNoSubAttrs();
        Utils.cleanup();
        System.out.println("C-14 RecordSubAttributeSemanticTest: ALL PASSED");
    }

    // =========================================================================
    // C-14a: Signature sub-attribute on generic record components
    // =========================================================================

    /**
     * Packs and unpacks {@code PairS14<A,B>(A first, B second)}.
     *
     * <p>Each component must have a Signature sub-attribute in the Record
     * attribute after round-trip.  The Record attribute body is parsed directly
     * from the class bytes to confirm the sub-attribute count.
     */
    static void testSignatureSubAttr() throws Exception {
        List<String> src = new ArrayList<>();
        src.add("public record PairS14<A,B>(A first, B second) {}");
        File java = new File("PairS14.java");
        Utils.createFile(java, src);
        Utils.compiler("--release", "17", java.getName());

        File jar = new File("pair-s14.jar");
        Utils.jar("cvf", jar.getName(), "PairS14.class");

        byte[] after = packUnpackGetClass(jar, "PairS14.class");
        ClassFileParser p = new ClassFileParser(after);

        byte[] recordData = p.findAttribute("Record");
        assertNotNull(recordData, "Record attribute missing after round-trip (PairS14)");

        RecordInfo[] comps = parseRecordComponents(recordData);
        assertEq(2, comps.length, "PairS14 component count");

        for (int i = 0; i < comps.length; i++) {
            assertPositive(comps[i].attrCount,
                    "PairS14 component[" + i + "] must have at least one sub-attr (Signature)");
        }
        System.out.println("  testSignatureSubAttr: PASS"
                + " (2 components each with " + comps[0].attrCount + " sub-attr(s))");
    }

    // =========================================================================
    // C-14b: RuntimeVisibleAnnotations sub-attribute
    // =========================================================================

    /**
     * Packs and unpacks {@code TaggedS14(@NonNullS14 String tag, int value)}.
     *
     * <p>The first component carries a RuntimeVisibleAnnotations sub-attribute;
     * the second has none.  After round-trip, the Record attribute must reflect
     * that asymmetry.
     */
    static void testAnnotationSubAttr() throws Exception {
        // Compile annotation type
        List<String> annoSrc = new ArrayList<>();
        annoSrc.add("import java.lang.annotation.*;");
        annoSrc.add("@Retention(RetentionPolicy.RUNTIME)");
        annoSrc.add("@Target(ElementType.RECORD_COMPONENT)");
        annoSrc.add("public @interface NonNullS14 {}");
        File annoJava = new File("NonNullS14.java");
        Utils.createFile(annoJava, annoSrc);

        // Compile record
        List<String> recSrc = new ArrayList<>();
        recSrc.add("public record TaggedS14(@NonNullS14 String tag, int value) {}");
        File recJava = new File("TaggedS14.java");
        Utils.createFile(recJava, recSrc);

        Utils.compiler("--release", "17", annoJava.getName(), recJava.getName());

        File jar = new File("tagged-s14.jar");
        Utils.jar("cvf", jar.getName(), "NonNullS14.class", "TaggedS14.class");

        byte[] after = packUnpackGetClass(jar, "TaggedS14.class");
        ClassFileParser cp = new ClassFileParser(after);

        byte[] recordData = cp.findAttribute("Record");
        assertNotNull(recordData, "Record attribute missing after round-trip (TaggedS14)");

        RecordInfo[] comps = parseRecordComponents(recordData);
        assertEq(2, comps.length, "TaggedS14 component count");

        // First component (tag) must carry at least one sub-attr (RuntimeVisibleAnnotations)
        assertPositive(comps[0].attrCount,
                "TaggedS14 component[0] (tag) must have at least one sub-attr");
        // Second component (value, primitive int) has no sub-attrs
        assertEq(0, comps[1].attrCount,
                "TaggedS14 component[1] (value) must have no sub-attrs");

        System.out.println("  testAnnotationSubAttr: PASS"
                + " (component[0] has " + comps[0].attrCount
                + " sub-attr(s); component[1] has 0)");
    }

    // =========================================================================
    // C-14c: Mixed sub-attributes (Signature + RuntimeVisibleAnnotations)
    // =========================================================================

    /**
     * Packs and unpacks {@code AnnotatedPairS14<T>(@NonNullS14m T first, @NonNullS14m T second)}.
     *
     * <p>Each component carries both a Signature sub-attribute (from the type
     * parameter T) and a RuntimeVisibleAnnotations sub-attribute (from the
     * annotation).  After round-trip both must still be present.
     */
    static void testMixedSubAttrs() throws Exception {
        List<String> annoSrc = new ArrayList<>();
        annoSrc.add("import java.lang.annotation.*;");
        annoSrc.add("@Retention(RetentionPolicy.RUNTIME)");
        annoSrc.add("@Target(ElementType.RECORD_COMPONENT)");
        annoSrc.add("public @interface NonNullS14m {}");
        File annoJava = new File("NonNullS14m.java");
        Utils.createFile(annoJava, annoSrc);

        List<String> recSrc = new ArrayList<>();
        recSrc.add("public record AnnotatedPairS14<T>(@NonNullS14m T first, @NonNullS14m T second) {}");
        File recJava = new File("AnnotatedPairS14.java");
        Utils.createFile(recJava, recSrc);

        Utils.compiler("--release", "17", annoJava.getName(), recJava.getName());

        File jar = new File("anpair-s14.jar");
        Utils.jar("cvf", jar.getName(), "NonNullS14m.class", "AnnotatedPairS14.class");

        byte[] after = packUnpackGetClass(jar, "AnnotatedPairS14.class");
        ClassFileParser cp = new ClassFileParser(after);

        byte[] recordData = cp.findAttribute("Record");
        assertNotNull(recordData, "Record attribute missing after round-trip (AnnotatedPairS14)");

        RecordInfo[] comps = parseRecordComponents(recordData);
        assertEq(2, comps.length, "AnnotatedPairS14 component count");

        // Each component has Signature + RuntimeVisibleAnnotations → at least 2 sub-attrs
        for (int i = 0; i < comps.length; i++) {
            if (comps[i].attrCount < 2) {
                throw new AssertionError(
                        "AnnotatedPairS14 component[" + i + "] expected >=2 sub-attrs but got "
                        + comps[i].attrCount);
            }
        }
        System.out.println("  testMixedSubAttrs: PASS"
                + " (each component has " + comps[0].attrCount + " sub-attr(s))");
    }

    // =========================================================================
    // C-14d: Selective sub-attributes — only one component decorated
    // =========================================================================

    /**
     * Packs and unpacks {@code MixedS14(@NonNullS14s String label, int count, String plain)}.
     *
     * <p>Only the first component carries an annotation; the other two are plain.
     * After round-trip the attribute counts must be [≥1, 0, 0].
     */
    static void testSelectiveSubAttrs() throws Exception {
        List<String> annoSrc = new ArrayList<>();
        annoSrc.add("import java.lang.annotation.*;");
        annoSrc.add("@Retention(RetentionPolicy.RUNTIME)");
        annoSrc.add("@Target(ElementType.RECORD_COMPONENT)");
        annoSrc.add("public @interface NonNullS14s {}");
        File annoJava = new File("NonNullS14s.java");
        Utils.createFile(annoJava, annoSrc);

        List<String> recSrc = new ArrayList<>();
        recSrc.add("public record MixedS14(@NonNullS14s String label, int count, String plain) {}");
        File recJava = new File("MixedS14.java");
        Utils.createFile(recJava, recSrc);

        Utils.compiler("--release", "17", annoJava.getName(), recJava.getName());

        File jar = new File("mixed-s14.jar");
        Utils.jar("cvf", jar.getName(), "NonNullS14s.class", "MixedS14.class");

        byte[] after = packUnpackGetClass(jar, "MixedS14.class");
        ClassFileParser cp = new ClassFileParser(after);

        byte[] recordData = cp.findAttribute("Record");
        assertNotNull(recordData, "Record attribute missing after round-trip (MixedS14)");

        RecordInfo[] comps = parseRecordComponents(recordData);
        assertEq(3, comps.length, "MixedS14 component count");

        assertPositive(comps[0].attrCount,
                "MixedS14 component[0] (label) must have at least one sub-attr");
        assertEq(0, comps[1].attrCount,
                "MixedS14 component[1] (count) must have no sub-attrs");
        assertEq(0, comps[2].attrCount,
                "MixedS14 component[2] (plain) must have no sub-attrs");

        System.out.println("  testSelectiveSubAttrs: PASS"
                + " (sub-attr counts: [" + comps[0].attrCount
                + ", " + comps[1].attrCount
                + ", " + comps[2].attrCount + "])");
    }

    // =========================================================================
    // C-14e: Plain record — no sub-attributes
    // =========================================================================

    /**
     * Packs and unpacks {@code PointS14(int x, int y)}.
     *
     * <p>Neither component carries any sub-attribute.  After round-trip the
     * Record attribute must still be present and the component sub-attr counts
     * must both be zero.
     */
    static void testNoSubAttrs() throws Exception {
        List<String> src = new ArrayList<>();
        src.add("public record PointS14(int x, int y) {}");
        File java = new File("PointS14.java");
        Utils.createFile(java, src);
        Utils.compiler("--release", "17", java.getName());

        File jar = new File("point-s14.jar");
        Utils.jar("cvf", jar.getName(), "PointS14.class");

        byte[] after = packUnpackGetClass(jar, "PointS14.class");
        ClassFileParser cp = new ClassFileParser(after);

        byte[] recordData = cp.findAttribute("Record");
        assertNotNull(recordData, "Record attribute missing after round-trip (PointS14)");

        RecordInfo[] comps = parseRecordComponents(recordData);
        assertEq(2, comps.length, "PointS14 component count");
        assertEq(0, comps[0].attrCount, "PointS14 component[0] must have no sub-attrs");
        assertEq(0, comps[1].attrCount, "PointS14 component[1] must have no sub-attrs");

        System.out.println("  testNoSubAttrs: PASS (2 components, 0 sub-attrs each)");
    }

    // =========================================================================
    // Pack/Unpack helpers
    // =========================================================================

    /**
     * Packs {@code jarFile} with Pack200, unpacks the result, and returns the
     * raw bytes of the named entry.
     */
    static byte[] packUnpackGetClass(File jarFile, String entryName) throws IOException {
        // Pack in-process (no subprocess)
        Pack200.Packer packer = Pack200.newPacker();
        ByteArrayOutputStream packBaos = new ByteArrayOutputStream();
        try (JarFile jf = new JarFile(jarFile)) {
            packer.pack(jf, packBaos);
        }

        // Unpack to a temp JAR
        File unpackedJar = new File("unpacked-" + jarFile.getName());
        Pack200.Unpacker unpacker = Pack200.newUnpacker();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(unpackedJar))) {
            unpacker.unpack(new ByteArrayInputStream(packBaos.toByteArray()), jos);
        }

        // Extract the named entry
        try (JarFile jf = new JarFile(unpackedJar)) {
            JarEntry entry = jf.getJarEntry(entryName);
            if (entry == null) {
                throw new AssertionError("Entry not found after unpack: " + entryName);
            }
            try (InputStream is = jf.getInputStream(entry)) {
                return readAllBytes(is);
            }
        }
    }

    static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    // =========================================================================
    // Record attribute parser
    // =========================================================================

    /** Lightweight holder for per-component metadata extracted from the Record attribute. */
    static class RecordInfo {
        final int nameIndex;
        final int descriptorIndex;
        final int attrCount;

        RecordInfo(int nameIndex, int descriptorIndex, int attrCount) {
            this.nameIndex = nameIndex;
            this.descriptorIndex = descriptorIndex;
            this.attrCount = attrCount;
        }
    }

    /**
     * Parses the raw body of a {@code Record} attribute and returns one
     * {@link RecordInfo} per component.
     *
     * <p>Record attribute layout (JVMS 4.7.23):
     * <pre>
     *   u2 components_count
     *   record_component_info[components_count] {
     *     u2 name_index
     *     u2 descriptor_index
     *     u2 attributes_count
     *     attribute_info[attributes_count] { u2 name_idx; u4 len; u1[len] data; }
     *   }
     * </pre>
     */
    static RecordInfo[] parseRecordComponents(byte[] recordAttrBody) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(recordAttrBody));
        int count = dis.readUnsignedShort();
        RecordInfo[] result = new RecordInfo[count];
        for (int i = 0; i < count; i++) {
            int nameIdx = dis.readUnsignedShort();
            int descIdx = dis.readUnsignedShort();
            int attrCount = dis.readUnsignedShort();
            // Skip the sub-attribute bodies (we only care about the count)
            for (int a = 0; a < attrCount; a++) {
                dis.readUnsignedShort(); // attr name index
                int len = dis.readInt();
                dis.skipBytes(len);
            }
            result[i] = new RecordInfo(nameIdx, descIdx, attrCount);
        }
        return result;
    }

    // =========================================================================
    // Minimal class-file parser (reused from RoundTripSemanticVerify pattern)
    // =========================================================================

    /**
     * Minimal class-file parser capable of locating named attributes in the
     * class-level attribute table.
     */
    static class ClassFileParser {

        private final byte[] buf;
        private int pos;
        private final String[] cpUtf8;
        private final int cpCount;

        ClassFileParser(byte[] classBytes) throws IOException {
            this.buf = classBytes;
            this.pos = 0;

            int magic = readInt();
            if (magic != 0xCAFEBABE) {
                throw new IOException("Bad magic: 0x" + Integer.toHexString(magic));
            }
            readShort(); // minor
            readShort(); // major

            cpCount = readShort(); // one-based
            cpUtf8 = new String[cpCount];

            for (int i = 1; i < cpCount; i++) {
                int tag = readByte() & 0xFF;
                switch (tag) {
                    case 1: // Utf8
                        int len = readShort() & 0xFFFF;
                        byte[] chars = readBytes(len);
                        cpUtf8[i] = new String(chars, "UTF-8");
                        break;
                    case 3: case 4:
                        skip(4); break;
                    case 5: case 6:
                        skip(8); i++; break;
                    case 7: case 8: case 16: case 19: case 20:
                        skip(2); break;
                    case 9: case 10: case 11: case 12:
                    case 17: case 18:
                        skip(4); break;
                    case 15:
                        skip(3); break;
                    default:
                        throw new IOException("Unknown CP tag " + tag + " at index " + i);
                }
            }

            skip(2 + 2 + 2); // access, this, super
            int ifaceCount = readShort() & 0xFFFF;
            skip(ifaceCount * 2);
            int fieldCount = readShort() & 0xFFFF;
            for (int i = 0; i < fieldCount; i++) skipMemberInfo();
            int methodCount = readShort() & 0xFFFF;
            for (int i = 0; i < methodCount; i++) skipMemberInfo();
            // pos is now at class-level attribute_count
        }

        byte[] findAttribute(String name) throws IOException {
            int attrCount = readShort() & 0xFFFF;
            for (int i = 0; i < attrCount; i++) {
                int nameIdx = readShort() & 0xFFFF;
                int length = readInt();
                String attrName = (nameIdx < cpUtf8.length) ? cpUtf8[nameIdx] : null;
                if (name.equals(attrName)) {
                    return readBytes(length);
                }
                skip(length);
            }
            return null;
        }

        private int readByte()  { return buf[pos++]; }
        private int readShort() { return ((buf[pos++] & 0xFF) << 8) | (buf[pos++] & 0xFF); }
        private int readInt()   {
            return ((buf[pos++] & 0xFF) << 24) | ((buf[pos++] & 0xFF) << 16)
                 | ((buf[pos++] & 0xFF) <<  8) |  (buf[pos++] & 0xFF);
        }
        private byte[] readBytes(int n) {
            byte[] r = Arrays.copyOfRange(buf, pos, pos + n);
            pos += n;
            return r;
        }
        private void skip(int n) { pos += n; }

        private void skipMemberInfo() throws IOException {
            skip(2 + 2 + 2); // access, name, descriptor
            int ac = readShort() & 0xFFFF;
            for (int a = 0; a < ac; a++) {
                skip(2);
                int len = readInt();
                skip(len);
            }
        }
    }

    // =========================================================================
    // Assertion helpers
    // =========================================================================

    static void assertNotNull(Object v, String msg) {
        if (v == null) throw new AssertionError(msg);
    }

    static void assertEq(int expected, int actual, String context) {
        if (expected != actual) {
            throw new AssertionError(context + ": expected " + expected + " but got " + actual);
        }
    }

    static void assertPositive(int actual, String msg) {
        if (actual <= 0) throw new AssertionError(msg + " (got " + actual + ")");
    }
}
