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
import java.util.ArrayList;
import java.util.List;

/*
 * @test
 * @summary Generates synthetic Java 17+ test JARs (records, lambdas, sealed
 *          classes, pattern matching) and measures Pack200 population-coding
 *          effectiveness on each one, printing a comparative report.
 * @requires jdk.version.major >= 17
 * @compile -XDignore.symbol.file Utils.java BenchmarkMetrics.java Java17ModernBenchmark.java
 * @run main/timeout=600 Java17ModernBenchmark
 */
public class Java17ModernBenchmark {

    public static void main(String... args) throws Exception {
        List<BenchmarkMetrics.Result> results = new ArrayList<>();

        results.add(benchmarkRecords());
        results.add(benchmarkLambdas());
        results.add(benchmarkSealedClasses());
        results.add(benchmarkPatternMatching());
        results.add(benchmarkMixed());

        BenchmarkMetrics.printReport(results);
        Utils.cleanup();
    }

    // ------------------------------------------------------------------
    // Individual benchmark cases
    // ------------------------------------------------------------------

    /**
     * Benchmark: a batch of record classes.
     *
     * <p>Records are a Java 16+ feature (JEP 395).  Each record class carries a
     * {@code Record} attribute in its class file.  With multiple records sharing
     * similar component type descriptors the population-coding step should find
     * repeated string patterns in the constant-pool bands.
     */
    static BenchmarkMetrics.Result benchmarkRecords() throws Exception {
        File outDir = new File("bench_records");
        outDir.mkdirs();

        List<String> sources = new ArrayList<>();

        // Generate a set of record classes with varying component counts.
        String[][] recordDefs = {
            {"Point2D",  "int x, int y"},
            {"Point3D",  "int x, int y, int z"},
            {"Color",    "int r, int g, int b, int alpha"},
            {"Range",    "double min, double max"},
            {"NamedRange", "String name, double min, double max"},
            {"Pair",     "Object first, Object second"},
            {"Triple",   "Object first, Object second, Object third"},
            {"Timestamp", "long epochMillis, String zone"},
            {"Version",  "int major, int minor, int patch"},
            {"Rect",     "double x, double y, double width, double height"},
        };

        for (String[] def : recordDefs) {
            String className = def[0];
            String components = def[1];
            List<String> src = new ArrayList<>();
            src.add("public record " + className + "(" + components + ") {}");
            File javaFile = new File(className + ".java");
            Utils.createFile(javaFile, src);
            sources.add(javaFile.getName());
        }

        Utils.compiler(buildCompilerArgs("--release", "17", "-d", outDir.getName(), sources));

        File jarFile = new File("bench_records.jar");
        Utils.jar("cvf", jarFile.getName(), "-C", outDir.getName(), ".");

        BenchmarkMetrics.Result result = BenchmarkMetrics.measure("Records (10 types)", jarFile);
        recursiveDelete(outDir);
        return result;
    }

    /**
     * Benchmark: classes heavy with lambda / anonymous-class usage.
     *
     * <p>Lambda expressions compiled to Java 17 produce {@code invokedynamic}
     * instructions backed by {@code LambdaMetafactory}.  With many lambdas the
     * constant-pool will contain repeated bootstrap-method descriptor strings
     * ({@code java/lang/invoke/LambdaMetafactory.metafactory}, SAM descriptors,
     * etc.) which are good candidates for population coding.
     */
    static BenchmarkMetrics.Result benchmarkLambdas() throws Exception {
        File outDir = new File("bench_lambdas");
        outDir.mkdirs();

        // Generate several classes that each define multiple lambdas.
        for (int cls = 1; cls <= 5; cls++) {
            List<String> src = new ArrayList<>();
            src.add("import java.util.function.*;");
            src.add("import java.util.*;");
            src.add("import java.util.stream.*;");
            src.add("public class LambdaClass" + cls + " {");
            // Each class gets 10 lambda-bearing methods.
            for (int m = 1; m <= 10; m++) {
                src.add("    public static Supplier<String> sup" + m
                        + "(String v) { return () -> v + \"_" + cls + "_" + m + "\"; }");
                src.add("    public static Function<String,Integer> fun" + m
                        + "() { return s -> s.length() + " + m + "; }");
                src.add("    public static Predicate<String> pred" + m
                        + "(int n) { return s -> s.length() > n; }");
                src.add("    public static List<String> filter" + m
                        + "(List<String> lst, int n) {");
                src.add("        return lst.stream().filter(s -> s.length() > n)"
                        + ".collect(Collectors.toList());");
                src.add("    }");
            }
            src.add("}");
            File javaFile = new File("LambdaClass" + cls + ".java");
            Utils.createFile(javaFile, src);
            Utils.compiler("--release", "17", "-d", outDir.getName(), javaFile.getName());
        }

        File jarFile = new File("bench_lambdas.jar");
        Utils.jar("cvf", jarFile.getName(), "-C", outDir.getName(), ".");

        BenchmarkMetrics.Result result = BenchmarkMetrics.measure("Lambdas (5×10 methods)", jarFile);
        recursiveDelete(outDir);
        return result;
    }

