/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
 */

package au.net.zeus.util.jar.pack;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for {@link EntropyAnalyzer}.
 *
 * <p>Every test is purely functional: the methods under test take plain arrays
 * or {@link Histogram} objects and return primitives or {@code boolean}s, so
 * no Pack200 infrastructure (TLGlobals, PropMap, …) is needed.</p>
 */
public class EntropyAnalyzerTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Builds a {@link Histogram} from a raw int array (duplicates allowed). */
    private static Histogram hist(int... values) {
        return new Histogram(values);
    }

    // -----------------------------------------------------------------------
    // 1. normalizedEntropy – edge cases
    // -----------------------------------------------------------------------

    /**
     * An empty histogram has no events; the method must return 0.0 rather
     * than NaN or throw.
     */
    @Test
    public void testNormalizedEntropyEmpty() {
        Histogram h = hist(/* empty */);
        assertEquals("Empty histogram must return 0.0",
                0.0, EntropyAnalyzer.normalizedEntropy(h), 0.0);
    }

    /**
     * A histogram containing a single distinct value (regardless of how many
     * times it appears) has zero entropy – the next symbol is perfectly
     * predictable.
     */
    @Test
    public void testNormalizedEntropySingleValue() {
        Histogram h = hist(42, 42, 42, 42);
        assertEquals("Single-value histogram must return 0.0",
                0.0, EntropyAnalyzer.normalizedEntropy(h), 0.0);
    }

    /**
     * A histogram where every value appears exactly once (uniform
     * distribution) has maximum normalised entropy = 1.0.
     */
    @Test
    public void testNormalizedEntropyUniform() {
        // Four distinct values, each appearing once → fully uniform.
        Histogram h = hist(1, 2, 3, 4);
        assertEquals("Uniform distribution must return 1.0",
                1.0, EntropyAnalyzer.normalizedEntropy(h), 1e-9);
    }

    /**
     * A histogram with a highly skewed distribution (one dominant value)
     * must have low normalised entropy, well below 0.5.
     */
    @Test
    public void testNormalizedEntropySkewed() {
        // Value 0 appears 97 times; values 1 and 2 appear once each.
        int[] vals = new int[99];
        vals[97] = 1;
        vals[98] = 2;
        Histogram h = hist(vals);
        double e = EntropyAnalyzer.normalizedEntropy(h);
        assertTrue("Skewed distribution must have entropy < 0.5, got " + e,
                e < 0.5);
    }

    /**
     * The normalised entropy must always be in {@code [0.0, 1.0]}.
     */
    @Test
    public void testNormalizedEntropyBounds() {
        // A random-looking distribution of small integers.
        Histogram h = hist(0, 1, 2, 3, 0, 1, 5, 0, 2, 8);
        double e = EntropyAnalyzer.normalizedEntropy(h);
        assertTrue("Normalised entropy must be >= 0.0, got " + e, e >= 0.0);
        assertTrue("Normalised entropy must be <= 1.0, got " + e, e <= 1.0);
    }

    // -----------------------------------------------------------------------
    // 2. isHighEntropy – threshold checks
    // -----------------------------------------------------------------------

    /**
     * A uniform distribution (entropy = 1.0) must be considered high-entropy
     * for any reasonable threshold below 1.0.
     */
    @Test
    public void testIsHighEntropyUniformAboveThreshold() {
        Histogram h = hist(1, 2, 3, 4, 5, 6, 7, 8);
        assertTrue("Uniform distribution must be high-entropy",
                EntropyAnalyzer.isHighEntropy(h, 0.85));
    }

    /**
     * A single-value distribution (entropy = 0.0) must never be considered
     * high-entropy.
     */
    @Test
    public void testIsHighEntropyZeroBelowThreshold() {
        Histogram h = hist(7, 7, 7, 7, 7);
        assertFalse("Zero-entropy histogram must NOT be high-entropy",
                EntropyAnalyzer.isHighEntropy(h, 0.85));
    }

    /**
     * The threshold boundary: entropy == threshold must be considered
     * high-entropy (≥ comparison).
     */
    @Test
    public void testIsHighEntropyAtExactThreshold() {
        // Build a histogram whose normalised entropy we can check is non-zero.
        Histogram h = hist(1, 2, 3, 4, 5, 6, 7, 8);
        double e = EntropyAnalyzer.normalizedEntropy(h);
        // Setting the threshold exactly to e should match.
        assertTrue("Entropy at exactly the threshold must be high-entropy",
                EntropyAnalyzer.isHighEntropy(h, e));
    }

    /**
     * The default threshold constant must itself be a value in (0, 1).
     */
    @Test
    public void testDefaultThresholdInRange() {
        double t = EntropyAnalyzer.DEFAULT_HIGH_ENTROPY_THRESHOLD;
        assertTrue("Default threshold must be > 0", t > 0.0);
        assertTrue("Default threshold must be < 1", t < 1.0);
    }

    // -----------------------------------------------------------------------
    // 3. lambdaSyntheticCount – pattern matching
    // -----------------------------------------------------------------------

    /**
     * An empty string array must return 0 without throwing.
     */
    @Test
    public void testLambdaSyntheticCountEmpty() {
        assertEquals(0, EntropyAnalyzer.lambdaSyntheticCount(new String[0]));
    }

    /**
     * Strings with no lambda-like patterns must return 0.
     */
    @Test
    public void testLambdaSyntheticCountNoMatch() {
        String[] s = { "main", "run", "com/example/Foo", "Ljava/lang/String;" };
        assertEquals(0, EntropyAnalyzer.lambdaSyntheticCount(s));
    }

    /**
     * The {@code lambda$} pattern (compiler-generated lambda body method)
     * must be counted.
     */
    @Test
    public void testLambdaSyntheticCountLambdaDollar() {
        String[] s = { "lambda$main$0", "lambda$run$1", "normalMethod" };
        assertEquals(2, EntropyAnalyzer.lambdaSyntheticCount(s));
    }

    /**
     * The {@code $$Lambda$} pattern (synthetic lambda class name) must be
     * counted.
     */
    @Test
    public void testLambdaSyntheticCountDoubleDollarLambda() {
        String[] s = { "Outer$$Lambda$1/0x00007f", "OtherClass", "main" };
        assertEquals(1, EntropyAnalyzer.lambdaSyntheticCount(s));
    }

    /**
     * A string containing both patterns must be counted only once.
     */
    @Test
    public void testLambdaSyntheticCountBothPatternsOneEntry() {
        // Contrived: contains both substrings in one string – still one entry.
        String[] s = { "lambda$$$Lambda$foo" };
        assertEquals("Both patterns in one string still counts as 1",
                1, EntropyAnalyzer.lambdaSyntheticCount(s));
    }

    /**
     * A realistic mix of lambda and non-lambda names.
     */
    @Test
    public void testLambdaSyntheticCountMixed() {
        String[] s = {
            "main",
            "lambda$main$0",
            "lambda$run$1",
            "lambda$run$2",
            "Outer$$Lambda$4",
            "Outer$$Lambda$5",
            "doWork",
            "Ljava/lang/String;"
        };
        assertEquals(5, EntropyAnalyzer.lambdaSyntheticCount(s));
    }

    // -----------------------------------------------------------------------
    // 4. distinctRecordTypeCount – repetition analysis
    // -----------------------------------------------------------------------

    /**
     * An empty type array must return 0 without throwing.
     */
    @Test
    public void testDistinctRecordTypeCountEmpty() {
        assertEquals(0, EntropyAnalyzer.distinctRecordTypeCount(new String[0]));
    }

    /**
     * A single element must return 1.
     */
    @Test
    public void testDistinctRecordTypeCountSingle() {
        assertEquals(1, EntropyAnalyzer.distinctRecordTypeCount(
                new String[] { "Ljava/lang/String;" }));
    }

    /**
     * All-identical elements must return 1.
     */
    @Test
    public void testDistinctRecordTypeCountAllSame() {
        String[] types = { "I", "I", "I", "I", "I" };
        assertEquals("All-same types must have 1 distinct",
                1, EntropyAnalyzer.distinctRecordTypeCount(types));
    }

    /**
     * All-distinct elements must return the array length.
     */
    @Test
    public void testDistinctRecordTypeCountAllDifferent() {
        String[] types = { "I", "D", "Ljava/lang/String;", "Z", "B" };
        assertEquals("All-different types must return array length",
                types.length, EntropyAnalyzer.distinctRecordTypeCount(types));
    }

    /**
     * A realistic record-component type distribution (String, int, boolean
     * repeating across many records) must return the correct distinct count.
     */
    @Test
    public void testDistinctRecordTypeCountRealistic() {
        // 10 records each with 3 components: String, int, boolean
        String[] types = new String[30];
        for (int i = 0; i < 30; i += 3) {
            types[i]     = "Ljava/lang/String;";
            types[i + 1] = "I";
            types[i + 2] = "Z";
        }
        assertEquals("Three distinct types across 30 entries",
                3, EntropyAnalyzer.distinctRecordTypeCount(types));
    }

    /**
     * The input array must not be mutated by {@code distinctRecordTypeCount}.
     */
    @Test
    public void testDistinctRecordTypeCountDoesNotMutate() {
        String[] original = { "I", "D", "I", "Z", "D" };
        String[] copy = original.clone();
        EntropyAnalyzer.distinctRecordTypeCount(original);
        assertArrayEquals("Input array must not be mutated", copy, original);
    }
}
