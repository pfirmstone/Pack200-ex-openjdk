/*
 * Copyright (c) 2010, 2018, Oracle and/or its affiliates. All rights reserved.
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
 * @test
 * @bug 6712743 6991164 7168401
 * @summary verify package versions
 * @compile -XDignore.symbol.file Utils.java PackageVersionTest.java
 * @run main PackageVersionTest
 * @author ksrini
 */

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.jar.JarFile;
import net.pack200.Pack200;
import net.pack200.Pack200.Packer;
import net.pack200.Pack200.Unpacker;

public class PackageVersionTest {
    private static final File  javaHome = new File(System.getProperty("java.home"));

    public final static int JAVA5_PACKAGE_MAJOR_VERSION = 150;
    public final static int JAVA5_PACKAGE_MINOR_VERSION = 7;

    public final static int JAVA6_PACKAGE_MAJOR_VERSION = 160;
    public final static int JAVA6_PACKAGE_MINOR_VERSION = 1;

    public final static int JAVA7_PACKAGE_MAJOR_VERSION = 170;
    public final static int JAVA7_PACKAGE_MINOR_VERSION = 1;

    // Pack200 spec §2 – archive version 171.0 covers Java 8 class files (52.x)
    // with no CONSTANT_Module / CONSTANT_Package / CONSTANT_Dynamic CP entries.
    public final static int JAVA8_PACKAGE_MAJOR_VERSION = 171;
    public final static int JAVA8_PACKAGE_MINOR_VERSION = 0;

    public static void main(String... args) throws IOException {
        File out = new File("test.pack");
        createClassFile("Test6");
        createClassFile("Test7");

        verify6991164();

        // Test7.class is compiled with -target 8 (class version 52.0).
        // Per Pack200 spec §2.1 rule 5, a Java-8 class file without
        // CONSTANT_Module/CONSTANT_Package/CONSTANT_Dynamic CP entries must
        // produce archive version 171.0 (JAVA8_PACKAGE_VERSION).
        verifyPack("Test7.class", JAVA8_PACKAGE_MAJOR_VERSION,
                JAVA8_PACKAGE_MINOR_VERSION);

        // test for resource file, ie. no class files
        // Per Pack200 spec §2.1 rule 1, no class files → 150.7 (JAVA5_PACKAGE_VERSION).
        verifyPack("Test6.java", JAVA5_PACKAGE_MAJOR_VERSION,
                JAVA5_PACKAGE_MINOR_VERSION);
        Utils.cleanup();
    }

    static void verify6991164() {
        Unpacker unpacker = Pack200.newUnpacker();
        String versionStr = unpacker.toString();
        String expected = "Pack200, Vendor: " +
                System.getProperty("java.vendor") + ", Version: " +
                getMaxPackageVersion();
        if (!versionStr.equals(expected)) {
            System.out.println("Expected: " + expected);
            System.out.println("Obtained: " + versionStr);
            throw new RuntimeException("did not get expected string " + expected);
        }
    }

    /**
     * Dynamically retrieves the maximum Pack200 package version supported by
     * the library (Constants.MAX_PACKAGE_VERSION) so that the expected version
     * string is correct for whichever Java version is under test, without
     * hardcoding a specific version number that would become stale as the
     * library evolves.
     */
    static String getMaxPackageVersion() {
        try {
            Class<?> constClass =
                    Class.forName("au.net.zeus.util.jar.pack.Constants");
            Field f = constClass.getDeclaredField("MAX_PACKAGE_VERSION");
            f.setAccessible(true);
            return f.get(null).toString();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(
                    "Cannot determine library max package version", e);
        }
    }

    static void createClassFile(String name) {
        createJavaFile(name);
        String target = name.substring(name.length() - 1);
        String javacCmds[] = {
            "-source",
            "8",
            "-target",
            "8",
            "-Xlint:-options",
            name + ".java"
        };
        Utils.compiler(javacCmds);
    }

    static void createJavaFile(String name) {
        PrintStream ps = null;
        FileOutputStream fos = null;
        File outputFile = new File(name + ".java");
        outputFile.delete();
        try {
            fos = new FileOutputStream(outputFile);
            ps = new PrintStream(fos);
            ps.format("public class %s {}", name);
        } catch (IOException ioe) {
            throw new RuntimeException("creation of test file failed");
        } finally {
            Utils.close(ps);
            Utils.close(fos);
        }
    }

    static void verifyPack(String filename, int expected_major, int expected_minor) {

        File jarFileName = new File("test.jar");
        jarFileName.delete();
        String jargs[] = {
            "cvf",
            jarFileName.getName(),
            filename
        };
        Utils.jar(jargs);
        JarFile jfin = null;

        try {
            jfin = new JarFile(jarFileName);
            Packer packer = Pack200.newPacker();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            packer.pack(jfin, baos);
            baos.flush();
            baos.close();
            byte[] buf = baos.toByteArray();

            int minor = buf[4] & 0x000000ff;
            int major = buf[5] & 0x000000ff;

            if (major != expected_major || minor != expected_minor) {
                String msg =
                        String.format("test fails: expected:%d.%d but got %d.%d\n",
                        expected_major, expected_minor,
                        major, minor);
                throw new Error(msg);
            }

            System.out.println(filename + ": OK");
        } catch (IOException ioe) {
            throw new RuntimeException(ioe.getMessage());
        } finally {
            Utils.close((Closeable) jfin);
        }
    }
}
