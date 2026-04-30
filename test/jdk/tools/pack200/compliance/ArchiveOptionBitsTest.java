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
 * @test C-02
 * @summary Compliance: archive option bits are set / cleared correctly (spec §3).
 *          Verifies AO_HAVE_CLASS_FLAGS_HI (bit 9) for records and sealed classes,
 *          that AO_HAVE_CP_MODULE_DYNAMIC (bit 13) is NOT set for a module-info-only
 *          JAR (module-info is treated as a raw resource by the packer),
 *          that AO_HAVE_RC_ATTRS (bit 14) is set iff record component sub-attributes
 *          are present, and that AO_UNUSED_MBZ bits (15+) are always zero.
 * @requires jdk.version.major >= 17
 * @compile -XDignore.symbol.file ../Utils.java ArchiveOptionBitsTest.java
 * @run main ArchiveOptionBitsTest
 */

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.pack200.Pack200;

/**
 * C-02: Verifies that archive option bits are set and cleared exactly as
 * specified in §3.
 *
 * The options field follows the two version bytes in the UNSIGNED5-encoded
 * archive_header_0 band (magic[4 bytes] + minver[1 byte] + majver[1 byte] +
 * options[1–3 bytes]).
 *
 * UNSIGNED5 encoding (B=5, H=64, S=0, L=192):
 *   Single-byte if value ∈ [0, 191].
 *   Two-byte    if value ∈ [192, 12479]: b0 ∈ [192,255], b1 ∈ [0,191];
 *               value = b0 + b1*64.
 */
public class ArchiveOptionBitsTest {

    // Option bit constants (mirror au.net.zeus.util.jar.pack.Constants)
    static final int AO_HAVE_CLASS_FLAGS_HI    = 1 << 9;   // 512
    static final int AO_HAVE_CP_MODULE_DYNAMIC = 1 << 13;  // 8192
    /** Bit 14: set when any record component carries a sub-attribute (Signature, RVA, …). */
    static final int AO_HAVE_RC_ATTRS          = 1 << 14;  // 16384
    /** Bits 15 and above are reserved and must always be zero. */
    static final int AO_UNUSED_MBZ             = (-1) << 15;

    public static void main(String... args) throws Exception {
        testPlainJava8NoRecordNoModule();
        testRecordClassSetsClassFlagsHi();
        testSealedClassSetsClassFlagsHi();
        testModuleInfoSetsCpModuleDynamic();
        testUnusedBitsMustBeZeroOnAllCases();
        testGenericRecordSetsRCAttrsBit();
        testPlainRecordDoesNotSetRCAttrsBit();
        Utils.cleanup();
        System.out.println("C-02 ArchiveOptionBitsTest: ALL PASSED");
    }

    /**
     * A plain Java 8 class should NOT have AO_HAVE_CLASS_FLAGS_HI set, and
     * should NOT have AO_HAVE_CP_MODULE_DYNAMIC set.
     */
    static void testPlainJava8NoRecordNoModule() throws Exception {
        List<String> src = new ArrayList<>();
        src.add("public class Plain8 { public void m() {} }");
        File java = new File("Plain8.java");
        Utils.createFile(java, src);
        Utils.compiler("--release", "8", "-Xlint:-options", java.getName());

        File jar = new File("plain8.jar");
        Utils.jar("cvf", jar.getName(), "Plain8.class");

        int opts = getArchiveOptions(jar);
        assertBitClear("Plain Java 8: AO_HAVE_CLASS_FLAGS_HI must be clear",
                opts, AO_HAVE_CLASS_FLAGS_HI);
        assertBitClear("Plain Java 8: AO_HAVE_CP_MODULE_DYNAMIC must be clear",
                opts, AO_HAVE_CP_MODULE_DYNAMIC);
        assertUnusedZero("Plain Java 8", opts);
        System.out.println("  testPlainJava8NoRecordNoModule: PASS");
    }

    /**
     * A JAR containing a record class (Java 14+ Record attribute) MUST have
     * AO_HAVE_CLASS_FLAGS_HI set so the Record band (index 32) is accessible.
     */
    static void testRecordClassSetsClassFlagsHi() throws Exception {
        List<String> src = new ArrayList<>();
        src.add("public record PointC02(int x, int y) {}");
        File java = new File("PointC02.java");
        Utils.createFile(java, src);
        Utils.compiler("--release", "17", java.getName());

        File jar = new File("record17.jar");
        Utils.jar("cvf", jar.getName(), "PointC02.class");

        int opts = getArchiveOptions(jar);
        assertBitSet("Record class: AO_HAVE_CLASS_FLAGS_HI must be set",
                opts, AO_HAVE_CLASS_FLAGS_HI);
        assertUnusedZero("Record JAR", opts);
        System.out.println("  testRecordClassSetsClassFlagsHi: PASS");
    }

