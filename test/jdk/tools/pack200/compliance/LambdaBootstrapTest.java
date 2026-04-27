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
 * @test C-09
 * @summary Compliance: BootstrapMethods table, invokedynamic bytecodes, and
 *          CONSTANT_MethodHandle / CONSTANT_MethodType CP entries survive
 *          pack/unpack (spec §6, package version 170.1+).
 * @compile -XDignore.symbol.file ../Utils.java LambdaBootstrapTest.java
 * @run main LambdaBootstrapTest
 */

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * C-09: Lambda / InvokeDynamic round-trip.
 *
 * Compiles classes that exercise invokedynamic in different forms:
 *   1. Lambda expression (LambdaMetafactory)
 *   2. Method reference (LambdaMetafactory via method handle)
 *   3. String concatenation via StringConcatFactory (Java 9+)
 * All three use BootstrapMethods with CONSTANT_MethodHandle references.
 */
public class LambdaBootstrapTest {

    public static void main(String... args) throws Exception {
        testLambdaExpression();
        testMethodReference();
        testStringConcatFactory();
        Utils.cleanup();
        System.out.println("C-09 LambdaBootstrapTest: ALL PASSED");
    }

    /**
     * A class with a lambda expression.  javac emits an invokedynamic
     * instruction referencing LambdaMetafactory.metafactory via a
     * CONSTANT_MethodHandle in the BootstrapMethods table.
     */
    static void testLambdaExpression() throws Exception {
        List<String> src = new ArrayList<>();
        src.add("import java.util.function.Supplier;");
        src.add("public class LambdaExpr09 {");
        src.add("    public static Supplier<String> supplier() {");
        src.add("        return () -> \"hello\";");
        src.add("    }");
        src.add("}");
        File java = new File("LambdaExpr09.java");
        Utils.createFile(java, src);
        Utils.compiler("--release", "11", java.getName());

        File jar = new File("lambda-expr09.jar");
        buildJarFromPrefix("LambdaExpr09", jar);
        Utils.testWithRepack(jar, "--unknown-attribute=error");
        System.out.println("  testLambdaExpression: PASS");
    }

    /**
     * A class with a method reference.  Also produces invokedynamic with a
     * CONSTANT_MethodHandle bootstrap argument.
     */
    static void testMethodReference() throws Exception {
        List<String> src = new ArrayList<>();
        src.add("import java.util.function.Function;");
        src.add("public class MethodRef09 {");
        src.add("    public static Function<String,Integer> lengthFn() {");
        src.add("        return String::length;");
        src.add("    }");
        src.add("}");
        File java = new File("MethodRef09.java");
        Utils.createFile(java, src);
        Utils.compiler("--release", "11", java.getName());

        File jar = new File("method-ref09.jar");
        buildJarFromPrefix("MethodRef09", jar);
        Utils.testWithRepack(jar, "--unknown-attribute=error");
        System.out.println("  testMethodReference: PASS");
    }

    /**
     * Java 9+ string concatenation uses StringConcatFactory (an invokedynamic)
     * with a CONSTANT_MethodHandle bootstrap.
     */
    static void testStringConcatFactory() throws Exception {
        List<String> src = new ArrayList<>();
        src.add("public class StringConcat09 {");
        src.add("    public static String greet(String name, int n) {");
        src.add("        return \"Hello \" + name + \" (\" + n + \")\";");
        src.add("    }");
        src.add("}");
        File java = new File("StringConcat09.java");
        Utils.createFile(java, src);
        // --release 9 uses StringConcatFactory for string concat
        Utils.compiler("--release", "9", java.getName());

        File jar = new File("string-concat09.jar");
        Utils.jar("cvf", jar.getName(), "StringConcat09.class");
        Utils.testWithRepack(jar, "--unknown-attribute=error");
        System.out.println("  testStringConcatFactory: PASS");
    }

    // -------------------------------------------------------------------------

    /** Jars all .class files whose names start with the given prefix. */
    static void buildJarFromPrefix(String prefix, File jar) {
        File cwd = new File(".");
        List<String> args = new ArrayList<>();
        args.add("cvf");
        args.add(jar.getName());
        for (File f : cwd.listFiles()) {
            if (f.getName().startsWith(prefix) && f.getName().endsWith(".class")) {
                args.add(f.getName());
            }
        }
        Utils.jar(args.toArray(new String[0]));
    }
}
