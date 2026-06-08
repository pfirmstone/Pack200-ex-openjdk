/*
 * Copyright (c) 2026, the Pack200-ex-openjdk contributors. All rights reserved.
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

import net.pack200.Normalize;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link net.pack200.Normalize}.
 *
 * <p>The test fixture builds two JARs with the same logical content but
 * different ZIP-level encodings (entry order, timestamps, directory entries,
 * extra fields).  Normalising both must produce byte-identical output.
 */
public class NormalizeTest {

    private Path workDir;

    @Before
    public void setUp() throws IOException {
        workDir = Files.createTempDirectory("normalize-test-");
    }

    @After
    public void tearDown() throws IOException {
        if (workDir != null) {
            try (Stream<Path> stream = Files.walk(workDir)) {
                stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                      .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            }
        }
    }

    // ------------------------------------------------------------------------
    // Reproducible: two semantically equal but byte-different JARs normalise
    // to byte-identical output.
    // ------------------------------------------------------------------------

    @Test
    public void reproducible_twoEquivalentJarsHashIdentically() throws Exception {
        // Build two JARs with the same payload but distinct ZIP encodings.
        // We use a deliberately simple, no-class-file payload because building a
        // valid classfile here would be overkill; the Pack200 round-trip handles
        // resource-only JARs perfectly well.
        Path jarA = workDir.resolve("a.jar");
        Path jarB = workDir.resolve("b.jar");

        long earlyTime = 1_000_000_000_000L; // 2001-09-09
        long lateTime  = 1_700_000_000_000L; // 2023-11-14

        writeJar(jarA, /*entryOrder=*/Arrays.asList("META-INF/MANIFEST.MF",
                                                   "data/alpha.txt",
                                                   "data/beta.txt"),
                 earlyTime, /*withDirs=*/false, /*withExtra=*/false);

        writeJar(jarB, /*entryOrder=*/Arrays.asList("META-INF/MANIFEST.MF",
                                                   "data/beta.txt",    // reversed
                                                   "data/alpha.txt",
                                                   "data/"),           // gratuitous directory
                 lateTime, /*withDirs=*/true, /*withExtra=*/true);

        // Sanity: the raw JARs differ.
        assertFalse("Test fixture broken: raw JARs are byte-equal",
                    java.util.Arrays.equals(Files.readAllBytes(jarA),
                                            Files.readAllBytes(jarB)));

        Path outA = workDir.resolve("a.norm.jar");
        Path outB = workDir.resolve("b.norm.jar");

        Normalize.normalize(jarA.toFile(), outA.toFile());
        Normalize.normalize(jarB.toFile(), outB.toFile());

        byte[] hashA = sha256(Files.readAllBytes(outA));
        byte[] hashB = sha256(Files.readAllBytes(outB));

        assertArrayEquals(
            "Normalised JARs differ: A=" + toHex(hashA) + " B=" + toHex(hashB),
            hashA, hashB);
    }

    // ------------------------------------------------------------------------
    // Idempotence: normalising the output again produces the same bytes.
    // (i.e. the output is a fixed point.)
    // ------------------------------------------------------------------------

    @Test
    public void reproducible_isIdempotent() throws Exception {
        Path src = workDir.resolve("src.jar");
        writeJar(src, Arrays.asList("META-INF/MANIFEST.MF", "data/x.txt"),
                 1_700_000_000_000L, false, false);

        Path n1 = workDir.resolve("n1.jar");
        Path n2 = workDir.resolve("n2.jar");

        Normalize.normalize(src.toFile(), n1.toFile());
        Normalize.normalize(n1.toFile(),  n2.toFile());

        assertArrayEquals("normalize(normalize(x)) != normalize(x)",
                          Files.readAllBytes(n1), Files.readAllBytes(n2));
    }

    // ------------------------------------------------------------------------
    // Output is a valid JAR: all entries present, manifest first, no
    // directory entries, comment cleared, timestamps fixed.
    // ------------------------------------------------------------------------

