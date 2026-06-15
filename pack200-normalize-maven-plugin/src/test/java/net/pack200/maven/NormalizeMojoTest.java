/*
 * Copyright (c) 2026, Peter Firmstone.
 *
 * Tests for NormalizeMojo.
 */
package net.pack200.maven;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.pack200.Normalize;

public class NormalizeMojoTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File inputJar;

    @Before
    public void buildInputJar() throws IOException {
        inputJar = tmp.newFile("input.jar");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(inputJar);
             JarOutputStream jos = newJos(fos)) {

            // a small "class" file as a data entry
            JarEntry e = new JarEntry("com/example/Thing.class");
            jos.putNextEntry(e);
            // Minimal valid-looking class header bytes (CAFEBABE) plus padding;
            // Normalize.reproducible() doesn't validate class structure.
            jos.write(new byte[] {
                (byte)0xCA,(byte)0xFE,(byte)0xBA,(byte)0xBE,
                0,0,0,52, // minor=0, major=52
                0,1,      // constant pool count = 1 (empty)
                0,0x20,   // access flags
                0,0,      // this_class
                0,0,      // super_class
                0,0,      // interfaces
                0,0,      // fields
                0,0,      // methods
                0,0       // attributes
            });
            jos.closeEntry();

            JarEntry r = new JarEntry("data/payload.txt");
            jos.putNextEntry(r);
            jos.write("hello pack200\n".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        assertTrue(inputJar.length() > 0);
    }

    private static JarOutputStream newJos(java.io.OutputStream raw) throws IOException {
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        return new JarOutputStream(raw, m);
    }

    @After
    public void cleanup() {
        // TemporaryFolder rule disposes.
    }

    // ---------------------------------------------------------------------

    /** Run normalize+stamp on a freshly built jar; verify shape and stamp. */
    @Test
    public void normalize_with_stamp_produces_consistent_output() throws Exception {
        File out = new File(tmp.getRoot(), "out.jar");

        NormalizeMojo mojo = new NormalizeMojo();
        mojo.setInputFile(inputJar);
        mojo.setOutputFile(out);
        mojo.setAttachStamp(true);
        mojo.setStampEntryName("META-INF/CONTENT-HASH");
        mojo.setFailOnAlreadyStamped(false);
        mojo.setSkip(false);

        mojo.execute();

        assertTrue("output must exist", out.isFile());
        assertTrue("output must be non-empty", out.length() > 0);

        // Compute reference canonical bytes the same way the mojo does, then
        // expected stamp + final bytes; verify byte-equality.
        byte[] inputBytes = Files.readAllBytes(inputJar.toPath());
        ByteArrayOutputStream canon = new ByteArrayOutputStream();
        try (InputStream in = new ByteArrayInputStream(inputBytes)) {
            Normalize.normalize(in, canon, Normalize.Options.reproducible());
        }
        byte[] canonical = canon.toByteArray();
        String expectedHash = sha256Hex(canonical);
        byte[] expectedFinal = NormalizeMojo.appendStamp(
            canonical, "META-INF/CONTENT-HASH", expectedHash);

        byte[] actualFinal = Files.readAllBytes(out.toPath());
        assertArrayEquals("output bytes must match Normalize.normalize(input) + stamp",
                          expectedFinal, actualFinal);

        // Stamp entry must be present and contain SHA-256:<hex>\n.
        try (JarFile jf = new JarFile(out, false)) {
            JarEntry stamp = jf.getJarEntry("META-INF/CONTENT-HASH");
            assertNotNull("stamp entry must be present", stamp);
            byte[] body;
            try (InputStream s = jf.getInputStream(stamp)) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[256];
                int n;
                while ((n = s.read(buf)) > 0) { b.write(buf, 0, n); }
                body = b.toByteArray();
            }
            String text = new String(body, StandardCharsets.UTF_8);
            assertEquals("stamp body must be exactly SHA-256:<hash>\\n",
                         "SHA-256:" + expectedHash + "\n", text);
        }

        // Recover canonical bytes by removing the stamp entry; their SHA-256 must
        // match what the stamp records.
        byte[] recovered = NormalizeMojo.jarBytesWithEntryRemoved(
            Files.readAllBytes(out.toPath()), "META-INF/CONTENT-HASH");
        assertNotNull("must be able to drop stamp entry", recovered);
        assertEquals("SHA-256 of recovered (unstamped) bytes must equal stamp value",
                     expectedHash, sha256Hex(recovered));
        // And they must be byte-identical to the canonical normalize() output.
        assertArrayEquals("recovered bytes must equal canonical bytes",
                          canonical, recovered);
    }

    /** No-stamp mode: output is exactly Normalize.normalize(input). */
    @Test
    public void normalize_without_stamp_writes_canonical_bytes() throws Exception {
        File out = new File(tmp.getRoot(), "out-nostamp.jar");

        NormalizeMojo mojo = new NormalizeMojo();
        mojo.setInputFile(inputJar);
        mojo.setOutputFile(out);
        mojo.setAttachStamp(false);
        mojo.setStampEntryName("META-INF/CONTENT-HASH");
        mojo.setFailOnAlreadyStamped(false);
        mojo.setSkip(false);

        mojo.execute();

        byte[] inputBytes = Files.readAllBytes(inputJar.toPath());
        ByteArrayOutputStream canon = new ByteArrayOutputStream();
        try (InputStream in = new ByteArrayInputStream(inputBytes)) {
            Normalize.normalize(in, canon, Normalize.Options.reproducible());
        }
        byte[] expected = canon.toByteArray();
        byte[] actual = Files.readAllBytes(out.toPath());
        assertArrayEquals(expected, actual);

        try (JarFile jf = new JarFile(out, false)) {
            assertNull("no stamp entry when attachStamp=false",
                       jf.getJarEntry("META-INF/CONTENT-HASH"));
        }
    }

    /** Running the mojo twice on the same input yields a byte-equal output. */
    @Test
    public void idempotent_double_run_yields_equal_output() throws Exception {
        File out1 = new File(tmp.getRoot(), "out1.jar");
        File out2 = new File(tmp.getRoot(), "out2.jar");

        NormalizeMojo mojo1 = new NormalizeMojo();
        mojo1.setInputFile(inputJar);
        mojo1.setOutputFile(out1);
        mojo1.setAttachStamp(true);
        mojo1.setStampEntryName("META-INF/CONTENT-HASH");
        mojo1.setFailOnAlreadyStamped(false);
        mojo1.setSkip(false);
        mojo1.execute();

        // Second run: feed the stamped output back as input.
        NormalizeMojo mojo2 = new NormalizeMojo();
        mojo2.setInputFile(out1);
        mojo2.setOutputFile(out2);
        mojo2.setAttachStamp(true);
        mojo2.setStampEntryName("META-INF/CONTENT-HASH");
        mojo2.setFailOnAlreadyStamped(false);
        mojo2.setSkip(false);
        mojo2.execute();

        // The OUTPUT of the second run (re-normalising a stamped jar) is permitted
        // to differ from out1 only in that the stamp entry is recomputed -- but
        // because Normalize.reproducible() is a fixed point, re-normalising
        // out1 produces out1 again, and the appended stamp is identical.
        byte[] b1 = Files.readAllBytes(out1.toPath());
        byte[] b2 = Files.readAllBytes(out2.toPath());
        assertArrayEquals("double-run must be byte-equal", b1, b2);
    }

    /** With failOnAlreadyStamped=true and a matching stamp, the file is left alone. */
    @Test
    public void fast_path_leaves_already_stamped_file_unchanged() throws Exception {
        File out = new File(tmp.getRoot(), "out-fast.jar");

        // First run stamps it.
        NormalizeMojo first = new NormalizeMojo();
        first.setInputFile(inputJar);
        first.setOutputFile(out);
        first.setAttachStamp(true);
        first.setStampEntryName("META-INF/CONTENT-HASH");
        first.setFailOnAlreadyStamped(false);
        first.setSkip(false);
        first.execute();

        byte[] beforeSecond = Files.readAllBytes(out.toPath());
        long mtimeBefore = out.lastModified();

        // Second run on the stamped output, with failOnAlreadyStamped=true,
        // must leave it byte-equal.
        NormalizeMojo second = new NormalizeMojo();
        second.setInputFile(out);
        // outputFile unset -> in-place; with the fast-path it should not be rewritten.
        second.setAttachStamp(true);
        second.setStampEntryName("META-INF/CONTENT-HASH");
        second.setFailOnAlreadyStamped(true);
        second.setSkip(false);
        second.execute();

        byte[] afterSecond = Files.readAllBytes(out.toPath());
        assertArrayEquals("fast-path must leave the file byte-equal",
                          beforeSecond, afterSecond);
        // mtime untouched is a nice-to-have; we don't strictly assert it because
        // file-system mtime resolution varies.
        assertTrue(mtimeBefore <= out.lastModified());
    }

    /** skip=true must produce no output and not fail. */
    @Test
    public void skip_short_circuits() throws Exception {
        File out = new File(tmp.getRoot(), "out-skip.jar");
        NormalizeMojo mojo = new NormalizeMojo();
        mojo.setInputFile(inputJar);
        mojo.setOutputFile(out);
        mojo.setAttachStamp(true);
        mojo.setStampEntryName("META-INF/CONTENT-HASH");
        mojo.setSkip(true);

        mojo.execute(); // must not throw

        assertFalse("skip=true must NOT create output", out.exists());
    }

    /** Missing input -> hard MojoExecutionException with a clear message. */
    @Test
    public void missing_input_fails() {
        File ghost = new File(tmp.getRoot(), "does-not-exist.jar");
        NormalizeMojo mojo = new NormalizeMojo();
        mojo.setInputFile(ghost);
        mojo.setOutputFile(new File(tmp.getRoot(), "out.jar"));
        mojo.setAttachStamp(true);
        mojo.setStampEntryName("META-INF/CONTENT-HASH");
        mojo.setSkip(false);

        try {
            mojo.execute();
            fail("expected MojoExecutionException");
        } catch (MojoExecutionException e) {
            assertTrue("message should mention the missing file",
                       e.getMessage() != null && e.getMessage().contains(ghost.getName()));
        }
    }

    // ---- helpers --------------------------------------------------------

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(data);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            int v = b & 0xff;
            if (v < 16) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }
}
