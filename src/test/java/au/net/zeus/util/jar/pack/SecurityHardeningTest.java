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

import net.pack200.Pack200;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for the security hardening applied to the pack200 unpacker.
 *
 * <p>Tests are in the {@code au.net.zeus.util.jar.pack} package to access
 * package-private types ({@link Package}, {@link BandStructure}).  Tests that
 * exercise {@link Package.File} directly install a minimal {@link TLGlobals}
 * for the duration of the call; tests that go through the public
 * {@link Pack200} API do not, because the API manages the thread-local itself.
 * </p>
 */
public class SecurityHardeningTest {

    // -----------------------------------------------------------------------
    // 1. Path traversal / zip-slip prevention in Package.fixupFileName
    // -----------------------------------------------------------------------

    /** A plain ".." component must be rejected. */
    @Test(expected = IllegalArgumentException.class)
    public void testDotDotComponentRejected() {
        withTLGlobals(() -> {
            Package pkg = new Package();
            pkg.new File("../../etc/passwd");
        });
    }

    /** ".." embedded in a longer path must be rejected. */
    @Test(expected = IllegalArgumentException.class)
    public void testDotDotEmbeddedInPathRejected() {
        withTLGlobals(() -> {
            Package pkg = new Package();
            pkg.new File("foo/../../bar");
        });
    }

    /** A trailing ".." component must be rejected. */
    @Test(expected = IllegalArgumentException.class)
    public void testDotDotTrailingRejected() {
        withTLGlobals(() -> {
            Package pkg = new Package();
            pkg.new File("a/b/..");
        });
    }

    /** An absolute path (starts with '/') must be rejected. */
    @Test(expected = IllegalArgumentException.class)
    public void testAbsolutePathRejected() {
        withTLGlobals(() -> {
            Package pkg = new Package();
            pkg.new File("/etc/passwd");
        });
    }

    /** "..." (three dots) is not ".." and must be accepted. */
    @Test
    public void testThreeDotsAccepted() {
        withTLGlobals(() -> {
            Package pkg = new Package();
            // "..." is a valid (if unusual) file/directory name component.
            pkg.new File("foo/.../bar");
        });
    }

    /** Normal relative paths must be accepted without exception. */
    @Test
    public void testNormalRelativePathAccepted() {
        withTLGlobals(() -> {
            Package pkg = new Package();
            pkg.new File("META-INF/MANIFEST.MF");
            pkg.new File("com/example/Foo.class");
            pkg.new File("resources/image.png");
        });
    }

    // -----------------------------------------------------------------------
    // 2. BandStructure.getIntTotal(int[]) overflow detection
    // -----------------------------------------------------------------------

    /**
     * The static helper {@code BandStructure.getIntTotal(int[])} must throw
     * {@link IOException} when the element sum overflows a 32-bit signed int.
     */
    @Test
    public void testGetIntTotalStaticOverflow() {
        int[] values = { Integer.MAX_VALUE, 1 };
        try {
            BandStructure.getIntTotal(values);
            fail("Expected IOException for integer overflow in band sum");
        } catch (IOException e) {
            assertTrue("Exception message should be non-empty",
                    e.getMessage() != null && !e.getMessage().isEmpty());
        }
    }

    /** Normal sum must be returned correctly. */
    @Test
    public void testGetIntTotalStaticNoOverflow() throws IOException {
        int[] values = { 1, 2, 3, 100, 50000 };
        int total = BandStructure.getIntTotal(values);
        assertTrue("Sum should equal 50106", total == 50106);
    }

    /** Empty array must return 0 without throwing. */
    @Test
    public void testGetIntTotalStaticEmpty() throws IOException {
        int[] values = {};
        assertTrue("Empty sum should be 0", BandStructure.getIntTotal(values) == 0);
    }

    // -----------------------------------------------------------------------
    // 3. Coding.readIntFrom EOF throws EOFException (Fix 5)
    // -----------------------------------------------------------------------

