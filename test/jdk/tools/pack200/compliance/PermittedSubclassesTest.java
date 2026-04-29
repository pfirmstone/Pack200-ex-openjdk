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
 * @test C-05
 * @summary Compliance: PermittedSubclasses attribute on sealed classes survives
 *          pack/unpack (spec §7.1, attribute index 33, requires AO_HAVE_CLASS_FLAGS_HI).
 * @requires jdk.version.major >= 17
 * @compile -XDignore.symbol.file ../Utils.java PermittedSubclassesTest.java
 * @run main PermittedSubclassesTest
 */

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * C-05: PermittedSubclasses attribute round-trip.
 *
 * Compiles sealed class hierarchies (sealed class + permitted subclasses,
 * sealed interface + implementations) and verifies that pack/repack succeeds
 * with --unknown-attribute=error.
 */
public class PermittedSubclassesTest {

    public static void main(String... args) throws Exception {
        testSealedClassHierarchy();
        testSealedInterface();
        Utils.cleanup();
        System.out.println("C-05 PermittedSubclassesTest: ALL PASSED");
    }

    /** sealed class ShapeP05 permits CircleP05, RectP05 */
    static void testSealedClassHierarchy() throws Exception {
        List<String> shapeSource = new ArrayList<>();
        shapeSource.add("public sealed class ShapeP05 permits CircleP05, RectP05 {}");
        File shapeFile = new File("ShapeP05.java");
        Utils.createFile(shapeFile, shapeSource);

        List<String> circSource = new ArrayList<>();
        circSource.add("public final class CircleP05 extends ShapeP05 {}");
        File circFile = new File("CircleP05.java");
        Utils.createFile(circFile, circSource);

        List<String> rectSource = new ArrayList<>();
        rectSource.add("public final class RectP05 extends ShapeP05 {}");
        File rectFile = new File("RectP05.java");
        Utils.createFile(rectFile, rectSource);

        Utils.compiler("--release", "17",
                shapeFile.getName(), circFile.getName(), rectFile.getName());

        File jar = new File("sealed-class.jar");
        Utils.jar("cvf", jar.getName(),
                "ShapeP05.class", "CircleP05.class", "RectP05.class");

        Utils.testWithRepack(jar, "--unknown-attribute=error");
        System.out.println("  testSealedClassHierarchy: PASS");
    }

    /** sealed interface ExprP05 permits NumP05, AddP05 */
    static void testSealedInterface() throws Exception {
        List<String> ifaceSrc = new ArrayList<>();
        ifaceSrc.add("public sealed interface ExprP05 permits NumP05, AddP05 {}");
        File ifaceFile = new File("ExprP05.java");
        Utils.createFile(ifaceFile, ifaceSrc);

        List<String> numSrc = new ArrayList<>();
        numSrc.add("public record NumP05(int n) implements ExprP05 {}");
        File numFile = new File("NumP05.java");
        Utils.createFile(numFile, numSrc);

        List<String> addSrc = new ArrayList<>();
        addSrc.add("public record AddP05(ExprP05 l, ExprP05 r) implements ExprP05 {}");
        File addFile = new File("AddP05.java");
        Utils.createFile(addFile, addSrc);

        Utils.compiler("--release", "17",
                ifaceFile.getName(), numFile.getName(), addFile.getName());

        File jar = new File("sealed-iface.jar");
        Utils.jar("cvf", jar.getName(),
                "ExprP05.class", "NumP05.class", "AddP05.class");

        Utils.testWithRepack(jar, "--unknown-attribute=error");
        System.out.println("  testSealedInterface: PASS");
    }
}
