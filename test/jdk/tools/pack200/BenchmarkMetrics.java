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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.GZIPOutputStream;
import net.pack200.Pack200;

/**
 * Tracks and reports compression metrics for Pack200 population coding benchmarks.
 *
 * <p>For each test case this class records:
 * <ul>
 *   <li>Original (uncompressed) JAR size in bytes</li>
 *   <li>Packed+gzipped size <em>with</em> population coding enabled (the default)</li>
 *   <li>Packed+gzipped size <em>without</em> population coding</li>
 * </ul>
 * From those three numbers it derives the compression ratio and the absolute /
 * relative contribution of population coding.
 */
class BenchmarkMetrics {

    /**
     * Internal property key used by CodingChooser to disable population coding.
     * Setting this to {@code "true"} in the packer's property map turns off
     * population-based coding for the pack operation.
     */
    static final String NO_POPULATION_CODING =
            "au.net.zeus.util.jar.pack.no.population.coding";

    // -----------------------------------------------------------------------
    // Per-case result container
    // -----------------------------------------------------------------------

    static class Result {
        final String name;
        final long originalBytes;
        final long packedWithPopBytes;
        final long packedNoPopBytes;

        Result(String name, long originalBytes,
               long packedWithPopBytes, long packedNoPopBytes) {
            this.name              = name;
            this.originalBytes     = originalBytes;
            this.packedWithPopBytes = packedWithPopBytes;
            this.packedNoPopBytes  = packedNoPopBytes;
        }

        double ratioWithPop() {
            return (double) packedWithPopBytes / originalBytes * 100.0;
        }

        double ratioNoPop() {
            return (double) packedNoPopBytes / originalBytes * 100.0;
        }

        /** Savings from population coding as percentage points of original size. */
        double populationCodingBenefit() {
            return ratioNoPop() - ratioWithPop();
        }
    }

    // -----------------------------------------------------------------------
    // Measurement helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the number of bytes in the given file.
     */
    static long fileSize(File f) {
        return f.length();
    }

    /**
     * Packs {@code jarFile} into a byte array using Pack200 followed by GZIP
     * compression, and returns the resulting size in bytes.
     *
     * @param jarFile            the JAR to pack
     * @param disablePopCoding   when {@code true} the population-coding step is
     *                           disabled via the internal property
     *                           {@value #NO_POPULATION_CODING}
     */
    static long measurePackedGzippedSize(File jarFile, boolean disablePopCoding)
            throws IOException {
        Pack200.Packer packer = Pack200.newPacker();
        java.util.Map<String, String> props = packer.properties();
        // Use maximum effort for reproducible, deterministic measurements.
        props.put(Pack200.Packer.EFFORT, "9");
        // Keep file order so results are comparable across runs.
        props.put(Pack200.Packer.KEEP_FILE_ORDER, Pack200.Packer.TRUE);
        // Deflate hint: keep stored so raw class bytes are passed through.
        props.put(Pack200.Packer.DEFLATE_HINT, Pack200.Packer.FALSE);
        if (disablePopCoding) {
            props.put(NO_POPULATION_CODING, "true");
        }

        // Pack into an in-memory byte array, then GZIP it.
        ByteArrayOutputStream packBuf = new ByteArrayOutputStream();
        try (JarFile jf = new JarFile(jarFile)) {
            packer.pack(jf, packBuf);
        }

        ByteArrayOutputStream gzipBuf = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(gzipBuf)) {
            gzos.write(packBuf.toByteArray());
        }
        return gzipBuf.size();
    }

    /**
     * Measures compression metrics for a single JAR and returns a {@link Result}.
     *
     * @param name    human-readable label for the test case
     * @param jarFile the JAR to measure
     */
    static Result measure(String name, File jarFile) throws IOException {
        long original    = fileSize(jarFile);
        long withPop     = measurePackedGzippedSize(jarFile, false);
        long withoutPop  = measurePackedGzippedSize(jarFile, true);
        return new Result(name, original, withPop, withoutPop);
    }

    // -----------------------------------------------------------------------
    // Reporting
    // -----------------------------------------------------------------------

    /**
     * Prints a formatted benchmark report to {@link System#out}.
     *
     * <p>The table columns are:
     * <ol>
     *   <li>Test-case name</li>
     *   <li>Original JAR size (bytes)</li>
     *   <li>Pack+GZ with population coding (bytes + % of original)</li>
     *   <li>Pack+GZ without population coding (bytes + % of original)</li>
     *   <li>Population-coding benefit (percentage points saved)</li>
     * </ol>
     */
    static void printReport(List<Result> results) {
        System.out.println();
        System.out.println("=================================================================");
        System.out.println("  Population Coding Benchmark – Java 17+ JAR Compression Report");
        System.out.println("=================================================================");
        System.out.printf("%-30s  %10s  %18s  %18s  %10s%n",
                "Test Case", "Orig(B)", "Pack+GZ w/Pop", "Pack+GZ no/Pop", "Pop Benefit");
        System.out.println("-----------------------------------------------------------------");
        for (Result r : results) {
            System.out.printf("%-30s  %10d  %10d(%5.1f%%)  %10d(%5.1f%%)  %+8.2f pp%n",
                    r.name,
                    r.originalBytes,
                    r.packedWithPopBytes, r.ratioWithPop(),
                    r.packedNoPopBytes,   r.ratioNoPop(),
                    r.populationCodingBenefit());
        }
        System.out.println("=================================================================");
        System.out.println();

        // Summary line.
        if (!results.isEmpty()) {
            double avgBenefit = results.stream()
                    .mapToDouble(Result::populationCodingBenefit)
                    .average()
                    .orElse(0.0);
            System.out.printf("Average population-coding benefit across %d test(s): %+.2f pp%n",
                    results.size(), avgBenefit);
            System.out.println("  (positive = population coding reduces pack+gz size)");
            System.out.println();
        }
    }
}