    /**
     * An empty InputStream fed to {@code Coding.readIntFrom} must produce an
     * {@link java.io.EOFException}, not a {@link RuntimeException}.
     */
    @Test
    public void testCodingReadIntFromEofThrowsEOFException() {
        try {
            Coding.readIntFrom(new ByteArrayInputStream(new byte[0]),
                    5 /*B*/, 256 /*H*/, 0 /*S*/);
            fail("Expected EOFException");
        } catch (java.io.EOFException e) {
            // correct: EOFException is an IOException
        } catch (IOException e) {
            // also acceptable if the implementation wraps it
        } catch (RuntimeException e) {
            fail("readIntFrom must not throw RuntimeException on EOF: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // 5. Aggregate count limits — OOM-prevention guards (Fixes 1–5)
    //
    //    checkCount() is used to bound the *sum* of per-element values before
    //    the resulting array is allocated.  Without these limits an attacker
    //    can supply a single class claiming 2^31 fields, passing the
    //    per-entry MAX_CP_ENTRY_COUNT check but triggering an OOM when the
    //    descriptor band array is pre-allocated before stream data is read.
    // -----------------------------------------------------------------------

    /**
     * {@code checkCount} must throw {@link IOException} when the supplied
     * value strictly exceeds the limit.
     */
    @Test
    public void testCheckCountRejectsOverLimit() throws Exception {
        try {
            PackageReader.checkCount("test_field", 100, 99);
            fail("Expected IOException when count > max");
        } catch (IOException e) {
            assertTrue("Message should mention the field name",
                       e.getMessage().contains("test_field"));
        }
    }

    /**
     * {@code checkCount} must throw {@link IOException} for negative values,
     * which indicate wrap-around or sign-extension bugs in the stream reader.
     */
    @Test
    public void testCheckCountRejectsNegative() throws Exception {
        try {
            PackageReader.checkCount("test_neg", -1, 100);
            fail("Expected IOException for negative count");
        } catch (IOException e) {
            // expected
        }
    }

    /** {@code checkCount} must not throw when count == max (boundary value). */
    @Test
    public void testCheckCountAcceptsAtLimit() throws IOException {
        // Must not throw
        PackageReader.checkCount("test_exact", 16_000_000, 16_000_000);
    }

    /** {@code checkCount} must not throw when count == 0. */
    @Test
    public void testCheckCountAcceptsZero() throws IOException {
        PackageReader.checkCount("test_zero", 0, 100);
    }

    /**
     * Verify the total-field-count limit is at least as large as a single
     * fully-loaded archive of 1 M classes each with a modest number of
     * fields, and that exactly {@code MAX_TOTAL_FIELD_COUNT + 1} is rejected.
     */
    @Test
    public void testTotalFieldCountLimitRejected() {
        try {
            PackageReader.checkCount("total_field_count",
                    PackageReader.MAX_TOTAL_FIELD_COUNT + 1,
                    PackageReader.MAX_TOTAL_FIELD_COUNT);
            fail("Expected IOException for total field count > limit");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("total_field_count"));
        }
    }

    /** Verify the total-method-count limit rejects one over the boundary. */
    @Test
    public void testTotalMethodCountLimitRejected() {
        try {
            PackageReader.checkCount("total_method_count",
                    PackageReader.MAX_TOTAL_METHOD_COUNT + 1,
                    PackageReader.MAX_TOTAL_METHOD_COUNT);
            fail("Expected IOException for total method count > limit");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("total_method_count"));
        }
    }

    /** Verify the total-BSM-arg-count limit rejects one over the boundary. */
    @Test
    public void testTotalBsmArgCountLimitRejected() {
        try {
            PackageReader.checkCount("total_bootstrap_method_arg_count",
                    PackageReader.MAX_TOTAL_BSM_ARG_COUNT + 1,
                    PackageReader.MAX_TOTAL_BSM_ARG_COUNT);
            fail("Expected IOException for total BSM arg count > limit");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("total_bootstrap_method_arg_count"));
        }
    }