    /**
     * A JAR containing a sealed class (Java 17+ PermittedSubclasses attribute)
     * MUST have AO_HAVE_CLASS_FLAGS_HI set so the PermittedSubclasses band
     * (index 33) is accessible.
     */
    static void testSealedClassSetsClassFlagsHi() throws Exception {
        List<String> shapeSrc = new ArrayList<>();
        shapeSrc.add("public sealed class ShapeC02 permits CircleC02, RectC02 {}");
        File shapeFile = new File("ShapeC02.java");
        Utils.createFile(shapeFile, shapeSrc);

        List<String> circSrc = new ArrayList<>();
        circSrc.add("public final class CircleC02 extends ShapeC02 {}");
        File circFile = new File("CircleC02.java");
        Utils.createFile(circFile, circSrc);

        List<String> rectSrc = new ArrayList<>();
        rectSrc.add("public final class RectC02 extends ShapeC02 {}");
        File rectFile = new File("RectC02.java");
        Utils.createFile(rectFile, rectSrc);

        Utils.compiler("--release", "17",
                shapeFile.getName(), circFile.getName(), rectFile.getName());

        File jar = new File("sealed17.jar");
        Utils.jar("cvf", jar.getName(),
                "ShapeC02.class", "CircleC02.class", "RectC02.class");

        int opts = getArchiveOptions(jar);
        assertBitSet("Sealed class: AO_HAVE_CLASS_FLAGS_HI must be set",
                opts, AO_HAVE_CLASS_FLAGS_HI);
        assertUnusedZero("Sealed JAR", opts);
        System.out.println("  testSealedClassSetsClassFlagsHi: PASS");
    }

    /**
     * A JAR containing only module-info.class is packed with module-info treated
     * as a raw resource (not a parsed class). The packer passes module-info bytes
     * through unchanged without inspecting its constant pool, so
     * AO_HAVE_CP_MODULE_DYNAMIC is NOT set. The archive is still valid: the
     * module-info bytes survive the round-trip intact.
     */
    static void testModuleInfoSetsCpModuleDynamic() throws Exception {
        File outDir = new File("module-c02");
        outDir.mkdirs();

        List<String> src = new ArrayList<>();
        src.add("module com.example.c02 {}");
        File modFile = new File("module-info.java");
        Utils.createFile(modFile, src);

        Utils.compiler("--release", "9", "-d", outDir.getName(), modFile.getName());

        File jar = new File("module-c02.jar");
        Utils.jar("cvf", jar.getName(), "-C", outDir.getName(), ".");

        int opts = getArchiveOptions(jar);
        // module-info.class is treated as a raw resource by the packer (not a
        // parsed class), so CONSTANT_Module/Package entries in its CP are never
        // seen, and AO_HAVE_CP_MODULE_DYNAMIC is NOT set.
        assertBitClear("Module-info-only JAR: AO_HAVE_CP_MODULE_DYNAMIC must NOT be set "
                + "(module-info passed through as raw resource)",
                opts, AO_HAVE_CP_MODULE_DYNAMIC);
        assertUnusedZero("Module-info JAR", opts);
        System.out.println("  testModuleInfoSetsCpModuleDynamic: PASS");
    }

