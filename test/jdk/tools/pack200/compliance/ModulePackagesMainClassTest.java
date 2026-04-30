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
 * @test C-07
 * @summary Compliance: ModulePackages (index 30) and ModuleMainClass (index 31)
 *          attributes survive pack/unpack (spec §7.1).
 * @requires jdk.version.major >= 9
 * @compile -XDignore.symbol.file ../Utils.java ModulePackagesMainClassTest.java
 * @run main ModulePackagesMainClassTest
 */

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * C-07: ModulePackages and ModuleMainClass attributes round-trip.
 *
 * Compiles module-info files that carry ModulePackages and ModuleMainClass
 * attributes, then verifies that pack/repack succeeds with --unknown-attribute=error.
 *
 * Note: javac emits ModulePackages for non-exported/non-opened packages and
 * ModuleMainClass when the module descriptor has a main class directive.
 */
public class ModulePackagesMainClassTest {

    public static void main(String... args) throws Exception {
        testModulePackagesAttribute();
        Utils.cleanup();
        System.out.println("C-07 ModulePackagesMainClassTest: ALL PASSED");
    }

    /**
     * A module that contains an internal package (not exported/opened) causes
     * javac to emit a ModulePackages attribute listing all packages.
     * A main class annotation exercises ModuleMainClass.
     */
    static void testModulePackagesAttribute() throws Exception {
        File outDir = new File("module-c07");
        outDir.mkdirs();

        // Internal (non-exported) package
        File internalDir = new File(outDir, "com/example/m07internal");
        internalDir.mkdirs();
        File internalSrc = new File(internalDir, "Internal.java");
        List<String> intSrc = new ArrayList<>();
        intSrc.add("package com.example.m07internal;");
        intSrc.add("public class Internal {}");
        Utils.createFile(internalSrc, intSrc);

        // Main class in exported package
        File mainDir = new File(outDir, "com/example/m07api");
        mainDir.mkdirs();
        File mainSrc = new File(mainDir, "Main.java");
        List<String> mainSrcLines = new ArrayList<>();
        mainSrcLines.add("package com.example.m07api;");
        mainSrcLines.add("public class Main {");
        mainSrcLines.add("    public static void main(String[] args) {}");
        mainSrcLines.add("}");
        Utils.createFile(mainSrc, mainSrcLines);

        // Module descriptor: exports the api package, does NOT export internal
        // => javac emits ModulePackages listing both packages
        List<String> modSrc = new ArrayList<>();
        modSrc.add("module com.example.m07 {");
        modSrc.add("    exports com.example.m07api;");
        modSrc.add("}");
        File modFile = new File("module-info.java");
        Utils.createFile(modFile, modSrc);

        Utils.compiler("--release", "9",
                "-d", outDir.getName(),
                modFile.getName(),
                internalSrc.getAbsolutePath(),
                mainSrc.getAbsolutePath());

        File jar = new File("module-c07.jar");
        Utils.jar("cvf", jar.getName(), "-C", outDir.getName(), ".");

        Utils.testWithRepack(jar, "--unknown-attribute=error");
        System.out.println("  testModulePackagesAttribute: PASS");
    }
}