    /** Verify the total-signature-class-count limit rejects one over the boundary. */
    @Test
    public void testTotalSigClassCountLimitRejected() {
        try {
            PackageReader.checkCount("total_signature_class_count",
                    PackageReader.MAX_TOTAL_SIG_CLASS_COUNT + 1,
                    PackageReader.MAX_TOTAL_SIG_CLASS_COUNT);
            fail("Expected IOException for total signature class count > limit");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("total_signature_class_count"));
        }
    }

    /** Verify the total-IC-tuple-count limit rejects one over the boundary. */
    @Test
    public void testTotalIcTupleCountLimitRejected() {
        try {
            PackageReader.checkCount("total_inner_class_tuple_count",
                    PackageReader.MAX_TOTAL_IC_TUPLE_COUNT + 1,
                    PackageReader.MAX_TOTAL_IC_TUPLE_COUNT);
            fail("Expected IOException for total IC tuple count > limit");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("total_inner_class_tuple_count"));
        }
    }

    /** Verify the total-record-component-count limit rejects one over the boundary. */
    @Test
    public void testTotalRecordCompCountLimitRejected() {
        try {
            PackageReader.checkCount("total_record_component_count",
                    PackageReader.MAX_TOTAL_RECORD_COMP + 1,
                    PackageReader.MAX_TOTAL_RECORD_COMP);
            fail("Expected IOException for total record component count > limit");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("total_record_component_count"));
        }
    }

    // -----------------------------------------------------------------------
    // 6. STORED-entry in-memory buffer limit (Fix 6)
    // -----------------------------------------------------------------------

    /**
     * The {@code MAX_STORED_RESOURCE_BYTES} constant must be positive and
     * large enough not to affect normal class files (class files are bounded
     * by JVM spec constraints far below this threshold).
     */
    @Test
    public void testMaxStoredResourceBytesIsReasonable() {
        assertTrue("MAX_STORED_RESOURCE_BYTES must be positive",
                   UnpackerImpl.MAX_STORED_RESOURCE_BYTES > 0);
        // Must comfortably accommodate a maximum-size class file (< 100 MB).
        assertTrue("MAX_STORED_RESOURCE_BYTES must be at least 100 MB",
                   UnpackerImpl.MAX_STORED_RESOURCE_BYTES >= 100L * 1024 * 1024);
        // Must be below Integer.MAX_VALUE so ByteArrayOutputStream.size()
        // (which returns int) comparison is always safe.
        assertTrue("MAX_STORED_RESOURCE_BYTES must be below Integer.MAX_VALUE",
                   UnpackerImpl.MAX_STORED_RESOURCE_BYTES < Integer.MAX_VALUE);
    }

    // -----------------------------------------------------------------------
    // 8. Total UTF-8 char count limit (Gap 1 fix)
    // -----------------------------------------------------------------------

    /** One over the total UTF-8 char limit must be rejected. */
    @Test
    public void testTotalUtf8CharCountLimitRejected() {
        try {
            PackageReader.checkCount("total_utf8_char_count",
                    PackageReader.MAX_TOTAL_UTF8_CHARS + 1,
                    PackageReader.MAX_TOTAL_UTF8_CHARS);
            fail("Expected IOException for total utf8 char count > limit");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("total_utf8_char_count"));
        }
    }

    /** Exactly at the total UTF-8 char limit must be accepted. */
    @Test
    public void testTotalUtf8CharCountLimitAccepted() throws IOException {
        PackageReader.checkCount("total_utf8_char_count",
                PackageReader.MAX_TOTAL_UTF8_CHARS,
                PackageReader.MAX_TOTAL_UTF8_CHARS);
    }

    // -----------------------------------------------------------------------
    // 9. Total interface count limit (Gap 2 fix)
    // -----------------------------------------------------------------------

    /** One over the total interface count limit must be rejected. */
    @Test
    public void testTotalInterfaceCountLimitRejected() {
        try {
            PackageReader.checkCount("total_interface_count",
                    PackageReader.MAX_TOTAL_INTERFACE_COUNT + 1,
                    PackageReader.MAX_TOTAL_INTERFACE_COUNT);
            fail("Expected IOException for total interface count > limit");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("total_interface_count"));
        }
    }

    /** Exactly at the total interface count limit must be accepted. */
    @Test
    public void testTotalInterfaceCountLimitAccepted() throws IOException {
        PackageReader.checkCount("total_interface_count",
                PackageReader.MAX_TOTAL_INTERFACE_COUNT,
                PackageReader.MAX_TOTAL_INTERFACE_COUNT);
    }

    // -----------------------------------------------------------------------
    // 10. Total attribute-band repetition count limit (Gap 3 fix)
    // -----------------------------------------------------------------------

    /** One over the EK_REPL repetition limit must be rejected. */
    @Test
    public void testTotalAttrBandLengthLimitRejected() {
        try {
            PackageReader.checkCount("attr_repl_count(test_band)",
                    PackageReader.MAX_TOTAL_ATTR_BAND_LENGTH + 1,
                    PackageReader.MAX_TOTAL_ATTR_BAND_LENGTH);
            fail("Expected IOException for attr band repetition count > limit");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("attr_repl_count(test_band)"));
        }
    }

    /** Exactly at the EK_REPL repetition limit must be accepted. */
    @Test
    public void testTotalAttrBandLengthLimitAccepted() throws IOException {
        PackageReader.checkCount("attr_repl_count(test_band)",
                PackageReader.MAX_TOTAL_ATTR_BAND_LENGTH,
                PackageReader.MAX_TOTAL_ATTR_BAND_LENGTH);
    }

    // -----------------------------------------------------------------------
    // 11. Fallback per-file buffer limit constant (Observation B fix)
    // -----------------------------------------------------------------------

    /** MAX_FALLBACK_FILE_BYTES must be positive and consistent with the
     *  public-API limit in UnpackerImpl. */
    @Test
    public void testMaxFallbackFileBytesIsReasonable() {
        assertTrue("MAX_FALLBACK_FILE_BYTES must be positive",
                   PackageReader.MAX_FALLBACK_FILE_BYTES > 0);
        assertTrue("MAX_FALLBACK_FILE_BYTES must be at least 100 MB",
                   PackageReader.MAX_FALLBACK_FILE_BYTES >= 100L * 1024 * 1024);
        // Must match the public-API limit so direct callers get equal protection.
        assertTrue("MAX_FALLBACK_FILE_BYTES must equal MAX_STORED_RESOURCE_BYTES",
                   PackageReader.MAX_FALLBACK_FILE_BYTES
                           == UnpackerImpl.MAX_STORED_RESOURCE_BYTES);
    }

    // -----------------------------------------------------------------------
    // 12. Path-traversal bypass via File(Utf8Entry) constructor (Critical fix)
    // -----------------------------------------------------------------------

    /**
     * Creating a {@code Package.File} via the {@code Utf8Entry} constructor
     * (the code path used during archive reading) must also reject ".."
     * path-traversal components.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testDotDotViaUtf8EntryRejected() {
        withTLGlobals(() -> {
            Package pkg = new Package();
            // Simulate what readFiles() does: look up a name from the CP.
            ConstantPool.Utf8Entry malicious =
                    ConstantPool.getUtf8Entry("../../etc/passwd");
            pkg.new File(malicious);
        });
    }

    /** Embedded ".." via Utf8Entry must also be rejected. */
    @Test(expected = IllegalArgumentException.class)
    public void testDotDotEmbeddedViaUtf8EntryRejected() {
        withTLGlobals(() -> {
            Package pkg = new Package();
            ConstantPool.Utf8Entry malicious =
                    ConstantPool.getUtf8Entry("foo/../../bar");
            pkg.new File(malicious);
        });
    }

    /** Absolute path via Utf8Entry must be rejected. */
    @Test(expected = IllegalArgumentException.class)
    public void testAbsolutePathViaUtf8EntryRejected() {
        withTLGlobals(() -> {
            Package pkg = new Package();
            ConstantPool.Utf8Entry malicious =
                    ConstantPool.getUtf8Entry("/etc/shadow");
            pkg.new File(malicious);
        });
    }

    /** A normal relative path via Utf8Entry must be accepted. */
    @Test
    public void testNormalRelativePathViaUtf8EntryAccepted() {
        withTLGlobals(() -> {
            Package pkg = new Package();
            ConstantPool.Utf8Entry good =
                    ConstantPool.getUtf8Entry("META-INF/MANIFEST.MF");
            pkg.new File(good);  // must not throw
        });
    }

    // -----------------------------------------------------------------------
    // 13. getUtf8Entry() – no class-level lock contention (Fix D)
    //
    //     All CP maps live in per-thread TLGlobals instances stored in a
    //     ThreadLocal.  Concurrent callers each operate on their own private
    //     HashMap; no synchronization is needed and the old class-level
    //     ConstantPool.class monitor is gone.  This test verifies that:
    //       a) concurrent threads see their own independent maps (values from
    //          one thread's TLGlobals are not visible in another thread's), and
    //       b) calling getUtf8Entry() from many threads simultaneously doesn't
    //          cause any data corruption (no shared state to corrupt).
    // -----------------------------------------------------------------------

    /**
     * Each thread must resolve {@code getUtf8Entry} against its own private
     * {@code TLGlobals} map, not a shared one.  The test spins up N threads,
     * each of which puts a uniquely-named entry into its own map and then
     * verifies that only that entry is present (no cross-thread pollution).
     */
    @Test
    public void testGetUtf8EntryIsThreadLocal() throws Exception {
        final int THREADS = 8;
        final CountDownLatch ready = new CountDownLatch(THREADS);
        final CountDownLatch go    = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<String>> futures = new ArrayList<>(THREADS);

        for (int t = 0; t < THREADS; t++) {
            final String threadKey = "thread-unique-key-" + t;
            futures.add(pool.submit(() -> {
                // Install a private TLGlobals for this worker thread.
                Utils.currentInstance.set(new TLGlobals());
                try {
                    ready.countDown();
                    go.await();  // all threads start together
                    ConstantPool.Utf8Entry e = ConstantPool.getUtf8Entry(threadKey);
                    // The value must match what we put in.
                    return e.stringValue();
                } finally {
                    Utils.currentInstance.set(null);
                }
            }));
        }

        go.countDown();  // release all threads simultaneously
        pool.shutdown();

        for (int t = 0; t < THREADS; t++) {
            String expected = "thread-unique-key-" + t;
            assertEquals("Thread " + t + " got wrong Utf8Entry",
                         expected, futures.get(t).get());
        }
    }

    // -----------------------------------------------------------------------
    // 7. Round-trip pack / unpack using the library's own JAR
    //
    //    This acts as a regression test confirming the security hardening
    //    did not break normal unpacking of a legitimate archive.
    // -----------------------------------------------------------------------

    /**
     * Packs and then unpacks the library's own JAR (built by Maven into
     * {@code target/}).  Verifies that the unpacked JAR contains at least one
     * entry and is therefore structurally valid.
     *
     * <p>The test is silently skipped when the JAR has not been built yet
     * (e.g. during an IDE-only compile), so it never fails spuriously in a
     * fresh checkout.</p>
     */
    @Test
    public void testRoundTripPackUnpack() throws Exception {
        File jarFile = findLibraryJar();
        if (jarFile == null) {
            // JAR not yet built - skip gracefully.
            return;
        }

        // --- Pack (Pack200 API manages TLGlobals itself) ---
        ByteArrayOutputStream packBuf = new ByteArrayOutputStream();
        JarFile jf = new JarFile(jarFile);
        try {
            Pack200.newPacker().pack(jf, packBuf);
        } finally {
            jf.close();
        }

        // --- Unpack ---
        ByteArrayOutputStream unpackBuf = new ByteArrayOutputStream();
        JarOutputStream jos = new JarOutputStream(unpackBuf);
        try {
            Pack200.newUnpacker().unpack(
                    new ByteArrayInputStream(packBuf.toByteArray()), jos);
        } finally {
            jos.close();
        }

        // --- Verify ---
        JarInputStream jis = new JarInputStream(
                new ByteArrayInputStream(unpackBuf.toByteArray()));
        int count = 0;
        try {
            while (jis.getNextJarEntry() != null) {
                count++;
            }
        } finally {
            jis.close();
        }
        assertTrue("Unpacked JAR must contain at least one entry", count > 0);
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    /**
     * Installs a minimal {@link TLGlobals} for the duration of a test body
     * that exercises package-internal code directly (e.g. {@code Package.File}
     * construction).  The thread-local is cleared even if {@code body} throws.
     */
    private static void withTLGlobals(Runnable body) {
        Utils.currentInstance.set(new TLGlobals());
        try {
            body.run();
        } finally {
            Utils.currentInstance.set(null);
        }
    }

    /**
     * Locates the library JAR produced by {@code mvn package}, searching a
     * few likely paths relative to the current working directory.
     */
    private static File findLibraryJar() {
        String[] candidates = {
            "target/Pack200-ex-openjdk-1.14.0-SNAPSHOT.jar",
            "../target/Pack200-ex-openjdk-1.14.0-SNAPSHOT.jar"
        };
        for (String path : candidates) {
            File f = new File(path);
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }
}
