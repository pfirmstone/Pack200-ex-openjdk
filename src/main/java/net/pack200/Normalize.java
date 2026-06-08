/*
 * Copyright (c) 2026, the Pack200-ex-openjdk contributors. All rights reserved.
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
 */
package net.pack200;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Canonicalises a JAR file so that semantically-equivalent inputs produce
 * byte-identical outputs.
 *
 * <p>This is the modern replacement for the {@code jar --normalize} / {@code jar -n}
 * option that was removed from the JDK by
 * <a href="https://bugs.openjdk.org/browse/JDK-8234542">JDK-8234542</a> (JEP&nbsp;367,
 * Java&nbsp;15).  The original OpenJDK implementation lived in
 * {@code sun.tools.jar.Main} and consisted of a {@link net.pack200.Pack200.Packer}
 * followed by a {@link net.pack200.Pack200.Unpacker} round-trip with
 * {@code EFFORT=1}.  That round-trip is preserved here as Step&nbsp;1 of the pipeline;
 * an optional Step&nbsp;2 then rewrites the resulting JAR with canonical entry
 * ordering, fixed timestamps and a stable deflate hint.
 *
 * <h2>Why normalise?</h2>
 *
 * <p>There are two distinct motivations:
 *
 * <ol>
 *   <li><b>Pack200 fixed-point (the original use case).</b>
 *       Packing a JAR with Pack200 is <em>not</em> idempotent on raw input bytes:
 *       Pack200 rewrites class-file constant pools into a canonical band layout
 *       and the unpacker produces a fresh classfile from those bands.  If you
 *       wish to sign the JAR <em>and</em> later distribute it as a {@code .pack}
 *       stream, the bytes you sign must already be in the form that Pack200 will
 *       reproduce.  The pack &rarr; unpack round-trip drives the JAR to that
 *       fixed point.  Signing first and packing afterwards would invalidate the
 *       signature; signing a normalised JAR and then packing/unpacking later
 *       leaves the signature intact.</li>
 *   <li><b>Reproducible builds &amp; content addressing.</b>
 *       For artefact-hash trust models (SCAP-style, Sigstore, Nix derivations,
 *       {@code reproducible-builds.org}), the same source must produce the same
 *       bytes.  Stock JARs fail this trivially: timestamps in the ZIP central
 *       directory differ per build, entry order depends on the filesystem, and
 *       optional ZIP extra fields leak host-OS attributes.  Normalisation strips
 *       all of these.</li>
 * </ol>
 *
 * <h2>Pipeline</h2>
 *
 * <ol>
 *   <li><b>Step 1 &mdash; Pack200 round-trip.</b>  Pack the input JAR with the
 *       canonical packer settings, then unpack the result.  This step is the
 *       direct equivalent of the deleted OpenJDK code; the only change is that
 *       the {@code SEGMENT_LIMIT} is explicitly pinned to {@code -1} (single
 *       segment, never auto-split) so that segment boundaries do not vary with
 *       input size.</li>
 *   <li><b>Step 2 &mdash; ZIP canonicalisation</b> (omitted for
 *       {@link Options#legacyJarN()}).  Rewrite the unpacked JAR with: entries
 *       sorted by name (manifest first), all timestamps pinned to a single
 *       fixed value, ZIP extra fields cleared, ZIP file comment cleared,
 *       directory entries dropped, and a uniform deflate hint.</li>
 * </ol>
 *
 * <h2>Default behaviour</h2>
 *
 * <p>{@link Options#reproducible()} is the default and is appropriate for
 * SHA-256 content addressing.  {@link Options#legacyJarN()} reproduces the exact
 * behaviour of the historical {@code jar -n} option (round-trip only, ZIP
 * canonicalisation skipped).
 *
 * <h2>What "normalised" guarantees</h2>
 *
 * <p>After {@code Normalize.normalize(in, out)}:
 *
 * <ul>
 *   <li>Every class file in {@code out} is in the canonical Pack200 form: constant
 *       pool entries are sorted into the band order, attribute orders are
 *       canonical, and default attribute values are dropped/restored
 *       consistently.</li>
 *   <li>Repeated invocation of normalize on {@code out} produces {@code out} again
 *       (byte-for-byte).  In other words, {@code out} is a Pack200 fixed point.</li>
 *   <li>{@code pack200(out)} for any conforming Pack200 packer with the same
 *       settings produces a {@code .pack} stream whose decompression yields
 *       {@code out} byte-for-byte.</li>
 *   <li>With {@link Options#reproducible()}: the SHA-256 of {@code out} is a
 *       function of the input class-file contents only, not of build timestamps,
 *       filesystem ordering, or host OS.</li>
 * </ul>
 *
 * <h2>What normalisation does NOT do</h2>
 *
 * <ul>
 *   <li>It does <em>not</em> alter the manifest.  Lines such as
 *       {@code Created-By:} or {@code Build-Jdk:} are passed through verbatim.
 *       Callers who need the manifest to be reproducible must edit it before
 *       calling.</li>
 *   <li>It does <em>not</em> re-sign the JAR.  Existing signatures (the
 *       {@code META-INF/*.SF}, {@code META-INF/*.RSA}, etc. entries) are passed
 *       through but the underlying classfile bytes <em>do</em> change during the
 *       round-trip, so any prior signature will become invalid.  Sign after
 *       normalising, not before.</li>
 *   <li>It does <em>not</em> verify class-file integrity beyond what the
 *       unpacker already requires.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 *
 * <p>{@code Normalize} is stateless and its static methods are thread-safe.  Each
 * call allocates its own {@link Pack200.Packer} and {@link Pack200.Unpacker}, both
 * of which are documented as single-threaded; the call therefore does not share
 * either across threads.
 *
 * <h2>Resources</h2>
 *
 * <p>Each call creates one or two temporary files (one for the intermediate
 * {@code .pack} stream; for the {@link InputStream} overload, an additional one
 * for the spooled input JAR).  Temporary files are deleted in a {@code finally}
 * block even if normalisation throws.
 *
 * @since 1.27
 */
public final class Normalize {

    /**
     * Earliest instant the ZIP DOS time format can represent: 1980-01-01 00:00:00 UTC,
     * as Unix epoch milliseconds.
     *
     * <p>ZIP encodes file times as a 16-bit date plus a 16-bit time relative to
     * 1980-01-01 in local time.  Values earlier than this cannot be represented
     * and are clamped by {@link ZipEntry#setTime}.  Reproducible-builds tooling
     * conventionally uses this value when no other anchor is provided.
     */
    public static final long ZIP_EPOCH_1980_UTC_MILLIS = 315532800000L;

    private Normalize() { /* no instances */ }

    // ------------------------------------------------------------------------
    // Options
    // ------------------------------------------------------------------------

    /**
     * Mutable, fluent options bag for {@link Normalize}.
     *
     * <p>Construct via one of the factory methods:
     * <ul>
     *   <li>{@link #reproducible()} &mdash; full canonicalisation (recommended).</li>
     *   <li>{@link #legacyJarN()} &mdash; bit-for-bit equivalent of the historical
     *       {@code jar -n} option (Pack200 round-trip only).</li>
     * </ul>
     *
     * <p>Individual setters return {@code this} so options can be chained:
     * <pre>{@code
     * Normalize.normalize(in, out,
     *     Normalize.Options.reproducible()
     *         .effort(5)
     *         .fixedTime(SOURCE_DATE_EPOCH_MILLIS));
     * }</pre>
     */
    public static final class Options {

        private int effort = 1;
        private int segmentLimit = -1;
        private boolean dropDirectories = true;
        private boolean fixTimestamps = true;
        private long fixedTimeEpochMillis = ZIP_EPOCH_1980_UTC_MILLIS;
        private boolean sortEntries = true;
        private boolean unifyDeflateHint = true;
        private boolean clearZipComment = true;
        private boolean stripExtra = true;

        private Options() { /* use factories */ }

        /**
         * Returns options that fully canonicalise the JAR: Pack200 round-trip
         * <em>and</em> ZIP-level canonicalisation.  Output is suitable for SHA-256
         * content addressing.
         *
         * @return a fresh Options instance set to {@code reproducible} defaults
         */
        public static Options reproducible() {
            return new Options();
        }

        /**
         * Returns options that reproduce the exact behaviour of the historical
         * OpenJDK {@code jar -n} / {@code jar --normalize} option that was removed
         * by JEP 367: a Pack200 round-trip with {@code EFFORT=1} and the rest of
         * the pipeline disabled.
         *
         * <p>Use this when you need the original normalize semantics, e.g. for
         * sign-then-pack workflows that depended on the pre-JDK-15 behaviour and
         * do <em>not</em> want timestamps or entry order altered beyond what the
         * Pack200 round-trip itself does.
         *
         * @return a fresh Options instance set to {@code legacyJarN} defaults
         */
        public static Options legacyJarN() {
            return new Options()
                    .dropDirectories(false)
                    .fixTimestamps(false)
                    .sortEntries(false)
                    .unifyDeflateHint(false)
                    .clearZipComment(false)
                    .stripExtra(false);
        }

        /**
         * Pack200 effort level (0&ndash;9).  Default: {@code 1}.
         *
         * <p>This matches the value used by the historical {@code jar -n}
         * implementation.  Higher values invest more time in choosing band
         * codings but, for the normalise use case, do not change the
         * <em>output</em> of the round-trip: the unpacker reconstructs the same
         * classfile bytes regardless.  The default {@code 1} is therefore
         * recommended; raise only if you care about the size of the
         * intermediate {@code .pack} stream.
         *
         * @param v effort level, {@code 0} (pass-through) to {@code 9}
         * @return this
         * @throws IllegalArgumentException if {@code v} is not in {@code [0, 9]}
         */
        public Options effort(int v) {
            if (v < 0 || v > 9)
                throw new IllegalArgumentException("effort must be in [0, 9]: " + v);
            this.effort = v;
            return this;
        }

        /**
         * Pack200 segment limit, in bytes.  Default: {@code -1} (single segment,
         * never auto-split).
         *
         * <p>For normalisation you almost always want {@code -1}: it makes
         * Pack200's choice of segment boundaries independent of input size,
         * which would otherwise be a source of nondeterminism if the JAR grows
         * across builds.
         *
         * @param v segment-size hint, or {@code -1} for unlimited
         * @return this
         */
        public Options segmentLimit(int v) {
            this.segmentLimit = v;
            return this;
        }

        /**
         * Whether to drop ZIP directory entries (those whose name ends with
         * {@code /}).  Default: {@code true}.
         *
         * <p>Directory entries are not semantically meaningful for JAR class
         * loading and their presence is filesystem-dependent.  Dropping them
         * is standard reproducible-builds practice.
         */
        public Options dropDirectories(boolean v) {
            this.dropDirectories = v;
            return this;
        }

        /**
         * Whether to pin every entry's modification time to {@link #fixedTime}.
         * Default: {@code true}.
         */
        public Options fixTimestamps(boolean v) {
            this.fixTimestamps = v;
            return this;
        }

        /**
         * Sets the fixed epoch-millis value used when {@link #fixTimestamps} is
         * true.  Default: {@link #ZIP_EPOCH_1980_UTC_MILLIS}.
         *
         * <p>Set this from {@code SOURCE_DATE_EPOCH} (a reproducible-builds
         * convention) when you need outputs from different toolchains to agree
         * on a non-1980 timestamp.
         *
         * @param epochMillis Unix epoch millis; must be {@code >=} the ZIP epoch
         * @return this
         * @throws IllegalArgumentException if earlier than 1980-01-01 UTC
         */
        public Options fixedTime(long epochMillis) {
            if (epochMillis < ZIP_EPOCH_1980_UTC_MILLIS)
                throw new IllegalArgumentException(
                    "ZIP cannot represent times before 1980-01-01 UTC: " + epochMillis);
            this.fixedTimeEpochMillis = epochMillis;
            return this;
        }

        /**
         * Whether to sort entries lexicographically by name, with the manifest
         * forced first.  Default: {@code true}.
         */
        public Options sortEntries(boolean v) {
            this.sortEntries = v;
            return this;
        }

        /**
         * Whether to write every entry as DEFLATED regardless of how the unpacker
         * emitted it.  Default: {@code true}.
         *
         * <p>The Pack200 unpacker can vary its choice of STORED vs. DEFLATED for
         * small entries; forcing DEFLATED removes that variance.  Per-entry
         * compression level inside the DEFLATE stream is set by the JDK's ZIP
         * implementation and is deterministic for a given input and JDK
         * version.
         */
        public Options unifyDeflateHint(boolean v) {
            this.unifyDeflateHint = v;
            return this;
        }

        /**
         * Whether to clear the ZIP file comment.  Default: {@code true}.
         *
         * <p>The Pack200 unpacker writes the literal string {@code "PACK200"} as
         * the ZIP comment to mark the JAR as a Pack200 round-trip output (see
         * {@link net.pack200.Pack200.Unpacker}).  Clearing it removes a marker
         * that varies with the implementation if a future version of the
         * unpacker changes the literal.
         */
        public Options clearZipComment(boolean v) {
            this.clearZipComment = v;
            return this;
        }

        /**
         * Whether to strip per-entry ZIP "extra" fields (Unix permissions,
         * NTFS timestamps, Info-ZIP UID/GID, etc.).  Default: {@code true}.
         *
         * <p>These fields are filled in by the host OS during ZIP packing and
         * are a major source of cross-platform nondeterminism.
         */
        public Options stripExtra(boolean v) {
            this.stripExtra = v;
            return this;
        }

        /** Returns whether any Step-2 ZIP canonicalisation is required. */
        boolean needsPostProcessing() {
            return dropDirectories || fixTimestamps || sortEntries
                || clearZipComment || stripExtra || unifyDeflateHint;
        }
    }

    // ------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------

    /**
     * Normalises {@code in} to {@code out} using {@link Options#reproducible()}.
     *
     * @param in  input JAR file (read)
     * @param out destination JAR file (overwritten)
     * @throws IOException if reading, packing, unpacking or writing fails
     * @throws NullPointerException if either argument is {@code null}
     */
    public static void normalize(File in, File out) throws IOException {
        normalize(in, out, Options.reproducible());
    }

    /**
     * Normalises {@code in} to {@code out}.
     *
     * @param in   input JAR file
     * @param out  destination JAR file (overwritten)
     * @param opts options controlling the pipeline
     * @throws IOException if any step fails
     * @throws NullPointerException if any argument is {@code null}
     */
    public static void normalize(File in, File out, Options opts) throws IOException {
        Objects.requireNonNull(in,   "in");
        Objects.requireNonNull(out,  "out");
        Objects.requireNonNull(opts, "opts");
        try (JarFile jf = new JarFile(in, false);
             OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            normalize(jf, os, opts);
        }
    }

    /**
     * Normalises {@code in} to {@code out} using {@link Options#reproducible()}.
     * Closes {@code in}; does not close {@code out}.
     *
     * @param in  input JAR
     * @param out destination stream &mdash; <em>not</em> closed by this call
     * @throws IOException if any step fails
     * @throws NullPointerException if any argument is {@code null}
     */
    public static void normalize(JarFile in, OutputStream out) throws IOException {
        normalize(in, out, Options.reproducible());
    }

    /**
     * Normalises {@code in} to {@code out}.  Closes {@code in}; does not close
     * {@code out}.
     *
     * <p>This is the core overload.  The {@link File} and {@link InputStream}
     * overloads delegate to this one.
     *
     * @param in   input JAR
     * @param out  destination stream &mdash; <em>not</em> closed by this call
     * @param opts options controlling the pipeline
     * @throws IOException if any step fails
     * @throws NullPointerException if any argument is {@code null}
     */
    public static void normalize(JarFile in, OutputStream out, Options opts) throws IOException {
        Objects.requireNonNull(in,   "in");
        Objects.requireNonNull(out,  "out");
        Objects.requireNonNull(opts, "opts");

        Path tmpPack = Files.createTempFile("pack200-normalize-", ".pack");
        try {
            // ---- Step 1: Pack200 round-trip ---------------------------------
            try (OutputStream packOut = new BufferedOutputStream(
                    Files.newOutputStream(tmpPack, StandardOpenOption.TRUNCATE_EXISTING))) {
                Pack200.Packer packer = Pack200.newPacker();
                Map<String, String> p = packer.properties();
                // EFFORT=1 matches the historical OpenJDK jar -n behaviour.
                p.put(Pack200.Packer.EFFORT, Integer.toString(opts.effort));
                // SEGMENT_LIMIT=-1 makes segment boundaries independent of input size.
                p.put(Pack200.Packer.SEGMENT_LIMIT, Integer.toString(opts.segmentLimit));
                // KEEP_FILE_ORDER=FALSE lets the packer drop directory entries
                // and reorder for better compression; we re-sort canonically in
                // Step 2 anyway, so this is purely an optimisation hint.
                p.put(Pack200.Packer.KEEP_FILE_ORDER,
                        opts.sortEntries ? Pack200.Packer.FALSE : Pack200.Packer.TRUE);
                // Smear per-entry modification times if we're going to overwrite
                // them in Step 2 anyway.
                if (opts.fixTimestamps) {
                    p.put(Pack200.Packer.MODIFICATION_TIME, Pack200.Packer.LATEST);
                }
                // Pin the deflate hint if we're going to overwrite it.
                if (opts.unifyDeflateHint) {
                    p.put(Pack200.Packer.DEFLATE_HINT, Pack200.Packer.TRUE);
                }
                packer.pack(in, packOut);
            }

            // ---- Step 2: ZIP canonicalisation (optional) --------------------
            if (!opts.needsPostProcessing()) {
                // legacyJarN(): unpack straight into the destination stream and stop.
                try (JarOutputStream jos = new JarOutputStream(noClose(out))) {
                    Pack200.newUnpacker().unpack(tmpPack.toFile(), jos);
                }
                return;
            }

            // Unpack to an intermediate JAR file, then rewrite it canonically.
            Path tmpJar = Files.createTempFile("pack200-normalize-", ".jar");
            try {
                try (JarOutputStream jos = new JarOutputStream(new BufferedOutputStream(
                        Files.newOutputStream(tmpJar, StandardOpenOption.TRUNCATE_EXISTING)))) {
                    Pack200.newUnpacker().unpack(tmpPack.toFile(), jos);
                }
                rewriteCanonical(tmpJar, out, opts);
            } finally {
                try { Files.deleteIfExists(tmpJar); } catch (IOException ignored) { /* best effort */ }
            }
        } finally {
            try { Files.deleteIfExists(tmpPack); } catch (IOException ignored) { /* best effort */ }
        }
    }

    /**
     * Normalises a streamed JAR.  Closes {@code in}; does not close {@code out}.
     *
     * <p>The input is fully buffered to a temporary file first, because
     * {@link Pack200.Packer#pack(JarFile, OutputStream)} requires random access
     * to the input archive.
     *
     * @param in   input stream containing a JAR
     * @param out  destination stream &mdash; <em>not</em> closed by this call
     * @param opts options controlling the pipeline
     * @throws IOException if any step fails
     * @throws NullPointerException if any argument is {@code null}
     */
    public static void normalize(InputStream in, OutputStream out, Options opts) throws IOException {
        Objects.requireNonNull(in,   "in");
        Objects.requireNonNull(out,  "out");
        Objects.requireNonNull(opts, "opts");

        Path tmpIn = Files.createTempFile("pack200-normalize-in-", ".jar");
        try {
            try (OutputStream s = new BufferedOutputStream(
                    Files.newOutputStream(tmpIn, StandardOpenOption.TRUNCATE_EXISTING))) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    s.write(buf, 0, n);
                }
            } finally {
                in.close();
            }
            try (JarFile jf = new JarFile(tmpIn.toFile(), false)) {
                normalize(jf, out, opts);
            }
        } finally {
            try { Files.deleteIfExists(tmpIn); } catch (IOException ignored) { /* best effort */ }
        }
    }

    // ------------------------------------------------------------------------
    // Step 2 implementation
    // ------------------------------------------------------------------------

    /**
     * Rewrites the JAR at {@code src} into {@code out}, applying the canonical
     * ZIP-level transformations selected in {@code opts}.
     */
    private static void rewriteCanonical(Path src, OutputStream out, Options opts) throws IOException {
        try (JarFile jf = new JarFile(src.toFile(), false)) {
            List<JarEntry> entries = new ArrayList<>();
            for (Enumeration<JarEntry> en = jf.entries(); en.hasMoreElements(); ) {
                JarEntry e = en.nextElement();
                if (opts.dropDirectories && e.isDirectory()) continue;
                entries.add(e);
            }
            if (opts.sortEntries) {
                entries.sort((a, b) -> {
                    boolean am = JarFile.MANIFEST_NAME.equals(a.getName());
                    boolean bm = JarFile.MANIFEST_NAME.equals(b.getName());
                    if (am && !bm) return -1;
                    if (bm && !am) return  1;
                    return a.getName().compareTo(b.getName());
                });
            }

            // Use ZipOutputStream rather than JarOutputStream: the latter
            // unconditionally adds a 4-byte 0xCAFE magic to the extra field of
            // the first META-INF/* entry as a "this is a JAR" marker, which
            // (a) defeats stripExtra and (b) would have to be replicated
            // identically across runs to stay reproducible.  The output is
            // still a valid JAR because java.util.jar.JarFile recognises any
            // ZIP that contains META-INF/MANIFEST.MF as a JAR.
            try (ZipOutputStream zos = new ZipOutputStream(noClose(out))) {
                if (opts.clearZipComment) {
                    zos.setComment(null);
                }
                byte[] readBuf = new byte[8192];
                for (JarEntry src2 : entries) {
                    ZipEntry ze = new ZipEntry(src2.getName());

                    int method = opts.unifyDeflateHint
                            ? ZipEntry.DEFLATED
                            : src2.getMethod();
                    ze.setMethod(method);

                    // Slurp the entry data.
                    byte[] data;
                    try (InputStream is = jf.getInputStream(src2)) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream(
                                Math.max(64, (int) Math.min(Integer.MAX_VALUE, src2.getSize())));
                        int n;
                        while ((n = is.read(readBuf)) > 0) baos.write(readBuf, 0, n);
                        data = baos.toByteArray();
                    }
                    if (method == ZipEntry.STORED) {
                        ze.setSize(data.length);
                        ze.setCompressedSize(data.length);
                        CRC32 crc = new CRC32();
                        crc.update(data);
                        ze.setCrc(crc.getValue());
                    }

                    if (opts.fixTimestamps) {
                        // ZipEntry#setTime(long) on modern JDKs populates both
                        // the legacy DOS time and the FileTime mtime field;
                        // ZipOutputStream then emits a per-entry Extended-
                        // Timestamp extra block (0x5455 / "UT") in addition.
                        // That block is deterministic for a given fixedTime,
                        // so reproducibility is preserved, but stripExtra
                        // cannot remove it without reflection.  We document
                        // this as a known artifact of using the JDK ZIP writer.
                        ze.setTime(opts.fixedTimeEpochMillis);
                    }
                    if (opts.stripExtra) {
                        ze.setExtra(null);
                    }

                    zos.putNextEntry(ze);
                    zos.write(data);
                    zos.closeEntry();
                }
            }
        }
    }

    /**
     * Wraps {@code os} so that {@link OutputStream#close} flushes but does not
     * propagate to the underlying stream.  Required because callers retain
     * ownership of the destination stream they pass in.
     */
    private static OutputStream noClose(OutputStream os) {
        return new FilterOutputStream(os) {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len); // FilterOutputStream's default writes byte-by-byte
            }
            @Override
            public void close() throws IOException {
                flush();
                /* deliberately do not close the wrapped stream */
            }
        };
    }
}