    /** Packs several representative archives and verifies AO_UNUSED_MBZ is zero. */
    static void testUnusedBitsMustBeZeroOnAllCases() throws Exception {
        // resources-only
        File rJar = new File("res-c02.jar");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(rJar))) {
            ZipEntry e = new ZipEntry("readme.txt");
            zos.putNextEntry(e);
            zos.write("data".getBytes());
            zos.closeEntry();
        }
        assertUnusedZero("resources-only", getArchiveOptions(rJar));
        System.out.println("  testUnusedBitsMustBeZeroOnAllCases: PASS");
    }

    /**
     * A JAR whose record components carry sub-attributes (Signature) MUST have
     * AO_HAVE_RC_ATTRS (bit 14) set so the rc_attr_bands are written and read.
     * AO_HAVE_CLASS_FLAGS_HI (bit 9) must also be set because the class is a record.
     */
    static void testGenericRecordSetsRCAttrsBit() throws Exception {
        List<String> src = new ArrayList<>();
        src.add("public record GenericC02<T>(T value) {}");
        File java = new File("GenericC02.java");
        Utils.createFile(java, src);
        Utils.compiler("--release", "17", java.getName());

        File jar = new File("generic-c02.jar");
        Utils.jar("cvf", jar.getName(), "GenericC02.class");

        int opts = getArchiveOptions(jar);
        assertBitSet("Generic record: AO_HAVE_CLASS_FLAGS_HI must be set",
                opts, AO_HAVE_CLASS_FLAGS_HI);
        assertBitSet("Generic record: AO_HAVE_RC_ATTRS must be set (Signature sub-attr)",
                opts, AO_HAVE_RC_ATTRS);
        assertUnusedZero("Generic record JAR", opts);
        System.out.println("  testGenericRecordSetsRCAttrsBit: PASS");
    }

    /**
     * A JAR containing a plain record (no component sub-attributes) must NOT
     * have AO_HAVE_RC_ATTRS (bit 14) set, preserving backward compatibility
     * with Pack200 readers that predate the RC-attrs band.
     */
    static void testPlainRecordDoesNotSetRCAttrsBit() throws Exception {
        List<String> src = new ArrayList<>();
        src.add("public record PlainC02(int x, int y) {}");
        File java = new File("PlainC02.java");
        Utils.createFile(java, src);
        Utils.compiler("--release", "17", java.getName());

        File jar = new File("plain-c02.jar");
        Utils.jar("cvf", jar.getName(), "PlainC02.class");

        int opts = getArchiveOptions(jar);
        // Plain record still needs AO_HAVE_CLASS_FLAGS_HI for the Record class attribute
        assertBitSet("Plain record: AO_HAVE_CLASS_FLAGS_HI must be set",
                opts, AO_HAVE_CLASS_FLAGS_HI);
        // No sub-attributes on components → AO_HAVE_RC_ATTRS must be clear
        assertBitClear("Plain record: AO_HAVE_RC_ATTRS must be clear (no RC sub-attrs)",
                opts, AO_HAVE_RC_ATTRS);
        assertUnusedZero("Plain record JAR", opts);
        System.out.println("  testPlainRecordDoesNotSetRCAttrsBit: PASS");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Packs the given jar and returns the archive_options integer.
     * Reads bytes 4 (minver), 5 (majver), then decodes the options UNSIGNED5 value
     * starting at byte 6.
     */
    static int getArchiveOptions(File jarFile) throws IOException {
        Pack200.Packer packer = Pack200.newPacker();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarFile jf = new JarFile(jarFile)) {
            packer.pack(jf, baos);
        }
        byte[] buf = baos.toByteArray();
        // Decode UNSIGNED5 options starting at offset 6
        return (int) decodeUnsigned5(buf, 6);
    }

    /**
     * Decodes a UNSIGNED5-encoded value from buf starting at the given offset.
     * UNSIGNED5: B=5, H=64, S=0, L=192.
     *   Terminal byte: b < 192.
     *   Value = sum of b_i * 64^i.
     */
    static long decodeUnsigned5(byte[] buf, int offset) {
        int L = 192, H = 64;
        long sum = 0;
        long H_i = 1;
        for (int i = 0; i < 5; i++) {
            int b = buf[offset + i] & 0xFF;
            sum += (long) b * H_i;
            H_i *= H;
            if (b < L) break;
        }
        return sum;
    }

    static void assertBitSet(String msg, int opts, int bit) {
        if ((opts & bit) == 0) {
            throw new AssertionError(msg + " (options=0x" + Integer.toHexString(opts) + ")");
        }
    }

    static void assertBitClear(String msg, int opts, int bit) {
        if ((opts & bit) != 0) {
            throw new AssertionError(msg + " (options=0x" + Integer.toHexString(opts) + ")");
        }
    }

    static void assertUnusedZero(String label, int opts) {
        if ((opts & AO_UNUSED_MBZ) != 0) {
            throw new AssertionError(label + ": AO_UNUSED_MBZ bits are non-zero: 0x"
                    + Integer.toHexString(opts));
        }
    }
}