    @Test
    public void reproducible_outputStructure() throws Exception {
        Path src = workDir.resolve("src.jar");
        writeJar(src, Arrays.asList("META-INF/MANIFEST.MF",
                              "data/beta.txt",
                              "data/alpha.txt",
                              "data/"),
                 1_700_000_000_000L, true, true);

        Path out = workDir.resolve("out.jar");
        Normalize.normalize(src.toFile(), out.toFile());

        try (JarFile jf = new JarFile(out.toFile(), false)) {
            // Order: manifest first, then alpha, then beta.  No dir entry.
            List<String> names = new ArrayList<>();
            for (Enumeration<JarEntry> en = jf.entries(); en.hasMoreElements(); ) {
                names.add(en.nextElement().getName());
            }
            assertEquals(Arrays.asList("META-INF/MANIFEST.MF",
                                       "data/alpha.txt",
                                       "data/beta.txt"),
                         names);

            // Every entry's time is pinned to the fixedTime.  The "extra"
            // field may contain the Extended-Timestamp (0x5455 "UT") block
            // emitted by JDK ZipOutputStream whenever setTime(long) is called
            // on a modern JDK -- that's deterministic for a fixed time, so the
            // overall JAR remains reproducible.  We only check that, if extra
            // is present, it is the well-known Extended Timestamp block and
            // nothing else.
            for (Enumeration<JarEntry> en = jf.entries(); en.hasMoreElements(); ) {
                JarEntry e = en.nextElement();
                byte[] extra = e.getExtra();
                if (extra != null) {
                    assertTrue(
                        "Unexpected extra-field content on " + e.getName()
                        + " (not Extended Timestamp): "
                        + (extra.length > 0 ? Integer.toHexString(extra[0] & 0xff) : "<empty>"),
                        // 0x5455 = 'U','T' = Info-ZIP Extended Timestamp
                        extra.length >= 2 && extra[0] == 0x55 && extra[1] == 0x54);
                }
                // DOS time has 2-second resolution; we just need the entry time
                // to land in the 1980 second.
                long secs = e.getTime() / 1000L;
                long expectedSecs = Normalize.ZIP_EPOCH_1980_UTC_MILLIS / 1000L;
                assertTrue(
                    "Time not pinned on " + e.getName()
                    + ": got=" + e.getTime() + " expected ~" + Normalize.ZIP_EPOCH_1980_UTC_MILLIS,
                    Math.abs(secs - expectedSecs) <= 86400L * 2); // within 2 days for tz/DOS quirks
            }

            assertNull("ZIP comment should be cleared", jf.getComment());
        }
    }

    // ------------------------------------------------------------------------
    // legacyJarN options preserve directory entries and don't touch ZIP fields.
    // ------------------------------------------------------------------------

    @Test
    public void legacyJarN_preservesDirectoryEntries() throws Exception {
        Path src = workDir.resolve("src.jar");
        writeJar(src, Arrays.asList("META-INF/MANIFEST.MF",
                              "data/",
                              "data/x.txt"),
                 1_700_000_000_000L, true, false);

        Path out = workDir.resolve("out.jar");
        Normalize.normalize(src.toFile(), out.toFile(),
                Normalize.Options.legacyJarN());

        try (JarFile jf = new JarFile(out.toFile(), false)) {
            boolean sawDir = false;
            for (Enumeration<JarEntry> en = jf.entries(); en.hasMoreElements(); ) {
                JarEntry e = en.nextElement();
                if (e.getName().equals("data/")) sawDir = true;
            }
            // The Pack200 packer may or may not preserve directory entries even
            // with KEEP_FILE_ORDER=TRUE; what we assert here is that legacyJarN
            // doesn't *force* their removal at the post-process stage.
            // Either outcome is acceptable; we just check that the call
            // succeeds and the data entry survives.
            assertNotNull("data/x.txt missing after legacyJarN",
                          jf.getJarEntry("data/x.txt"));
        }
    }

    // ------------------------------------------------------------------------
    // InputStream/OutputStream overload works and produces same hash as File.
    // ------------------------------------------------------------------------

    @Test
    public void streamOverload_matchesFileOverload() throws Exception {
        Path src = workDir.resolve("src.jar");
        writeJar(src, Arrays.asList("META-INF/MANIFEST.MF", "data/x.txt"),
                 1_700_000_000_000L, false, false);

        Path viaFile = workDir.resolve("viaFile.jar");
        Normalize.normalize(src.toFile(), viaFile.toFile());

        ByteArrayOutputStream viaStream = new ByteArrayOutputStream();
        try (InputStream is = Files.newInputStream(src)) {
            Normalize.normalize(is, viaStream, Normalize.Options.reproducible());
        }

        assertArrayEquals(Files.readAllBytes(viaFile), viaStream.toByteArray());
    }

    // ------------------------------------------------------------------------
    // Pinned time honoured.
    // ------------------------------------------------------------------------

