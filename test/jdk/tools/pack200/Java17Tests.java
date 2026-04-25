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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/*
 * @test
 * @summary Tests pack/unpack round-trip for Java 17 class files (major version 61),
 *          exercising records (Record attribute), sealed classes
 *          (PermittedSubclasses attribute), and lambdas (BootstrapMethods /
 *          invokedynamic).
 * @compile -XDignore.symbol.file Utils.java Java17Tests.java
 * @run main Java17Tests
 */
public class Java17Tests {

    public static void main(String... args) throws Exception {
        testSimpleClassAtVersion61();
        testRecordAtVersion61();
        testSealedClassAtVersion61();
        testLambdaAtVersion61();
        Utils.cleanup();
    }

    /**
     * Baseline: a plain class compiled to Java 17 (version 61.0) is packed
     * and unpacked without error.
     */
    static void testSimpleClassAtVersion61() throws Exception {
        List<String> src = new ArrayList<String>();
        src.add("public class Hello17 {");
        src.add("    public static String greeting() { return \"Hello Java 17\"; }");
        src.add("}");
        File javaFile = new File("Hello17.java");
        Utils.createFile(javaFile, src);
        Utils.compiler("--release", "17", javaFile.getName());

        File jarFile = new File("simple17.jar");
        Utils.jar("cvf", jarFile.getName(), "Hello17.class");
        Utils.testWithRepack(jarFile, "--unknown-attribute=pass");
        System.out.println("testSimpleClassAtVersion61: PASS");
    }

    /**
     * Record class compiled to Java 17: the class file carries a {@code Record}
     * attribute.  Verifies that pack/unpack preserves the attribute unchanged.
     */
    static void testRecordAtVersion61() throws Exception {
        List<String> src = new ArrayList<String>();
        src.add("public record Point(int x, int y) {");
        src.add("    public double distance() {");
        src.add("        return Math.sqrt(x * (double) x + y * (double) y);");
        src.add("    }");
        src.add("}");
        File javaFile = new File("Point.java");
        Utils.createFile(javaFile, src);
        Utils.compiler("--release", "17", javaFile.getName());

        File jarFile = new File("record17.jar");
        Utils.jar("cvf", jarFile.getName(), "Point.class");
        Utils.testWithRepack(jarFile, "--unknown-attribute=pass");
        System.out.println("testRecordAtVersion61: PASS");
    }

    /**
     * Sealed-class hierarchy compiled to Java 17: {@code Shape.class} carries a
     * {@code PermittedSubclasses} attribute listing {@code Circle} and
     * {@code Rectangle}.  Verifies that pack/unpack preserves the attribute.
     */
    static void testSealedClassAtVersion61() throws Exception {
        File outDir = new File("sealed17");
        outDir.mkdirs();

        List<String> shapeSrc = new ArrayList<String>();
        shapeSrc.add("public sealed class Shape17 permits Circle17, Rectangle17 {}");
        File shapeFile = new File("Shape17.java");
        Utils.createFile(shapeFile, shapeSrc);

        List<String> circleSrc = new ArrayList<String>();
        circleSrc.add("public final class Circle17 extends Shape17 {");
        circleSrc.add("    final double radius;");
        circleSrc.add("    Circle17(double r) { this.radius = r; }");
        circleSrc.add("}");
        File circleFile = new File("Circle17.java");
        Utils.createFile(circleFile, circleSrc);

        List<String> rectSrc = new ArrayList<String>();
        rectSrc.add("public final class Rectangle17 extends Shape17 {");
        rectSrc.add("    final double width, height;");
        rectSrc.add("    Rectangle17(double w, double h) { width = w; height = h; }");
        rectSrc.add("}");
        File rectFile = new File("Rectangle17.java");
        Utils.createFile(rectFile, rectSrc);

        // All three files must be compiled together so the compiler can resolve
        // the sealed-class permit references.
        Utils.compiler("--release", "17",
                "-d", outDir.getName(),
                shapeFile.getName(), circleFile.getName(), rectFile.getName());

        File jarFile = new File("sealed17.jar");
        Utils.jar("cvf", jarFile.getName(), "-C", outDir.getName(), ".");
        Utils.testWithRepack(jarFile, "--unknown-attribute=pass");
        System.out.println("testSealedClassAtVersion61: PASS");
    }

    /**
     * Class using a lambda expression compiled to Java 17: the class file
     * carries a {@code BootstrapMethods} attribute and an {@code invokedynamic}
     * instruction backed by {@code LambdaMetafactory}.  Verifies that pack/unpack
     * preserves the bootstrap-method entries correctly.
     */
    static void testLambdaAtVersion61() throws Exception {
        List<String> src = new ArrayList<String>();
        src.add("import java.util.function.Supplier;");
        src.add("public class Lambda17 {");
        src.add("    public static Supplier<String> makeSupplier(final String v) {");
        src.add("        return new Supplier<String>() {");
        src.add("            public String get() { return v.toUpperCase(); }");
        src.add("        };");
        src.add("    }");
        src.add("}");
        File javaFile = new File("Lambda17.java");
        Utils.createFile(javaFile, src);
        Utils.compiler("--release", "17", javaFile.getName());

        // Jar both the outer class and any anonymous inner classes the compiler
        // may have generated (e.g. Lambda17$1.class).
        File jarFile = new File("lambda17.jar");
        Utils.jar("cvf", jarFile.getName(), ".");
        Utils.testWithRepack(jarFile, "--unknown-attribute=pass");
        System.out.println("testLambdaAtVersion61: PASS");
    }
}
