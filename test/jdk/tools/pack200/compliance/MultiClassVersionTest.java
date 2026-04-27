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
 * @test C-12
 * @summary Compliance: a JAR segment containing class files compiled to
 *          different Java versions uses package version 190.0 (driven by the
 *          highest), and emits the .ClassFile.version pseudo-attribute for
 *          classes whose version differs from the segment default (spec §2.1,
 *          §7.1 index 24).
 * @compile -XDignore.symbol.file ../Utils.java MultiClassVersionTest.java
 * @run main MultiClassVersionTest
 */

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import net.pack200.Pack200;

/**
 * C-12: Multi-class-version segment.
 *
 * Builds a JAR containing:
 *   - Hello8.class  (major version 52, Java 8)
 *   - Record12.class (major version 61, Java 17 record)
 *
 * The expected behaviour:
 *   - The archive is produced with package version 190.0 (driven by the Java 17 class).
 *   - The .ClassFile.version pseudo-attribute (attribute index 24) is emitted for
 *     Hello8.class so the unpacker can restore its original major version.
 *   - Both classes round-trip without loss.
 */
public class MultiClassVersionTest {

    static final int JAVA11_MAJOR = 190;
    static final int JAVA11_MINOR = 0;

    public static void main(String... args) throws Exception {
        buildTestJar();
        verifyPackageVersion();
        verifyRoundTrip();
        Utils.cleanup();
        System.out.println("C-12 MultiClassVersionTest: ALL PASSED");
    }

    static File buildTestJar() throws Exception {
        // Java 8 class
        List<String> src8 = new ArrayList<>();
        src8.add("public class Hello8v12 {");
        src8.add("    public static void greet() {}");
        src8.add("}");
        File java8 = new File("Hello8v12.java");
        Utils.createFile(java8, src8);
        Utils.compiler("--release", "8", "-Xlint:-options", java8.getName());

        // Java 17 record
        List<String> src17 = new ArrayList<>();
        src17.add("public record Record12(int x, int y) {}");
        File java17 = new File("Record12.java");
        Utils.createFile(java17, src17);
        Utils.compiler("--release", "17", java17.getName());

        File jar = new File("mixed-versions.jar");
        Utils.jar("cvf", jar.getName(), "Hello8v12.class", "Record12.class");
        return jar;
    }

    /** The archive produced from a mixed-version JAR must use package version 190.0. */
    static void verifyPackageVersion() throws Exception {
        File jar = new File("mixed-versions.jar");
        Pack200.Packer packer = Pack200.newPacker();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarFile jf = new JarFile(jar)) {
            packer.pack(jf, baos);
        }
        byte[] buf = baos.toByteArray();
        int minor = buf[4] & 0xFF;
        int major = buf[5] & 0xFF;
        if (major != JAVA11_MAJOR || minor != JAVA11_MINOR) {
            throw new AssertionError("Expected package version 190.0 for mixed JAR but got "
                    + major + "." + minor);
        }
        System.out.println("  verifyPackageVersion: PASS (190.0)");
    }

    /**
     * Both classes must round-trip correctly.  The Java 8 class's original
     * major version must be restored after unpack.
     */
    static void verifyRoundTrip() throws Exception {
        File jar = new File("mixed-versions.jar");
        Utils.testWithRepack(jar, "--unknown-attribute=error");
        System.out.println("  verifyRoundTrip: PASS");
    }
}
