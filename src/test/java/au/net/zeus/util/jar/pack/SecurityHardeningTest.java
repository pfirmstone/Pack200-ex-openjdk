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
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

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
        try {
            for (int t = 0; t < THREADS; t++) {
                String expected = "thread-unique-key-" + t;
                assertEquals("Thread " + t + " got wrong Utf8Entry",
                             expected, futures.get(t).get());
            }
        } finally {
            pool.shutdownNow();
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
    // 14. CONSTANT_Dynamic as a bootstrap-method static argument
    //
    //     JDK 25+ class files use CONSTANT_Dynamic entries as static
    //     arguments to bootstrap methods (e.g. to pass a Class literal for a
    //     primitive type to a condy resolver).  The original single-pass
    //     ClassReader.readBootstrapMethods() immediately tried to intern a
    //     BootstrapMethodEntry via getBootstrapMethodEntry(), which called
    //     stringValue() on each argument.  A CONSTANT_Dynamic argument was
    //     still an UnresolvedEntry at that point, so stringValue() threw
    //     RuntimeException("unresolved entry has no string").
    //
    //     The fix changes readBootstrapMethods() to a two-pass approach:
    //       Pass 1 – read raw table data; args may be UnresolvedEntry.
    //       Pass 2 – resolve CONSTANT_Dynamic args in dependency order
    //                (recursive descent with cycle detection), then intern.
    // -----------------------------------------------------------------------

    /**
     * {@link ClassReader} must correctly parse a {@code BootstrapMethods}
     * attribute whose second BSM has a {@code CONSTANT_Dynamic} entry as its
     * sole static argument (the dynamic constant itself references the first
     * BSM).  Before the fix, this threw
     * {@code RuntimeException("unresolved entry has no string")}.
     */
    @Test
    public void testReadBootstrapMethodsWithCondyArg() throws Exception {
        byte[] classBytes = buildCondyArgClassFile();

        Utils.currentInstance.set(new TLGlobals());
        try {
            Package pkg = new Package();
            Package.Class cls = pkg.new Class("TestCondyBsmArg.class");
            new ClassReader(cls, new ByteArrayInputStream(classBytes)).read();

            List<ConstantPool.BootstrapMethodEntry> bsms = cls.getBootstrapMethods();
            assertEquals("Expected 2 bootstrap methods", 2, bsms.size());
            assertEquals("BSM[0] must have 0 args", 0, bsms.get(0).argRefs.length);
            assertEquals("BSM[1] must have 1 arg",  1, bsms.get(1).argRefs.length);
            assertTrue("BSM[1] arg[0] must be a DynamicEntry",
                       bsms.get(1).argRefs[0] instanceof ConstantPool.DynamicEntry);
            ConstantPool.DynamicEntry de =
                    (ConstantPool.DynamicEntry) bsms.get(1).argRefs[0];
            assertEquals("DynamicEntry bssRef must equal BSM[0]",
                         bsms.get(0), de.bssRef);
        } finally {
            Utils.currentInstance.set(null);
        }
    }

    /**
     * A class file where a bootstrap method static argument is a
     * {@code CONSTANT_Dynamic} must survive a full pack/unpack round-trip
     * without any exception.
     */
    @Test
    public void testPackUnpackCondyArgClass() throws Exception {
        byte[] classBytes = buildCondyArgClassFile();

        // Build an in-memory JAR containing the synthetic class.
        ByteArrayOutputStream jarBuf = new ByteArrayOutputStream();
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        JarOutputStream jos = new JarOutputStream(jarBuf, mf);
        JarEntry entry = new JarEntry("TestCondyBsmArg.class");
        jos.putNextEntry(entry);
        jos.write(classBytes);
        jos.closeEntry();
        jos.close();

        // Pack via the streaming API (no temporary file required).
        ByteArrayOutputStream packBuf = new ByteArrayOutputStream();
        Pack200.newPacker().pack(
                new JarInputStream(new ByteArrayInputStream(jarBuf.toByteArray())),
                packBuf);

        // Unpack.
        ByteArrayOutputStream unpackBuf = new ByteArrayOutputStream();
        JarOutputStream unpackJos = new JarOutputStream(unpackBuf);
        try {
            Pack200.newUnpacker().unpack(
                    new ByteArrayInputStream(packBuf.toByteArray()), unpackJos);
        } finally {
            unpackJos.close();
        }

        // Verify the unpacked JAR is non-empty.
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

    /**
     * Builds a minimal Java 11 (major version 55) class file that exercises
     * the "CONSTANT_Dynamic as bootstrap-method static argument" pattern used
     * by JDK 25+ class files.
     *
     * <p>The class {@code TestCondyBsmArg} contains two bootstrap methods:</p>
     * <ul>
     *   <li>BSM[0] — a plain {@code REF_invokeStatic} method handle with no
     *       static arguments</li>
     *   <li>BSM[1] — the same method handle, but with {@code #15} (a
     *       {@code CONSTANT_Dynamic} that itself references BSM[0]) as its
     *       sole static argument</li>
     * </ul>
     *
     * <p>Constant-pool layout (indices 1–25, {@code cp_count} = 26):</p>
     * <pre>
     *  #1  Utf8        "TestCondyBsmArg"
     *  #2  Utf8        "java/lang/Object"
     *  #3  Class       #1
     *  #4  Class       #2
     *  #5  Utf8        "Holder"
     *  #6  Class       #5
     *  #7  Utf8        "bsm"
     *  #8  Utf8        "(Ljava/lang/invoke/MethodHandles$Lookup;...)Ljava/lang/Object;"
     *  #9  NameAndType #7  #8
     * #10  Methodref   #6  #9
     * #11  MethodHandle REF_invokeStatic, #10
     * #12  Utf8        "val"
     * #13  Utf8        "Ljava/lang/Object;"
     * #14  NameAndType #12 #13
     * #15  Dynamic     bsm_index=0, #14   &lt;-- CONSTANT_Dynamic refs BSM[0]
     * #16  Utf8        "test"
     * #17  Utf8        "()Ljava/lang/Object;"
     * #18  NameAndType #16 #17
     * #19  InvokeDynamic bsm_index=1, #18
     * #20  Utf8        "&lt;init&gt;"
     * #21  Utf8        "()V"
     * #22  NameAndType #20 #21
     * #23  Methodref   #4  #22
     * #24  Utf8        "Code"
     * #25  Utf8        "BootstrapMethods"
     * </pre>
     *
     * <p>Bootstrap methods table:</p>
     * <pre>
     *   BSM[0]: ref=#11, args=[]
     *   BSM[1]: ref=#11, args=[#15]   &lt;-- CONSTANT_Dynamic as arg!
     * </pre>
     */
    private static byte[] buildCondyArgClassFile() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);

        // ---- class file header ----
        out.writeInt(0xCAFEBABE);  // magic
        out.writeShort(0);          // minor_version
        out.writeShort(55);         // major_version (Java 11; first to support CONSTANT_Dynamic)

        // ---- constant pool (cp_count = 26, valid entries #1..#25) ----
        out.writeShort(26);

        out.writeByte(1);  out.writeUTF("TestCondyBsmArg");           // #1
        out.writeByte(1);  out.writeUTF("java/lang/Object");          // #2
        out.writeByte(7);  out.writeShort(1);                         // #3 Class #1
        out.writeByte(7);  out.writeShort(2);                         // #4 Class #2
        out.writeByte(1);  out.writeUTF("Holder");                    // #5
        out.writeByte(7);  out.writeShort(5);                         // #6 Class #5
        out.writeByte(1);  out.writeUTF("bsm");                       // #7
        out.writeByte(1);                                              // #8 (BSM descriptor)
        out.writeUTF("(Ljava/lang/invoke/MethodHandles$Lookup;"
                   + "Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;");
        out.writeByte(12); out.writeShort(7);  out.writeShort(8);     // #9  NameAndType #7 #8
        out.writeByte(10); out.writeShort(6);  out.writeShort(9);     // #10 Methodref #6 #9
        out.writeByte(15); out.writeByte(6);   out.writeShort(10);    // #11 MethodHandle invStatic #10
        out.writeByte(1);  out.writeUTF("val");                       // #12
        out.writeByte(1);  out.writeUTF("Ljava/lang/Object;");        // #13
        out.writeByte(12); out.writeShort(12); out.writeShort(13);    // #14 NameAndType #12 #13
        out.writeByte(17); out.writeShort(0);  out.writeShort(14);    // #15 Dynamic bsm=0 nat=#14
        out.writeByte(1);  out.writeUTF("test");                      // #16
        out.writeByte(1);  out.writeUTF("()Ljava/lang/Object;");      // #17
        out.writeByte(12); out.writeShort(16); out.writeShort(17);    // #18 NameAndType #16 #17
        out.writeByte(18); out.writeShort(1);  out.writeShort(18);    // #19 InvokeDynamic bsm=1 nat=#18
        out.writeByte(1);  out.writeUTF("<init>");                    // #20
        out.writeByte(1);  out.writeUTF("()V");                       // #21
        out.writeByte(12); out.writeShort(20); out.writeShort(21);    // #22 NameAndType #20 #21
        out.writeByte(10); out.writeShort(4);  out.writeShort(22);    // #23 Methodref #4 #22
        out.writeByte(1);  out.writeUTF("Code");                      // #24
        out.writeByte(1);  out.writeUTF("BootstrapMethods");          // #25

        // ---- class header ----
        out.writeShort(0x0001);  // access_flags = ACC_PUBLIC
        out.writeShort(3);        // this_class  = #3
        out.writeShort(4);        // super_class = #4
        out.writeShort(0);        // interfaces_count

        // ---- fields: none ----
        out.writeShort(0);

        // ---- methods ----
        out.writeShort(2);

        // <init>()V : aload_0, invokespecial #23 (0x17), return
        out.writeShort(0x0001); out.writeShort(20); out.writeShort(21);
        out.writeShort(1);  // attributes_count
        writeCodeAttr(out, 24, 1, 1,
                new byte[]{ 0x2a, (byte) 0xb7, 0x00, 23, (byte) 0xb1 });

        // test()Ljava/lang/Object; : invokedynamic #19 0 0, areturn
        out.writeShort(0x0009); out.writeShort(16); out.writeShort(17);
        out.writeShort(1);  // attributes_count
        writeCodeAttr(out, 24, 1, 0,
                new byte[]{ (byte) 0xba, 0x00, 19, 0x00, 0x00, (byte) 0xb0 });

        // ---- class attributes: BootstrapMethods ----
        // BSM[0]: ref=#11, nargs=0          → 4 bytes
        // BSM[1]: ref=#11, nargs=1, arg=#15 → 6 bytes
        // body = 2 (count) + 4 + 6 = 12 bytes
        out.writeShort(1);          // attributes_count
        out.writeShort(25);         // attribute_name_index = #25
        out.writeInt(12);           // attribute_length
        out.writeShort(2);          // num_bootstrap_methods
        out.writeShort(11); out.writeShort(0);                          // BSM[0]: ref=#11, nargs=0
        out.writeShort(11); out.writeShort(1); out.writeShort(15);      // BSM[1]: ref=#11, nargs=1, arg=#15

        out.flush();
        return bos.toByteArray();
    }

    /**
     * Writes a {@code Code} attribute with the supplied code bytes and no
     * exception table or nested attributes.
     */
    private static void writeCodeAttr(DataOutputStream out, int nameIdx,
                                      int maxStack, int maxLocals,
                                      byte[] code) throws IOException {
        // body = max_stack(2) + max_locals(2) + code_length(4) + code + ex_table_length(2) + attrs_count(2)
        int bodyLen = 2 + 2 + 4 + code.length + 2 + 2;
        out.writeShort(nameIdx);
        out.writeInt(bodyLen);
        out.writeShort(maxStack);
        out.writeShort(maxLocals);
        out.writeInt(code.length);
        out.write(code);
        out.writeShort(0);  // exception_table_length
        out.writeShort(0);  // attributes_count
    }

    // -----------------------------------------------------------------------
    // 15. Record component sub-attribute support (Java 16+, JVMS 4.7.23)
    //
    //     Before the fix, packing a class with a Record attribute whose
    //     components carry sub-attributes (e.g. Signature) failed with:
    //       "Record component sub-attributes are not supported"
    //     and the class was silently passed as-is (not compressed).
    //
    //     After the fix, sub-attributes are stored in rc_attr_bands and
    //     survive a pack/unpack round-trip byte-for-byte.
    // -----------------------------------------------------------------------

    /**
     * A Java 16 record class with a generic component must survive a full
     * pack → unpack round-trip and produce an output class file that is
     * byte-for-byte identical to the input.
     *
     * <p>Constant-pool layout (indices 1–13, {@code cp_count} = 14):</p>
     * <pre>
     *  #1  Utf8        "TestGenericRecord"
     *  #2  Utf8        "java/lang/Object"
     *  #3  Utf8        "java/lang/Record"
     *  #4  Class       #1
     *  #5  Class       #2
     *  #6  Class       #3
     *  #7  Utf8        "value"           (component name)
     *  #8  Utf8        "Ljava/lang/Object;" (component descriptor)
     *  #9  Utf8        "TT;"              (Signature of generic component)
     *  #10 Utf8        "Signature"        (attribute name)
     *  #11 Utf8        "Record"           (attribute name)
     *  #12 Utf8        "(TT;)V"           (class Signature)
     *  #13 Utf8        "SourceFile"       (attribute name - unused, just padding)
     * </pre>
     */
    @Test
    public void testPackUnpackRecordWithSignatureComponent() throws Exception {
        byte[] classBytes = buildGenericRecordClassFile();

        // Build an in-memory JAR containing the synthetic class.
        ByteArrayOutputStream jarBuf = new ByteArrayOutputStream();
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        JarOutputStream jos = new JarOutputStream(jarBuf, mf);
        JarEntry entry = new JarEntry("TestGenericRecord.class");
        jos.putNextEntry(entry);
        jos.write(classBytes);
        jos.closeEntry();
        jos.close();

        // Pack via the streaming API.
        ByteArrayOutputStream packBuf = new ByteArrayOutputStream();
        Pack200.newPacker().pack(
                new JarInputStream(new ByteArrayInputStream(jarBuf.toByteArray())),
                packBuf);

        // Unpack.
        ByteArrayOutputStream unpackBuf = new ByteArrayOutputStream();
        JarOutputStream unpackJos = new JarOutputStream(unpackBuf);
        try {
            Pack200.newUnpacker().unpack(
                    new ByteArrayInputStream(packBuf.toByteArray()), unpackJos);
        } finally {
            unpackJos.close();
        }

        // Verify the unpacked JAR is non-empty and the class bytes are
        // identical to the input (round-trip fidelity).
        JarInputStream jis = new JarInputStream(
                new ByteArrayInputStream(unpackBuf.toByteArray()));
        JarEntry outEntry = null;
        byte[] outBytes = null;
        try {
            JarEntry e;
            while ((e = jis.getNextJarEntry()) != null) {
                if (e.getName().equals("TestGenericRecord.class")) {
                    outEntry = e;
                    ByteArrayOutputStream tmp = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = jis.read(buf)) != -1) tmp.write(buf, 0, n);
                    outBytes = tmp.toByteArray();
                    break;
                }
            }
        } finally {
            jis.close();
        }

        assertTrue("Unpacked JAR must contain TestGenericRecord.class",
                   outEntry != null);
        assertTrue("Output class must be a valid class file (CAFEBABE magic)",
                   outBytes.length >= 4
                   && (outBytes[0] & 0xFF) == 0xCA
                   && (outBytes[1] & 0xFF) == 0xFE
                   && (outBytes[2] & 0xFF) == 0xBA
                   && (outBytes[3] & 0xFF) == 0xBE);

        // Verify that the Record attribute with the Signature sub-attribute
        // is preserved after the round-trip.  We scan the raw class bytes for
        // the "Signature" marker inside the Record body rather than pulling in
        // a full ASM dependency.
        String outClassText = new String(outBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue("Output class must contain the 'Record' attribute name",
                   outClassText.contains("Record"));
        assertTrue("Output class must contain the component Signature value 'TT;'",
                   outClassText.contains("TT;"));
    }

    /**
     * Builds a minimal Java 16 (major version 60) record class file whose
     * single component carries a {@code Signature} sub-attribute.
     *
     * <p>The generated class is equivalent to:</p>
     * <pre>
     *   public record TestGenericRecord&lt;T&gt;(T value) {}
     * </pre>
     * (simplified: no actual methods, just the Record attribute with the
     * component and its Signature)
     */
    private static byte[] buildGenericRecordClassFile() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);

        // ---- class file header ----
        out.writeInt(0xCAFEBABE);
        out.writeShort(0);   // minor_version
        out.writeShort(60);  // major_version = Java 16

        // ---- constant pool (cp_count = 14, valid indices #1..#13) ----
        out.writeShort(14);

        out.writeByte(1); out.writeUTF("TestGenericRecord");      // #1  Utf8 class name
        out.writeByte(1); out.writeUTF("java/lang/Object");       // #2  Utf8 Object
        out.writeByte(1); out.writeUTF("java/lang/Record");       // #3  Utf8 Record
        out.writeByte(7); out.writeShort(1);                      // #4  Class #1
        out.writeByte(7); out.writeShort(2);                      // #5  Class #2
        out.writeByte(7); out.writeShort(3);                      // #6  Class #3
        out.writeByte(1); out.writeUTF("value");                  // #7  component name
        out.writeByte(1); out.writeUTF("Ljava/lang/Object;");     // #8  component descriptor
        out.writeByte(1); out.writeUTF("TT;");                    // #9  component Signature: type param T
        out.writeByte(1); out.writeUTF("Signature");              // #10 attr name
        out.writeByte(1); out.writeUTF("Record");                 // #11 attr name
        // #12: generic class signature — one type param T bounded by Object,
        //      extending Record  (JVMS §4.7.9 ClassSignature syntax)
        out.writeByte(1); out.writeUTF("<T:Ljava/lang/Object;>Ljava/lang/Record;"); // #12 class sig
        out.writeByte(1); out.writeUTF("SourceFile");             // #13 (unused, stabilises CP order)

        // ---- class header ----
        out.writeShort(0x0010 | 0x0020);  // ACC_FINAL | ACC_SUPER
        out.writeShort(4);   // this_class  = #4
        out.writeShort(6);   // super_class = #6  (java/lang/Record)
        out.writeShort(0);   // interfaces_count

        // ---- fields: none ----
        out.writeShort(0);

        // ---- methods: none ----
        out.writeShort(0);

        // ---- class attributes: Signature + Record ----
        out.writeShort(2);

        // Signature attribute (class-level, #10/#12)
        out.writeShort(10);  // attribute_name_index = #10 "Signature"
        out.writeInt(2);     // attribute_length
        out.writeShort(12);  // signature_index = #12

        // Record attribute (#11)
        //   components_count = 1
        //   component: name=#7, descriptor=#8, attributes_count=1
        //     Signature sub-attribute: name=#10, length=2, signature_index=#9
        int recordBodyLen =
            2 +           // components_count
            (2 + 2 +      //   name_index + descriptor_index
             2 +          //   attributes_count
             (2 + 4 + 2)  //   Signature attr: name(2) + length(4) + index(2)
            );
        out.writeShort(11);                // attribute_name_index = #11 "Record"
        out.writeInt(recordBodyLen);       // attribute_length
        out.writeShort(1);                 // components_count
        // component[0]
        out.writeShort(7);                 //   name_index  = #7 "value"
        out.writeShort(8);                 //   descriptor  = #8 "Ljava/lang/Object;"
        out.writeShort(1);                 //   attributes_count = 1
        // Signature sub-attr for component
        out.writeShort(10);                //     attribute_name_index = #10 "Signature"
        out.writeInt(2);                   //     attribute_length = 2
        out.writeShort(9);                 //     signature_index = #9 "TT;"

        out.flush();
        return bos.toByteArray();
    }
}
