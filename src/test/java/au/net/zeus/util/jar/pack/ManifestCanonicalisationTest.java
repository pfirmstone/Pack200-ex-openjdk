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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.stream.Stream;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for the manifest-canonicalisation feature of {@link net.pack200.Normalize}.
 *
 * <p>Each test builds a JAR whose {@code META-INF/MANIFEST.MF} is written from a
 * literal header sequence (so we control attribute order and continuation lines
 * precisely), normalises it under {@link Normalize.Options#reproducible()}, and
 * asserts on the canonical result.
 */
public class ManifestCanonicalisationTest {

    private Path workDir;

    @Before
    public void setUp() throws IOException {
        workDir = Files.createTempDirectory("manifest-canon-test-");
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
    // (1) Volatile-header independence.
    // ------------------------------------------------------------------------

    @Test
    public void volatileHeaders_doNotAffectOutput() throws Exception {
        String manifestA =
                "Manifest-Version: 1.0\r\n"
              + "Created-By: 11.0.2 (Oracle Corporation)\r\n"
              + "Build-Jdk: 11.0.2\r\n"
              + "Bnd-LastModified: 1700000000000\r\n"
              + "Implementation-Title: Demo\r\n"
              + "\r\n";
        String manifestB =
                "Manifest-Version: 1.0\r\n"
              + "Created-By: 17.0.9 (Eclipse Adoptium)\r\n"
              + "Build-Jdk: 17.0.9\r\n"
              + "Bnd-LastModified: 1799999999999\r\n"
              + "Implementation-Title: Demo\r\n"
              + "\r\n";

        Path jarA = writeJarWithManifest("a.jar", manifestA);
        Path jarB = writeJarWithManifest("b.jar", manifestB);

        byte[] outA = normalizeToBytes(jarA);
        byte[] outB = normalizeToBytes(jarB);

        assertArrayEquals("Volatile-only differences must vanish after canonicalisation",
                          sha256(outA), sha256(outB));

        // And the surviving manifest must keep Implementation-Title but drop volatiles.
        String mf = manifestOf(outA);
        assertTrue("Implementation-Title preserved", mf.contains("Implementation-Title: Demo"));
        assertFalse("Created-By dropped", mf.contains("Created-By"));
        assertFalse("Build-Jdk dropped", mf.contains("Build-Jdk"));
        assertFalse("Bnd-LastModified dropped", mf.contains("Bnd-LastModified"));
    }

    // ------------------------------------------------------------------------
    // (2) Attribute-insertion-order independence.
    // ------------------------------------------------------------------------

    @Test
    public void attributeOrder_doesNotAffectOutput() throws Exception {
        String manifestA =
                "Manifest-Version: 1.0\r\n"
              + "Aaa: 1\r\n"
              + "Bbb: 2\r\n"
              + "Ccc: 3\r\n"
              + "Specification-Title: X\r\n"
              + "\r\n";
        String manifestB =
                "Manifest-Version: 1.0\r\n"
              + "Specification-Title: X\r\n"
              + "Ccc: 3\r\n"
              + "Aaa: 1\r\n"
              + "Bbb: 2\r\n"
              + "\r\n";

        byte[] outA = normalizeToBytes(writeJarWithManifest("a.jar", manifestA));
        byte[] outB = normalizeToBytes(writeJarWithManifest("b.jar", manifestB));

        assertArrayEquals("Header insertion order must not affect canonical bytes",
                          sha256(outA), sha256(outB));

        // Canonical order: Manifest-Version first, then sorted (Aaa, Bbb, Ccc, Specification-Title).
        String mf = manifestOf(outA);
        int iMv  = mf.indexOf("Manifest-Version");
        int iAaa = mf.indexOf("Aaa:");
        int iBbb = mf.indexOf("Bbb:");
        int iCcc = mf.indexOf("Ccc:");
        int iSpec = mf.indexOf("Specification-Title:");
        assertTrue(iMv >= 0 && iMv < iAaa);
        assertTrue(iAaa < iBbb);
        assertTrue(iBbb < iCcc);
        assertTrue(iCcc < iSpec);
    }

    // ------------------------------------------------------------------------
    // (3) Idempotence / manifest fixed point.
    // ------------------------------------------------------------------------

    @Test
    public void canonicalManifest_isAFixedPoint() throws Exception {
        String manifest =
                "Manifest-Version: 1.0\r\n"
              + "Created-By: somebody\r\n"
              + "Main-Class: com.example.Main\r\n"
              + "Zed: last\r\n"
              + "Alpha: first\r\n"
              + "\r\n";
        Path jar = writeJarWithManifest("src.jar", manifest);

        byte[] n1 = normalizeToBytes(jar);
        Path jar1 = workDir.resolve("n1.jar");
        Files.write(jar1, n1);
        byte[] n2 = normalizeToBytes(jar1);

        assertArrayEquals("normalize(normalize(x)) != normalize(x)", n1, n2);

        // The manifest bytes themselves are a fixed point of the canonicaliser.
        byte[] mfBytes = manifestBytesOf(n1);
        byte[] reCanon = Normalize.canonicaliseManifestBytes(
                mfBytes, Normalize.Options.reproducible());
        assertArrayEquals("canon(canon(m)) != canon(m)", mfBytes, reCanon);
    }

    // ------------------------------------------------------------------------
    // (4) Keep-list survives.
    // ------------------------------------------------------------------------

    @Test
    public void keepList_survives() throws Exception {
        String manifest =
                "Manifest-Version: 1.0\r\n"
              + "Created-By: tool\r\n"
              + "Main-Class: com.example.Main\r\n"
              + "Multi-Release: true\r\n"
              + "Automatic-Module-Name: com.example\r\n"
              + "Bundle-SymbolicName: com.example.bundle\r\n"
              + "Bundle-Version: 1.2.3\r\n"
              + "Import-Package: org.foo;version=\"1\"\r\n"
              + "Export-Package: com.example;version=\"1\"\r\n"
              + "Implementation-Version: 9.9\r\n"
              + "Specification-Vendor: Acme\r\n"
              + "\r\n";
        byte[] out = normalizeToBytes(writeJarWithManifest("src.jar", manifest));
        String mf = manifestOf(out);

        assertTrue(mf.contains("Main-Class: com.example.Main"));
        assertTrue(mf.contains("Multi-Release: true"));
        assertTrue(mf.contains("Automatic-Module-Name: com.example"));
        assertTrue(mf.contains("Bundle-SymbolicName: com.example.bundle"));
        assertTrue(mf.contains("Bundle-Version: 1.2.3"));
        assertTrue(mf.contains("Import-Package: org.foo;version=\"1\""));
        assertTrue(mf.contains("Export-Package: com.example;version=\"1\""));
        assertTrue(mf.contains("Implementation-Version: 9.9"));
        assertTrue(mf.contains("Specification-Vendor: Acme"));
        // Only the denylisted Created-By is gone.
        assertFalse(mf.contains("Created-By"));
    }

    // ------------------------------------------------------------------------
    // (5) Configurable denylist add/remove.
    // ------------------------------------------------------------------------

    @Test
    public void denylist_addAndRemove() throws Exception {
        String manifest =
                "Manifest-Version: 1.0\r\n"
              + "Created-By: tool\r\n"
              + "X-Custom-Volatile: 12345\r\n"
              + "Implementation-Title: Keepme\r\n"
              + "\r\n";
        Path jar = writeJarWithManifest("src.jar", manifest);

        // Add X-Custom-Volatile to the denylist: it should be dropped.
        byte[] added = normalizeToBytes(jar, Normalize.Options.reproducible()
                .manifestDenylistAdd("X-Custom-Volatile"));
        String mfAdded = manifestOf(added);
        assertFalse("X-Custom-Volatile should be dropped after add", mfAdded.contains("X-Custom-Volatile"));
        assertFalse("Created-By still dropped (default)", mfAdded.contains("Created-By"));

        // Remove Created-By from the denylist: it should now survive.
        byte[] removed = normalizeToBytes(jar, Normalize.Options.reproducible()
                .manifestDenylistRemove("Created-By"));
        String mfRemoved = manifestOf(removed);
        assertTrue("Created-By should survive after remove", mfRemoved.contains("Created-By: tool"));

        // Replace the whole denylist with just X-Custom-Volatile: Created-By survives.
        byte[] replaced = normalizeToBytes(jar, Normalize.Options.reproducible()
                .manifestDenylist(Collections.singleton("X-Custom-Volatile")));
        String mfReplaced = manifestOf(replaced);
        assertTrue("Created-By survives with replaced denylist", mfReplaced.contains("Created-By: tool"));
        assertFalse("X-Custom-Volatile dropped with replaced denylist", mfReplaced.contains("X-Custom-Volatile"));
    }

    // ------------------------------------------------------------------------
    // (6) >72-byte header value wraps and round-trips as a fixed point.
    // ------------------------------------------------------------------------

    @Test
    public void longHeaderValue_wrapsAndIsFixedPoint() throws Exception {
        StringBuilder longCp = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            longCp.append("lib/some-long-artifact-name-").append(i).append(".jar ");
        }
        String manifest =
                "Manifest-Version: 1.0\r\n"
              + "Class-Path: " + longCp + "\r\n"
              + "\r\n";

        byte[] out = normalizeToBytes(writeJarWithManifest("src.jar", manifest));
        byte[] mfBytes = manifestBytesOf(out);

        // Every physical line must be <= 72 bytes.
        for (String line : new String(mfBytes, StandardCharsets.UTF_8).split("\r\n", -1)) {
            assertTrue("Line exceeds 72 bytes: [" + line + "]",
                       line.getBytes(StandardCharsets.UTF_8).length <= 72);
        }
        // The wrapped value must re-parse to the original Class-Path value.
        String reassembled = manifestOf(out);
        assertTrue("Class-Path content lost across wrap/round-trip",
                   reassembled.replace("\r\n ", "").contains("lib/some-long-artifact-name-11.jar"));

        // Fixed point.
        byte[] reCanon = Normalize.canonicaliseManifestBytes(
                mfBytes, Normalize.Options.reproducible());
        assertArrayEquals("Wrapped manifest is not a fixed point", mfBytes, reCanon);
    }

    // ------------------------------------------------------------------------
    // (7) Multi-release + per-entry sections sorted.
    // ------------------------------------------------------------------------

    @Test
    public void perEntrySections_sorted() throws Exception {
        String manifest =
                "Manifest-Version: 1.0\r\n"
              + "Multi-Release: true\r\n"
              + "\r\n"
              + "Name: zeta/Z.class\r\n"
              + "Sealed: true\r\n"
              + "\r\n"
              + "Name: alpha/A.class\r\n"
              + "Sealed: false\r\n"
              + "\r\n";

        byte[] out = normalizeToBytes(writeJarWithManifest("src.jar", manifest));
        String mf = manifestOf(out);

        int iAlpha = mf.indexOf("Name: alpha/A.class");
        int iZeta  = mf.indexOf("Name: zeta/Z.class");
        assertTrue("alpha section missing", iAlpha >= 0);
        assertTrue("zeta section missing", iZeta >= 0);
        assertTrue("Per-entry sections must be sorted by Name (alpha before zeta)",
                   iAlpha < iZeta);
        assertTrue("Multi-Release preserved", mf.contains("Multi-Release: true"));
    }

    // ------------------------------------------------------------------------
    // (8) Signed jar: manifest left intact.
    // ------------------------------------------------------------------------

    @Test
    public void signedJar_manifestLeftIntact() throws Exception {
        // A manifest with a denylisted header that would normally be dropped,
        // plus a per-entry digest section -- as a real signed jar would carry.
        String manifest =
                "Manifest-Version: 1.0\r\n"
              + "Created-By: signer-tool\r\n"
              + "\r\n"
              + "Name: data/x.txt\r\n"
              + "SHA-256-Digest: abcdefABCDEF0123456789==\r\n"
              + "\r\n";
        // Build the jar with a META-INF/*.SF entry present -> treated as signed.
        Path jar = workDir.resolve("signed.jar");
        Map<String, byte[]> extra = new LinkedHashMap<>();
        extra.put("META-INF/SIGNER.SF",
                  "Signature-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        extra.put("data/x.txt", "content of data/x.txt".getBytes(StandardCharsets.UTF_8));
        writeJarWithManifestAndEntries(jar, manifest, extra);

        byte[] out = normalizeToBytes(jar);
        String mf = manifestOf(out);

        // The denylisted Created-By must STILL be present because the manifest
        // was left intact for the signed jar.
        assertTrue("Signed-jar manifest must not be canonicalised (Created-By kept)",
                   mf.contains("Created-By: signer-tool"));
        assertTrue("Per-entry digest must be untouched",
                   mf.contains("SHA-256-Digest: abcdefABCDEF0123456789=="));
    }

    // ------------------------------------------------------------------------
    // (9) Cross-locale determinism (manifest canonicalisation is locale-safe).
    //
    // NOTE: cross-TIME-ZONE byte reproducibility is intentionally NOT asserted
    // here.  The JDK ZIP writer encodes the entry timestamp field (and the
    // 0x5455 "UT" extra block) in the JVM default time zone — even via
    // setTimeLocal — so reproducible output across build hosts requires building
    // with a fixed zone (e.g. -Duser.timezone=UTC).  That is a pre-existing
    // property of Normalize's ZIP layer, tracked separately from manifest
    // canonicalisation.
    // ------------------------------------------------------------------------

    @Test
    public void crossLocale_deterministic() throws Exception {
        // Header names chosen to exercise case folding: "I"/"i" names that the
        // Turkish locale would fold differently from ROOT, so a comparator using
        // the default locale (rather than Locale.ROOT) would order them
        // differently and produce different canonical bytes.
        String manifest =
                "Manifest-Version: 1.0\r\n"
              + "Implementation-Title: T\r\n"
              + "Import-Package: org.foo\r\n"
              + "iCustom: lower-i\r\n"
              + "\r\n";
        Path jar = writeJarWithManifest("src.jar", manifest);

        // Time zone held fixed (UTC) for both runs so only the locale varies.
        Locale savedLocale = Locale.getDefault();
        TimeZone savedTz = TimeZone.getDefault();
        byte[] enOut;
        byte[] trOut;
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            Locale.setDefault(Locale.forLanguageTag("en-US"));
            enOut = normalizeToBytes(jar);

            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            trOut = normalizeToBytes(jar);
        } finally {
            Locale.setDefault(savedLocale);
            TimeZone.setDefault(savedTz);
        }

        assertArrayEquals("Manifest canonicalisation must be locale-independent",
                          sha256(enOut), sha256(trOut));
    }

    // ------------------------------------------------------------------------
    // legacyJarN does NOT canonicalise the manifest.
    // ------------------------------------------------------------------------

    @Test
    public void legacyJarN_doesNotCanonicaliseManifest() throws Exception {
        String manifest =
                "Manifest-Version: 1.0\r\n"
              + "Created-By: tool\r\n"
              + "\r\n";
        Path jar = writeJarWithManifest("src.jar", manifest);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarFile jf = new JarFile(jar.toFile(), false)) {
            Normalize.normalize(jf, out, Normalize.Options.legacyJarN());
        }
        String mf = manifestOf(out.toByteArray());
        assertTrue("legacyJarN must leave Created-By in place", mf.contains("Created-By"));
    }

    // ------------------------------------------------------------------------
    // Malformed manifest -> IOException.
    // ------------------------------------------------------------------------

    @Test
    public void malformedManifest_throwsIOException() throws Exception {
        // A manifest with no Manifest-Version main attribute is rejected.
        byte[] bad = "NotAHeaderLineWithoutColon\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        try {
            Normalize.canonicaliseManifestBytes(bad, Normalize.Options.reproducible());
            fail("Expected IOException for malformed manifest");
        } catch (IOException expected) { /* good */ }
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private byte[] normalizeToBytes(Path jar) throws IOException {
        return normalizeToBytes(jar, Normalize.Options.reproducible());
    }

    private byte[] normalizeToBytes(Path jar, Normalize.Options opts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarFile jf = new JarFile(jar.toFile(), false)) {
            Normalize.normalize(jf, out, opts);
        }
        return out.toByteArray();
    }

    private Path writeJarWithManifest(String name, String manifestText) throws IOException {
        Path jar = workDir.resolve(name);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("data/x.txt", "content of data/x.txt".getBytes(StandardCharsets.UTF_8));
        writeJarWithManifestAndEntries(jar, manifestText, entries);
        return jar;
    }

    private static void writeJarWithManifestAndEntries(Path dest, String manifestText,
                                                       Map<String, byte[]> entries) throws IOException {
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(dest))) {
            JarEntry mf = new JarEntry(JarFile.MANIFEST_NAME);
            mf.setTime(1_700_000_000_000L);
            mf.setMethod(ZipEntry.DEFLATED);
            jos.putNextEntry(mf);
            jos.write(manifestText.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                JarEntry je = new JarEntry(e.getKey());
                je.setTime(1_700_000_000_000L);
                je.setMethod(ZipEntry.DEFLATED);
                jos.putNextEntry(je);
                jos.write(e.getValue());
                jos.closeEntry();
            }
        }
    }

    private static String manifestOf(byte[] jarBytes) throws IOException {
        return new String(manifestBytesOf(jarBytes), StandardCharsets.UTF_8);
    }

    private static byte[] manifestBytesOf(byte[] jarBytes) throws IOException {
        Path tmp = Files.createTempFile("read-mf-", ".jar");
        try {
            Files.write(tmp, jarBytes);
            try (JarFile jf = new JarFile(tmp.toFile(), false)) {
                JarEntry e = jf.getJarEntry(JarFile.MANIFEST_NAME);
                assertNotNull("output JAR has no manifest", e);
                try (InputStream is = jf.getInputStream(e)) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) > 0) b.write(buf, 0, n);
                    return b.toByteArray();
                }
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
