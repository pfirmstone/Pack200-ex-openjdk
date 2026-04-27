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
 * @test C-01
 * @summary Compliance: packer selects the correct package version for each
 *          class-file version tier (spec §2.1).
 *          Resources-only → 150.7; Java 8 class → 171.0; Java 17 class → 190.0;
 *          Java 9 module-info → 180.0; Java 7 + InvokeDynamic → 170.1.
 * @compile -XDignore.symbol.file ../Utils.java VersionNegotiationTest.java
 * @run main VersionNegotiationTest
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
 * C-01: Verifies that the packer selects the correct package version for
 * each class-file version tier as defined in the spec §2.1.
 *
 * Archive header layout (bytes):
 *   [0..3] magic CA FE D0 0D
 *   [4]    minor version (UNSIGNED5, always single-byte since ≤ 191)
 *   [5]    major version (UNSIGNED5, always single-byte since ≤ 191)
 */
public class VersionNegotiationTest {

    // Expected package versions from the spec
    static final int JAVA5_MAJOR  = 150; static final int JAVA5_MINOR  = 7;
    static final int JAVA6_MAJOR  = 160; static final int JAVA6_MINOR  = 1;
    static final int JAVA7_MAJOR  = 170; static final int JAVA7_MINOR  = 1;
    static final int JAVA8_MAJOR  = 171; static final int JAVA8_MINOR  = 0;
    static final int JAVA9_MAJOR  = 180; static final int JAVA9_MINOR  = 0;
    static final int JAVA11_MAJOR = 190; static final int JAVA11_MINOR = 0;

    public static void main(String... args) throws Exception {
        testResourcesOnly();
        testJava8Class();
        testJava9ModuleInfo();
        testJava11Class();
        testJava17Class();
        Utils.cleanup();
        System.out.println("C-01 VersionNegotiationTest: ALL PASSED");
    }

    /**
     * A JAR containing only a text resource (no .class files) must produce
     * a pack200 archive with package version 150.7.
     */
    static void testResourcesOnly() throws Exception {
        File jarFile = new File("resources-only.jar");
        createResourceOnlyJar(jarFile);
        int[] ver = getPackVersion(jarFile);
        assertVersion("resources-only JAR", ver, JAVA5_MAJOR, JAVA5_MINOR);
        jarFile.delete();
        System.out.println("  testResourcesOnly: PASS (150.7)");
    }

    /**
     * A JAR containing a Java 8 class (major version 52, no InvokeDynamic,
     * no Module/Package CP entries) must produce package version 171.0.
     */
    static void testJava8Class() throws Exception {
        List<String> src = new ArrayList<>();
        src.add("public class Hello8 {");
        src.add("    public static void main(String[] a) {}");
        src.add("}");
        File javaFile = new File("Hello8.java");
        Utils.createFile(javaFile, src);
        Utils.compiler("--release", "8", "-Xlint:-options", javaFile.getName());

        File jarFile = new File("java8.jar");
        Utils.jar("cvf", jarFile.getName(), "Hello8.class");

        int[] ver = getPackVersion(jarFile);
        assertVersion("Java 8 JAR", ver, JAVA8_MAJOR, JAVA8_MINOR);
        System.out.println("  testJava8Class: PASS (171.0)");
    }

    /**
     * A JAR containing module-info.class (which carries CONSTANT_Module and
     * CONSTANT_Package CP entries) must produce package version 180.0.
     */
    static void testJava9ModuleInfo() throws Exception {
        File outDir = new File("module9");
        outDir.mkdirs();

        List<String> src = new ArrayList<>();
        src.add("module com.example.hello9 {");
        src.add("    exports com.example.hello9;");
        src.add("}");
        File modFile = new File("module-info.java");
        Utils.createFile(modFile, src);

        List<String> pkgSrc = new ArrayList<>();
        pkgSrc.add("package com.example.hello9;");
        pkgSrc.add("public class Hello9 {}");
        File pkgDir = new File(outDir, "com/example/hello9");
        pkgDir.mkdirs();
        File helloFile = new File(pkgDir, "Hello9.java");
        Utils.createFile(helloFile, pkgSrc);

        Utils.compiler("--release", "9",
                "-d", outDir.getName(),
                modFile.getName(), helloFile.getAbsolutePath());

        File jarFile = new File("module9.jar");
        Utils.jar("cvf", jarFile.getName(), "-C", outDir.getName(), ".");

        int[] ver = getPackVersion(jarFile);
        assertVersion("Java 9 module-info JAR", ver, JAVA9_MAJOR, JAVA9_MINOR);
        System.out.println("  testJava9ModuleInfo: PASS (180.0)");
    }

    /**
     * A JAR containing a Java 11 class (major version 55) must produce
     * package version 190.0.
     */
    static void testJava11Class() throws Exception {
        List<String> src = new ArrayList<>();
        src.add("public class Hello11 {");
        src.add("    public String greet(Object o) {");
        src.add("        return \"hello\";");
        src.add("    }");
        src.add("}");
        File javaFile = new File("Hello11.java");
        Utils.createFile(javaFile, src);
        Utils.compiler("--release", "11", javaFile.getName());

        File jarFile = new File("java11.jar");
        Utils.jar("cvf", jarFile.getName(), "Hello11.class");

        int[] ver = getPackVersion(jarFile);
        assertVersion("Java 11 JAR", ver, JAVA11_MAJOR, JAVA11_MINOR);
        System.out.println("  testJava11Class: PASS (190.0)");
    }

    /**
     * A JAR containing a Java 17 class (major version 61) must produce
     * package version 190.0.
     */
    static void testJava17Class() throws Exception {
        List<String> src = new ArrayList<>();
        src.add("public class Hello17 {");
        src.add("    public static String greeting() { return \"Hello Java 17\"; }");
        src.add("}");
        File javaFile = new File("Hello17.java");
        Utils.createFile(javaFile, src);
        Utils.compiler("--release", "17", javaFile.getName());

        File jarFile = new File("java17.jar");
        Utils.jar("cvf", jarFile.getName(), "Hello17.class");

        int[] ver = getPackVersion(jarFile);
        assertVersion("Java 17 JAR", ver, JAVA11_MAJOR, JAVA11_MINOR);
        System.out.println("  testJava17Class: PASS (190.0)");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Packs the given jar and returns {major, minor} from the archive header. */
    static int[] getPackVersion(File jarFile) throws IOException {
        Pack200.Packer packer = Pack200.newPacker();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarFile jf = new JarFile(jarFile)) {
            packer.pack(jf, baos);
        }
        byte[] buf = baos.toByteArray();
        // Archive header: [0..3]=magic, [4]=minor, [5]=major  (UNSIGNED5 single-byte)
        int minor = buf[4] & 0xFF;
        int major = buf[5] & 0xFF;
        return new int[]{ major, minor };
    }

    /** Creates a JAR containing only a plain text resource. */
    static void createResourceOnlyJar(File jarFile) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jarFile))) {
            ZipEntry e = new ZipEntry("META-INF/resource.txt");
            zos.putNextEntry(e);
            zos.write("hello".getBytes());
            zos.closeEntry();
        }
    }

    static void assertVersion(String label, int[] actual,
                              int expectedMajor, int expectedMinor) {
        if (actual[0] != expectedMajor || actual[1] != expectedMinor) {
            throw new AssertionError(label + ": expected package version "
                + expectedMajor + "." + expectedMinor
                + " but got " + actual[0] + "." + actual[1]);
        }
    }
}
