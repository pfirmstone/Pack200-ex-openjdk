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
 * @test C-03
 * @summary Compliance: NestHost and NestMembers attributes survive pack/unpack
 *          without triggering --unknown-attribute=pass (spec §7.1, indices 25-26).
 *          Verifies that the attributes are natively encoded and are therefore
 *          preserved without raw-byte pass-through warnings.
 * @requires jdk.version.major >= 11
 * @compile -XDignore.symbol.file ../Utils.java NestHostMembersTest.java
 * @run main NestHostMembersTest
 */

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * C-03: NestHost / NestMembers round-trip.
 *
 * Compiles a class containing an inner class (which forces javac to emit
 * NestHost on the inner class and NestMembers on the outer class), then
 * verifies that pack/repack succeeds with --unknown-attribute=error (i.e.,
 * the attributes are natively compressed, not passed through as raw bytes).
 */
public class NestHostMembersTest {

    public static void main(String... args) throws Exception {
        testNestHostMembersRoundTrip();
        testLambdaInnerClassNesting();
        Utils.cleanup();
        System.out.println("C-03 NestHostMembersTest: ALL PASSED");
    }

    /**
     * Outer class with an explicit inner class.  javac emits NestHost in the
     * inner class and NestMembers in the outer class.
     */
    static void testNestHostMembersRoundTrip() throws Exception {
        List<String> src = new ArrayList<>();
        src.add("public class NestOuter {");
        src.add("    public static class NestInner {");
        src.add("        public void run() {}");
        src.add("    }");
        src.add("}");
        File javaFile = new File("NestOuter.java");
        Utils.createFile(javaFile, src);
        Utils.compiler("--release", "11", javaFile.getName());

        File jarFile = new File("nesting.jar");
        Utils.jar("cvf", jarFile.getName(), "NestOuter.class", "NestOuter$NestInner.class");

        // --unknown-attribute=error: any attribute not natively handled causes failure
        Utils.testWithRepack(jarFile, "--unknown-attribute=error");
        System.out.println("  testNestHostMembersRoundTrip: PASS");
    }

    /**
     * A class that uses a lambda forces an anonymous inner class, which also
     * carries NestHost / NestMembers attributes in Java 11+.
     */
    static void testLambdaInnerClassNesting() throws Exception {
        List<String> src = new ArrayList<>();
        src.add("import java.util.function.Supplier;");
        src.add("public class LambdaNest {");
        src.add("    Supplier<String> s = () -> \"hello\";");
        src.add("}");
        File javaFile = new File("LambdaNest.java");
        Utils.createFile(javaFile, src);
        Utils.compiler("--release", "11", javaFile.getName());

        // jar all generated class files (LambdaNest.class and any $... classes)
        File cwd = new File(".");
        List<String> classFiles = new ArrayList<>();
        for (File f : cwd.listFiles()) {
            if (f.getName().startsWith("LambdaNest") && f.getName().endsWith(".class")) {
                classFiles.add(f.getName());
            }
        }
        List<String> jarArgs = new ArrayList<>();
        jarArgs.add("cvf");
        jarArgs.add("lambdanest.jar");
        jarArgs.addAll(classFiles);
        Utils.jar(jarArgs.toArray(new String[0]));

        Utils.testWithRepack(new File("lambdanest.jar"), "--unknown-attribute=error");
        System.out.println("  testLambdaInnerClassNesting: PASS");
    }
}
