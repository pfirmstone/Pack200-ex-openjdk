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
 * @summary Population-coding benchmark for Pack200 on Java 17+ JARs.
 *          Measures pack+gzip sizes with and without population coding across
 *          several synthetic test JAR categories (records, lambdas, sealed
 *          classes, pattern matching, mixed) and prints a comparative report.
 *          The benchmark does not assert specific thresholds; it documents
 *          baseline measurements and flags regressions when the benefit
 *          unexpectedly inverts (i.e. population coding inflates rather than
 *          reduces the compressed size by more than a tolerance margin).
 * @requires jdk.version.major >= 17
 * @compile -XDignore.symbol.file Utils.java BenchmarkMetrics.java Java17ModernBenchmark.java PopulationCodingBenchmark.java
 * @run main/timeout=600 PopulationCodingBenchmark
 */
public class PopulationCodingBenchmark {

    /**
     * Tolerance in percentage points: if population coding <em>increases</em>
     * the packed+gzipped size by more than this amount the test fails.
     *
     * <p>A small positive overhead (up to {@value} pp) is accepted because
     * population coding adds header overhead that may outweigh its benefit for
     * very small JARs.  Real-world JARs of meaningful size should not exceed
     * this threshold.
     */
    static final double MAX_OVERHEAD_PERCENTAGE_POINTS = 5.0;

    public static void main(String... args) throws Exception {
        List<BenchmarkMetrics.Result> results = new ArrayList<>();

        System.out.println("PopulationCodingBenchmark: building synthetic Java 17 test JARs …");

        results.add(Java17ModernBenchmark.benchmarkRecords());
        results.add(Java17ModernBenchmark.benchmarkLambdas());
        results.add(Java17ModernBenchmark.benchmarkSealedClasses());
        results.add(Java17ModernBenchmark.benchmarkPatternMatching());
        results.add(Java17ModernBenchmark.benchmarkMixed());

        // Also benchmark classes extracted from the running JDK's java.base module
        // (java.lang package subset) as a more realistic workload.
        BenchmarkMetrics.Result javaBaseResult = benchmarkJavaBase();
        if (javaBaseResult != null) {
            results.add(javaBaseResult);
        }

        BenchmarkMetrics.printReport(results);

        // Regression check: population coding must not inflate compressed size
        // by more than MAX_OVERHEAD_PERCENTAGE_POINTS for any case.
        checkNoRegression(results);

        Utils.cleanup();
        System.out.println("PopulationCodingBenchmark: PASS");
    }

    // ------------------------------------------------------------------
    // JDK java.base benchmark
    // ------------------------------------------------------------------

    /**
     * Extracts {@code java.lang} classes from the running JDK's {@code jrt:/}
     * file system, packages them as a JAR, and measures population-coding
     * effectiveness on that realistic workload.
     *
     * <p>This benchmark is skipped gracefully on JDK 8 (no jrt:/ filesystem)
     * or in environments where the JDK image is read-only / stripped.
     *
     * @return a {@link BenchmarkMetrics.Result} or {@code null} if the JDK
     *         image is not accessible
     */
    static BenchmarkMetrics.Result benchmarkJavaBase() {
        try {
            File rtJar = Utils.createRtJar("/modules/java\\.base/java/lang/.*\\.class");
            if (!rtJar.exists() || rtJar.length() == 0) {
                System.out.println("  [skip] java.base classes not available – skipping JDK benchmark.");
                return null;
            }
            BenchmarkMetrics.Result r = BenchmarkMetrics.measure("java.lang (from jrt:/)", rtJar);
            rtJar.delete();
            return r;
        } catch (Exception ex) {
            System.out.println("  [skip] Could not extract java.base classes: " + ex.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Regression gate
    // ------------------------------------------------------------------

    /**
     * Asserts that population coding does not inflate compressed output beyond
     * {@link #MAX_OVERHEAD_PERCENTAGE_POINTS} for any result.
     *
     * <p>A negative benefit (inflation) can occur for trivially small JARs
     * because the population-coding header overhead dominates.  The threshold
     * accounts for this and is intentionally lenient; a future commit can
     * tighten it once baseline measurements are established.
     */
    static void checkNoRegression(List<BenchmarkMetrics.Result> results) {
        List<String> failures = new ArrayList<>();
        for (BenchmarkMetrics.Result r : results) {
            double benefit = r.populationCodingBenefit();
            // benefit > 0 means pop coding helps; benefit < 0 means it hurts.
            if (benefit < -MAX_OVERHEAD_PERCENTAGE_POINTS) {
                failures.add(String.format(
                        "  %s: population coding inflated size by %.2f pp (limit %.1f pp)",
                        r.name, -benefit, MAX_OVERHEAD_PERCENTAGE_POINTS));
            }
        }
        if (!failures.isEmpty()) {
            StringBuilder sb = new StringBuilder(
                    "Population-coding regression detected (pack+gz size inflated beyond tolerance):\n");
            for (String f : failures) sb.append(f).append('\n');
            throw new RuntimeException(sb.toString());
        }
    }
}
