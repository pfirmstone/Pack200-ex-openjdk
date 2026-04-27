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
 *          AO_HAVE_CP_MODULE_DYNAMIC (bit 13) for module-info, and that
 *          AO_UNUSED_MBZ bits are always zero.
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
    static final int AO_UNUSED_MBZ             = (-1) << 14;

    public static void main(String... args) throws Exception {
        testPlainJava8NoRecordNoModule();
        testRecordClassSetsClassFlagsHi();
        testSealedClassSetsClassFlagsHi();
        testModuleInfoSetsCpModuleDynamic();
        testUnusedBitsMustBeZeroOnAllCases();
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
     * A JAR containing module-info.class (with CONSTANT_Module / CONSTANT_Package
     * CP entries) MUST have AO_HAVE_CP_MODULE_DYNAMIC set.
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
        assertBitSet("Module-info JAR: AO_HAVE_CP_MODULE_DYNAMIC must be set",
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