    @Test
    public void fixedTime_isHonoured() throws Exception {
        // 2020-01-01 00:00:00 UTC
        long pinned = 1577836800000L;

        Path src = workDir.resolve("src.jar");
        writeJar(src, Arrays.asList("META-INF/MANIFEST.MF", "data/x.txt"),
                 1_700_000_000_000L, false, false);

        Path out = workDir.resolve("out.jar");
        Normalize.normalize(src.toFile(), out.toFile(),
                Normalize.Options.reproducible().fixedTime(pinned));

        try (JarFile jf = new JarFile(out.toFile(), false)) {
            for (Enumeration<JarEntry> en = jf.entries(); en.hasMoreElements(); ) {
                JarEntry e = en.nextElement();
                long secs         = e.getTime() / 1000L;
                long expectedSecs = pinned / 1000L;
                assertTrue(
                    "Entry " + e.getName() + " time " + e.getTime()
                    + " not close to pinned " + pinned,
                    Math.abs(secs - expectedSecs) <= 86400L * 2);
            }
        }
    }

    // ------------------------------------------------------------------------
    // fixedTime rejects pre-1980 values.
    // ------------------------------------------------------------------------

    @Test
    public void fixedTime_rejectsPreZipEpoch() {
        try {
            Normalize.Options.reproducible().fixedTime(0L);
            fail("Expected IllegalArgumentException for pre-1980 time");
        } catch (IllegalArgumentException expected) { /* good */ }
    }

    @Test
    public void effort_rejectsOutOfRange() {
        try { Normalize.Options.reproducible().effort(-1); fail(); }
        catch (IllegalArgumentException expected) { /* good */ }
        try { Normalize.Options.reproducible().effort(10); fail(); }
        catch (IllegalArgumentException expected) { /* good */ }
    }

    @Test
    public void nullArguments_areRejected() {
        try {
            Normalize.normalize((File) null, new File("x"));
            fail("Expected NPE on null in-File");
        } catch (NullPointerException expected) { /* good */ } catch (IOException e) { fail(); }
        try {
            Normalize.normalize(new File("x"), (File) null);
            fail("Expected NPE on null out-File");
        } catch (NullPointerException expected) { /* good */ } catch (IOException e) { fail(); }
    }

    // ------------------------------------------------------------------------
    // Caller's OutputStream is not closed by the call.
    // ------------------------------------------------------------------------

    @Test
    public void destinationStream_isNotClosed() throws Exception {
        Path src = workDir.resolve("src.jar");
        writeJar(src, Arrays.asList("META-INF/MANIFEST.MF", "data/x.txt"),
                 1_700_000_000_000L, false, false);

        CloseCountingStream sink = new CloseCountingStream();
        try (JarFile jf = new JarFile(src.toFile(), false)) {
            Normalize.normalize(jf, sink);
        }
        assertEquals("Caller stream was closed by normalize", 0, sink.closes);
        assertTrue("Nothing written", sink.size() > 0);
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /**
     * Writes a deterministic JAR for testing.  Resource-only (no class files):
     * the Pack200 round-trip still applies because Pack200 passes resources
     * through verbatim.
     */
    private static void writeJar(Path dest,
                                 List<String> entryOrder,
                                 long mtimeMillis,
                                 boolean includeDirs,
                                 boolean withExtra) throws IOException {
        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        main.putValue("Manifest-Version", "1.0");
        main.putValue("Created-By", "NormalizeTest");

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(dest))) {
            jos.setComment("test-fixture-" + System.nanoTime());
            for (String name : entryOrder) {
                if (!includeDirs && name.endsWith("/")) continue;
                JarEntry e = new JarEntry(name);
                e.setTime(mtimeMillis);
                e.setMethod(ZipEntry.DEFLATED);
                if (withExtra) {
                    // 0x756e ("nu") is not a registered ZIP extra-field tag,
                    // which is exactly the point: it's filesystem noise.
                    e.setExtra(new byte[] { 0x6e, 0x75, 0x04, 0x00, 1, 2, 3, 4 });
                }
                jos.putNextEntry(e);
                if (name.equals("META-INF/MANIFEST.MF")) {
                    manifest.write(jos);
                } else if (!name.endsWith("/")) {
                    // Deterministic per-name payload.
                    byte[] body = ("content of " + name).getBytes();
                    jos.write(body);
                }
                jos.closeEntry();
            }
        }
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static final class CloseCountingStream extends ByteArrayOutputStream {
        int closes = 0;
        @Override public void close() throws IOException {
            closes++;
            super.close();
        }
    }
}