    /**
     * Benchmark: a sealed-class hierarchy.
     *
     * <p>Sealed classes (JEP 409, Java 17) carry a {@code PermittedSubclasses}
     * attribute.  A deep or wide hierarchy means many class-file references that
     * may share constant-pool prefixes, exercising population coding on class-name
     * and type-descriptor bands.
     */
    static BenchmarkMetrics.Result benchmarkSealedClasses() throws Exception {
        File outDir = new File("bench_sealed");
        outDir.mkdirs();

        List<String> allFiles = new ArrayList<>();

        // Each shape has a constructor parameter list (valid Java syntax).
        String[] shapes = {"BenchCircle", "BenchSquare", "BenchTriangle", "BenchPentagon",
                           "BenchHexagon", "BenchEllipse", "BenchRhombus", "BenchTrapezoid"};
        String[] ctorParams = {
            "double radius",
            "double side",
            "double base, double height",
            "double side",
            "double side",
            "double semiMajor, double semiMinor",
            "double side, double angle",
            "double parallelA, double parallelB, double height"
        };

        // Root sealed interface listing all BenchXxx permitted types.
        List<String> rootSrc = new ArrayList<>();
        rootSrc.add("public sealed interface BenchShape");
        rootSrc.add("    permits BenchCircle, BenchSquare, BenchTriangle, BenchPentagon,");
        rootSrc.add("            BenchHexagon, BenchEllipse, BenchRhombus, BenchTrapezoid {}");
        File rootFile = new File("BenchShape.java");
        Utils.createFile(rootFile, rootSrc);
        allFiles.add(rootFile.getName());

        for (int i = 0; i < shapes.length; i++) {
            List<String> src = new ArrayList<>();
            src.add("public final class " + shapes[i] + " implements BenchShape {");
            // Emit each constructor parameter as a separate field declaration.
            for (String param : ctorParams[i].split(",")) {
                src.add("    private final " + param.trim() + ";");
            }
            src.add("    public " + shapes[i] + "(" + ctorParams[i] + ") {");
            // Assign every field in the constructor (required because all are final).
            for (String param : ctorParams[i].split(",")) {
                String fieldName = param.trim().split(" ")[1];
                src.add("        this." + fieldName + " = " + fieldName + ";");
            }
            src.add("    }");
            src.add("}");
            File f = new File(shapes[i] + ".java");
            Utils.createFile(f, src);
            allFiles.add(f.getName());
        }

        Utils.compiler(buildCompilerArgs("--release", "17", "-d", outDir.getName(), allFiles));

        File jarFile = new File("bench_sealed.jar");
        Utils.jar("cvf", jarFile.getName(), "-C", outDir.getName(), ".");

        BenchmarkMetrics.Result result =
                BenchmarkMetrics.measure("Sealed hierarchy (8 impls)", jarFile);
        recursiveDelete(outDir);
        return result;
    }

    /**
     * Benchmark: pattern-matching switch expressions (Java 17 preview / Java 21).
     *
     * <p>Pattern-matching switch compiles to complex {@code tableswitch} /
     * {@code lookupswitch} + {@code checkcast} sequences and type-test patterns
     * backed by {@code invokedynamic} (in preview) or bytecode sequences.  These
     * introduce new constant-pool entry patterns compared with legacy code.
     *
     * <p>Note: Java 17 supports {@code instanceof} pattern matching (JEP 394)
     * unconditionally; switch patterns are a preview in Java 17 so we use
     * {@code instanceof} patterns only to keep the benchmark compilable at
     * {@code --release 17}.
     */
    static BenchmarkMetrics.Result benchmarkPatternMatching() throws Exception {
        File outDir = new File("bench_patterns");
        outDir.mkdirs();

        for (int cls = 1; cls <= 5; cls++) {
            List<String> src = new ArrayList<>();
            src.add("public class PatternClass" + cls + " {");
            // Use instanceof pattern matching (JEP 394, available in Java 16+).
            src.add("    public static String describe(Object obj) {");
            src.add("        if (obj instanceof Integer i) return \"int:\" + i;");
            src.add("        if (obj instanceof Long l)    return \"long:\" + l;");
            src.add("        if (obj instanceof Double d)  return \"double:\" + d;");
            src.add("        if (obj instanceof String s)  return \"string:\" + s;");
            src.add("        if (obj instanceof int[] arr) return \"int[]:\" + arr.length;");
            src.add("        return \"other:\" + obj;");
            src.add("    }");
            // Additional methods with nested instanceof checks.
            for (int m = 1; m <= 5; m++) {
                src.add("    public static int compute" + m + "(Object a, Object b) {");
                src.add("        if (a instanceof Integer ia && b instanceof Integer ib)");
                src.add("            return ia + ib + " + m + ";");
                src.add("        return -1;");
                src.add("    }");
            }
            src.add("}");
            File javaFile = new File("PatternClass" + cls + ".java");
            Utils.createFile(javaFile, src);
            Utils.compiler("--release", "17", "-d", outDir.getName(), javaFile.getName());
        }

        File jarFile = new File("bench_patterns.jar");
        Utils.jar("cvf", jarFile.getName(), "-C", outDir.getName(), ".");

        BenchmarkMetrics.Result result =
                BenchmarkMetrics.measure("Pattern matching (5 classes)", jarFile);
        recursiveDelete(outDir);
        return result;
    }

