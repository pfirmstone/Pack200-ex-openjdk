/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

package au.net.zeus.util.jar.pack;

/**
 * Computes Shannon entropy to guide population-coding decisions in
 * {@link CodingChooser}.
 *
 * <p>Population coding is most beneficial when the value sequence contains
 * a small set of high-frequency ("favoured") values.  For sequences where all
 * values are roughly equally probable (high entropy), the population-coding
 * header overhead outweighs any savings on the body.
 *
 * <p>Modern Java 17+ JARs are dominated by lambda synthetic names, record
 * component metadata, and pattern-matching bootstrap descriptors – all of which
 * exhibit near-uniform distributions (high entropy) and therefore gain no
 * measurable benefit from population coding.
 */
class EntropyAnalyzer {

    /**
     * Default normalised-entropy threshold above which population coding is
     * skipped.  A value of {@code 0.85} means: if 85% or more of the maximum
     * possible entropy (complete uniformity) is present in the band, skip
     * population coding.
     */
    static final double DEFAULT_HIGH_ENTROPY_THRESHOLD = 0.85;

    private EntropyAnalyzer() {}  // utility class

    /**
     * Calculates the <em>normalised</em> Shannon entropy of the value
     * distribution recorded in {@code hist}.
     *
     * <p>The normalised entropy is {@code H / log2(N)} where {@code H} is the
     * raw Shannon entropy (in bits) and {@code N} is the number of distinct
     * values in the histogram.  A value of {@code 1.0} indicates a perfectly
     * uniform distribution (every value occurs equally often); {@code 0.0}
     * means all occurrences are the same single value.
     *
     * <p>Returns {@code 0.0} for empty or single-valued histograms (zero
     * entropy ≡ no uncertainty ≡ population coding trivially optimal).
     *
     * @param hist the histogram to analyse
     * @return normalised entropy in {@code [0.0, 1.0]}
     */
    static double normalizedEntropy(Histogram hist) {
        int n = hist.getTotalLength(); // number of distinct values
        if (n <= 1) return 0.0;

        int total = hist.getTotalWeight();
        if (total == 0) return 0.0;

        double entropy = 0.0;
        int[] counts = hist.counts;
        for (int count : counts) {
            if (count > 0) {
                double p = (double) count / total;
                entropy -= p * (Math.log(p) / Math.log(2));
            }
        }
        // Normalise by log2(n): the maximum possible entropy for n distinct symbols.
        double maxEntropy = Math.log(n) / Math.log(2);
        return (maxEntropy > 0.0) ? (entropy / maxEntropy) : 0.0;
    }

    /**
     * Returns {@code true} if the value distribution recorded in {@code hist}
     * has a normalised entropy at or above the supplied {@code threshold}, in
     * which case population coding is unlikely to be worthwhile and should be
     * skipped.
     *
     * @param hist      histogram of the band's values
     * @param threshold normalised-entropy threshold in {@code [0.0, 1.0]}
     * @return {@code true} when the normalised entropy is ≥ {@code threshold}
     */
    static boolean isHighEntropy(Histogram hist, double threshold) {
        return normalizedEntropy(hist) >= threshold;
    }
}
