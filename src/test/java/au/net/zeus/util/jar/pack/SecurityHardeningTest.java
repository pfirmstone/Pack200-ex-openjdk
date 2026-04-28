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
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

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
    // 3. Round-trip pack / unpack using the library's own JAR
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
