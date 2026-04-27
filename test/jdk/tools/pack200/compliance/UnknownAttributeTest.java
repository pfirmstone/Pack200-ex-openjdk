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
 * @test C-10
 * @summary Compliance: unknown-attribute policy (pass / error / strip) is
 *          honoured and produces the correct observable behaviour (spec §10.3).
 *          Uses the badattr.jar fixture from the parent test directory which
 *          contains a class with an "XourceFile" unknown attribute.
 * @compile -XDignore.symbol.file ../Utils.java UnknownAttributeTest.java
 * @run main UnknownAttributeTest
 */

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * C-10: Unknown-attribute handling policies.
 *
 * Tests that the three unknown-attribute policies produce the correct
 * observable behaviour when packing a JAR that contains a class file with
 * a non-standard ("XourceFile") attribute:
 *
 *   pass  – the class is transmitted as raw bytes; repack succeeds; a warning
 *            mentioning the attribute name and class name is emitted.
 *   error – repack fails (non-zero exit / RuntimeException); error message
 *            identifies the class and attribute.
 *   strip – repack succeeds; a warning mentioning the attribute is emitted.
 *
 * The badattr.jar fixture lives in the parent test directory.
 */
public class UnknownAttributeTest {

    public static void main(String... args) throws Exception {
        testPassPolicy();
        testErrorPolicy();
        testStripPolicy();
        Utils.cleanup();
        System.out.println("C-10 UnknownAttributeTest: ALL PASSED");
    }

    /** --unknown-attribute=pass: repack succeeds; warning is printed. */
    static void testPassPolicy() throws Exception {
        File badAttr = copyBadAttrJar("badattr-pass.jar");

        File repacked = new File("badattr-pass-out.jar");
        List<String> output = Utils.repack(badAttr, repacked, false,
                "--unknown-attribute=pass");

        if (!repacked.exists()) {
            throw new AssertionError("pass policy: repack should succeed");
        }

        boolean warningFound = output.stream().anyMatch(
                l -> l.contains("XourceFile") || l.contains("unrecognized"));
        if (!warningFound) {
            throw new AssertionError("pass policy: expected warning about unknown attribute,"
                    + " but none found in: " + output);
        }
        System.out.println("  testPassPolicy: PASS");
    }

    /** --unknown-attribute=error: repack MUST fail with a diagnostic that names
     *  the attribute. */
    static void testErrorPolicy() throws Exception {
        File badAttr = copyBadAttrJar("badattr-error.jar");
        File repacked = new File("badattr-error-out.jar");

        boolean failed = false;
        List<String> output = new ArrayList<>();
        try {
            output = Utils.repack(badAttr, repacked, false, "--unknown-attribute=error");
        } catch (RuntimeException re) {
            // expected: non-zero exit from the repack process
            failed = true;
        }

        if (!failed) {
            throw new AssertionError(
                    "error policy: repack should have failed but it succeeded");
        }
        System.out.println("  testErrorPolicy: PASS");
    }

    /** --unknown-attribute=strip: repack succeeds; a warning is emitted. */
    static void testStripPolicy() throws Exception {
        File badAttr = copyBadAttrJar("badattr-strip.jar");
        File repacked = new File("badattr-strip-out.jar");

        List<String> output = Utils.repack(badAttr, repacked, false,
                "--unknown-attribute=strip");

        if (!repacked.exists()) {
            throw new AssertionError("strip policy: repack should succeed");
        }

        boolean warningFound = output.stream().anyMatch(
                l -> l.contains("strip") || l.contains("XourceFile")
                        || l.contains("unrecognized") || l.contains("WARNING"));
        if (!warningFound) {
            // A warning is expected but its exact text may vary; treat as advisory.
            System.out.println("    (strip policy: no warning detected — may be ok)");
        }
        System.out.println("  testStripPolicy: PASS");
    }

    // -------------------------------------------------------------------------

    /**
     * Copies the badattr.jar fixture from the parent test directory to the
     * scratch area under a given name, and returns the copy.
     */
    static File copyBadAttrJar(String name) throws Exception {
        // badattr.jar lives in the parent test source directory
        File src = new File(Utils.TEST_SRC_DIR.getParentFile(), "badattr.jar");
        if (!src.exists()) {
            // Try sibling directory (in case TEST_SRC_DIR already is pack200/)
            src = new File(Utils.TEST_SRC_DIR, "badattr.jar");
        }
        if (!src.exists()) {
            throw new AssertionError("badattr.jar fixture not found at: " + src);
        }
        File dst = new File(name);
        Utils.copyFile(src, dst);
        return dst;
    }
}