    /**
     * Benchmark: a mixed JAR combining all Java 17 feature types.
     *
     * <p>This is the most representative of a real-world library JAR that uses
     * modern Java constructs throughout.  It exercises all the constant-pool
     * patterns introduced by the previous individual benchmarks simultaneously,
     * giving population coding the best chance to find repeated strings across
     * multiple feature categories.
     */
    static BenchmarkMetrics.Result benchmarkMixed() throws Exception {
        File outDir = new File("bench_mixed");
        outDir.mkdirs();

        List<String> allFiles = new ArrayList<>();

        // --- Records ---
        String[][] recordDefs = {
            {"MixPoint",   "int x, int y"},
            {"MixRange",   "double min, double max"},
            {"MixVersion", "int major, int minor"},
        };
        for (String[] def : recordDefs) {
            List<String> src = new ArrayList<>();
            src.add("public record " + def[0] + "(" + def[1] + ") {}");
            File f = new File(def[0] + ".java");
            Utils.createFile(f, src);
            allFiles.add(f.getName());
        }

        // --- Sealed interface + implementations ---
        {
            List<String> src = new ArrayList<>();
            src.add("public sealed interface MixShape permits MixCircle, MixRect {}");
            File f = new File("MixShape.java");
            Utils.createFile(f, src);
            allFiles.add(f.getName());
        }
        {
            List<String> src = new ArrayList<>();
            src.add("public final class MixCircle implements MixShape {");
            src.add("    final double r;");
            src.add("    MixCircle(double r) { this.r = r; }");
            src.add("}");
            File f = new File("MixCircle.java");
            Utils.createFile(f, src);
            allFiles.add(f.getName());
        }
        {
            List<String> src = new ArrayList<>();
            src.add("public final class MixRect implements MixShape {");
            src.add("    final double w, h;");
            src.add("    MixRect(double w, double h) { this.w = w; this.h = h; }");
            src.add("}");
            File f = new File("MixRect.java");
            Utils.createFile(f, src);
            allFiles.add(f.getName());
        }

        // Compile the sealed hierarchy together.
        Utils.compiler(buildCompilerArgs("--release", "17", "-d", outDir.getName(), allFiles));

        // --- Lambda-heavy utility class ---
        {
            List<String> src = new ArrayList<>();
            src.add("import java.util.function.*;");
            src.add("import java.util.*;");
            src.add("import java.util.stream.*;");
            src.add("public class MixUtil {");
            for (int m = 1; m <= 8; m++) {
                src.add("    public static Supplier<String> sup" + m
                        + "(String v) { return () -> v + \"_mix_" + m + "\"; }");
                src.add("    public static Function<Integer,Integer> inc" + m
                        + "() { return n -> n + " + m + "; }");
            }
            src.add("    public static String describeShape(MixShape s) {");
            src.add("        if (s instanceof MixCircle c) return \"circle:\" + c.r;");
            src.add("        if (s instanceof MixRect   r) return \"rect:\"   + r.w + \"x\" + r.h;");
            src.add("        return \"unknown\";");
            src.add("    }");
            src.add("}");
            File f = new File("MixUtil.java");
            Utils.createFile(f, src);
            Utils.compiler("--release", "17",
                    "-cp", outDir.getName(),
                    "-d", outDir.getName(),
                    f.getName());
        }

        File jarFile = new File("bench_mixed.jar");
        Utils.jar("cvf", jarFile.getName(), "-C", outDir.getName(), ".");

        BenchmarkMetrics.Result result =
                BenchmarkMetrics.measure("Mixed (records+sealed+lambdas)", jarFile);
        recursiveDelete(outDir);
        return result;
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Builds a {@code String[]} suitable for passing to {@link Utils#compiler}
     * that contains fixed flags followed by the source-file paths in
     * {@code sourceFileNames}.
     */
    private static String[] buildCompilerArgs(String... flagsAndFiles) {
        return flagsAndFiles;
    }

    private static String[] buildCompilerArgs(String flag1, String val1,
                                               String flag2, String val2,
                                               List<String> files) {
        List<String> args = new ArrayList<>();
        args.add(flag1);
        args.add(val1);
        args.add(flag2);
        args.add(val2);
        args.addAll(files);
        return args.toArray(new String[0]);
    }

    private static void recursiveDelete(File dir) throws Exception {
        if (dir.isDirectory()) {
            File[] entries = dir.listFiles();
            if (entries != null) {
                for (File e : entries) recursiveDelete(e);
            }
        }
        dir.delete();
    }
}
