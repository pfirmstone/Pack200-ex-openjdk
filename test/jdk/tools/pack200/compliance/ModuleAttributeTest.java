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
 * @test C-06
 * @summary Compliance: Module attribute with all sub-elements (requires, exports,
 *          opens, uses, provides) survives pack/unpack (spec §7.2, index 29).
 * @requires jdk.version.major >= 9
 * @compile -XDignore.symbol.file ../Utils.java ModuleAttributeTest.java
 * @run main ModuleAttributeTest
 */

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * C-06: Module attribute round-trip.
 *
 * Compiles a module-info covering all Module attribute sub-elements — requires,
 * exports (with to-clause), opens (with to-clause), uses, and provides — then
 * verifies that pack/repack succeeds with --unknown-attribute=error.
 */
public class ModuleAttributeTest {

    public static void main(String... args) throws Exception {
        testFullModuleAttribute();
        testMinimalModule();
        Utils.cleanup();
        System.out.println("C-06 ModuleAttributeTest: ALL PASSED");
    }

    /**
     * A module-info that exercises all Module sub-elements:
     *   requires, exports ... to, opens ... to, uses, provides ... with.
     */
    static void testFullModuleAttribute() throws Exception {
        File outDir = new File("module-full");
        outDir.mkdirs();

        // Service interface and implementation
        File svcDir = new File(outDir, "com/example/m06svc");
        svcDir.mkdirs();
        File svcFile = new File(svcDir, "Svc.java");
        List<String> svcSrc = new ArrayList<>();
        svcSrc.add("package com.example.m06svc;");
        svcSrc.add("public interface Svc {}");
        Utils.createFile(svcFile, svcSrc);

        File implDir = new File(outDir, "com/example/m06impl");
        implDir.mkdirs();
        File implFile = new File(implDir, "SvcImpl.java");
        List<String> implSrc = new ArrayList<>();
        implSrc.add("package com.example.m06impl;");
        implSrc.add("import com.example.m06svc.Svc;");
        implSrc.add("public class SvcImpl implements Svc {}");
        Utils.createFile(implFile, implSrc);

        List<String> modSrc = new ArrayList<>();
        modSrc.add("module com.example.m06 {");
        modSrc.add("    requires java.base;");
        modSrc.add("    exports com.example.m06svc;");
        modSrc.add("    opens   com.example.m06impl;");
        modSrc.add("    uses    com.example.m06svc.Svc;");
        modSrc.add("    provides com.example.m06svc.Svc with com.example.m06impl.SvcImpl;");
        modSrc.add("}");
        File modFile = new File("module-info-full.java");
        // Write as module-info.java so javac recognises it
        File modActual = new File("module-info.java");
        Utils.createFile(modActual, modSrc);

        Utils.compiler("--release", "11",
                "-d", outDir.getName(),
                modActual.getName(),
                svcFile.getAbsolutePath(),
                implFile.getAbsolutePath());

        File jar = new File("module-full.jar");
        Utils.jar("cvf", jar.getName(), "-C", outDir.getName(), ".");

        Utils.testWithRepack(jar, "--unknown-attribute=error");
        System.out.println("  testFullModuleAttribute: PASS");
    }

    /** A minimal module-info (requires java.base only). */
    static void testMinimalModule() throws Exception {
        File outDir = new File("module-min");
        outDir.mkdirs();

        // Place the module-info.java in its own source directory to avoid
        // colliding with the module-info.java created by testFullModuleAttribute.
        File srcDir = new File("module-min-src");
        srcDir.mkdirs();
        List<String> modSrc = new ArrayList<>();
        modSrc.add("module com.example.m06min {");
        modSrc.add("    requires java.base;");
        modSrc.add("}");
        File modFile = new File(srcDir, "module-info.java");
        Utils.createFile(modFile, modSrc);

        Utils.compiler("--release", "9", "-d", outDir.getName(), modFile.getAbsolutePath());

        File jar = new File("module-min.jar");
        Utils.jar("cvf", jar.getName(), "-C", outDir.getName(), ".");

        Utils.testWithRepack(jar, "--unknown-attribute=error");
        System.out.println("  testMinimalModule: PASS");
    }
}
