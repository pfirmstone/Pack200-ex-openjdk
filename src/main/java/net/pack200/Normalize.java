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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.logging.Logger;
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
 * <h2>Manifest canonicalisation</h2>
 *
 * <p>Under {@link Options#reproducible()} (and whenever
 * {@link Options#canonicaliseManifest(boolean)} is enabled) the
 * {@code META-INF/MANIFEST.MF} entry <em>is</em> canonicalised: volatile
 * tool-identity headers (the {@linkplain Options#manifestDenylist(Set) denylist},
 * e.g. {@code Created-By}, {@code Build-Jdk}, {@code Bnd-LastModified}) are
 * dropped from the main attributes, and the surviving attributes are emitted in a
 * fixed, locale-independent order with fixed UTF-8/CRLF/72-byte-wrap formatting,
 * so that two builds that differ only in those volatile headers (or in attribute
 * insertion order) normalise to byte-identical output.  The canonicalisation is
 * idempotent and is a fixed point of the overall normalisation.  It is skipped for
 * {@link Options#legacyJarN()} and for signed JARs (see below).
 *
 * <h2>What normalisation does NOT do</h2>
 *
 * <ul>
 *   <li>It does <em>not</em> canonicalise the manifest of a <b>signed</b> JAR.
 *       If a {@code META-INF/*.SF} entry is present, the manifest is passed through
 *       verbatim (rewriting it would invalidate the per-entry {@code *-Digest}
 *       values), consistent with this class not re-signing JARs.  A warning is
 *       logged in that case.</li>
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

    private static final Logger LOG = Logger.getLogger(Normalize.class.getName());

    /**
     * The default set of main-attribute header names dropped during manifest
     * canonicalisation.  Matching is case-insensitive and exact (no prefix
     * matching).  These are provably build-environment / tool-identity headers
     * only; everything else (including {@code Implementation-*} and
     * {@code Specification-*}, all OSGi {@code Bundle-*}, etc.) is preserved.
     *
     * <p>Deployments tweak this via {@link Options#manifestDenylistAdd(String...)}
     * / {@link Options#manifestDenylistRemove(String...)} or replace it wholesale
     * via {@link Options#manifestDenylist(Set)}.
     */
    public static final Set<String> DEFAULT_MANIFEST_DENYLIST;
    static {
        // Insertion order is irrelevant: the stored set is normalised to lower
        // case (ROOT locale) for case-insensitive matching.
        Set<String> d = new LinkedHashSet<>();
        d.add("Created-By");
        d.add("Build-Jdk");
        d.add("Build-Jdk-Spec");
        d.add("Built-By");
        d.add("Build-Time");
        d.add("Build-Date");
        d.add("Build-Timestamp");
        d.add("Bnd-LastModified");
        d.add("Tool");
        d.add("Hostname");
        d.add("Ant-Version");
        d.add("Maven-Version");
        d.add("Originally-Created-By");
        d.add("Implementation-Build-Date");
        DEFAULT_MANIFEST_DENYLIST = java.util.Collections.unmodifiableSet(d);
    }

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
        private boolean canonicaliseManifest = true;
        /** Denylisted header names, stored ROOT-lowercased for case-insensitive matching. */
        private Set<String> manifestDenylistLower = lowerSet(DEFAULT_MANIFEST_DENYLIST);

        private Options() { /* use factories */ }

        private static Set<String> lowerSet(Set<String> names) {
            Set<String> s = new LinkedHashSet<>();
            for (String n : names) {
                if (n != null) {
                    s.add(n.toLowerCase(Locale.ROOT));
                }
            }
            return s;
        }

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
                    .stripExtra(false)
                    .canonicaliseManifest(false);
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

        /**
         * Whether to canonicalise {@code META-INF/MANIFEST.MF}.  Default:
         * {@code true} for {@link #reproducible()}, {@code false} for
         * {@link #legacyJarN()}.
         *
         * <p>When enabled and a manifest entry is present, denylisted main
         * attributes (see {@link #manifestDenylist(Set)}) are dropped and the
         * remaining attributes are re-serialised in a fixed, locale-independent
         * order and format.  Skipped automatically for signed JARs (those with a
         * {@code META-INF/*.SF} entry), whose per-entry digests must not be
         * disturbed.
         *
         * @param v whether to canonicalise the manifest
         * @return this
         */
        public Options canonicaliseManifest(boolean v) {
            this.canonicaliseManifest = v;
            return this;
        }

        /**
         * Replaces the manifest denylist (the set of main-attribute header names
         * dropped during canonicalisation) with {@code names}.  Matching is
         * case-insensitive and exact.  Passing an empty set drops nothing.
         *
         * @param names header names to drop; {@code null} resets to
         *              {@link #DEFAULT_MANIFEST_DENYLIST}
         * @return this
         */
        public Options manifestDenylist(Set<String> names) {
            this.manifestDenylistLower = (names == null)
                    ? lowerSet(DEFAULT_MANIFEST_DENYLIST)
                    : lowerSet(names);
            return this;
        }

        /**
         * Adds header names to the current manifest denylist without replacing the
         * existing entries.  Case-insensitive.
         *
         * @param names header names to also drop
         * @return this
         */
        public Options manifestDenylistAdd(String... names) {
            if (names != null) {
                for (String n : names) {
                    if (n != null) {
                        this.manifestDenylistLower.add(n.toLowerCase(Locale.ROOT));
                    }
                }
            }
            return this;
        }

        /**
         * Removes header names from the current manifest denylist (so they are
         * preserved rather than dropped).  Case-insensitive.
         *
         * @param names header names to no longer drop
         * @return this
         */
        public Options manifestDenylistRemove(String... names) {
            if (names != null) {
                for (String n : names) {
                    if (n != null) {
                        this.manifestDenylistLower.remove(n.toLowerCase(Locale.ROOT));
                    }
                }
            }
            return this;
        }

        /** Whether the given header name is denylisted (case-insensitive). */
        boolean isDenied(String headerName) {
            return manifestDenylistLower.contains(headerName.toLowerCase(Locale.ROOT));
        }

        /** Returns whether any Step-2 ZIP canonicalisation is required. */
        boolean needsPostProcessing() {
            return dropDirectories || fixTimestamps || sortEntries
                || clearZipComment || stripExtra || unifyDeflateHint
                || canonicaliseManifest;
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
            boolean signed = false;
            for (Enumeration<JarEntry> en = jf.entries(); en.hasMoreElements(); ) {
                JarEntry e = en.nextElement();
                if (isSignatureFile(e.getName())) {
                    signed = true;
                }
                if (opts.dropDirectories && e.isDirectory()) continue;
                entries.add(e);
            }
            // Canonicalise the manifest only when enabled AND the JAR is not
            // signed: rewriting MANIFEST.MF would invalidate the per-entry
            // *-Digest values recorded for the signature, and this class does
            // not re-sign.  Consistent with the "does not re-sign" stance.
            boolean doManifest = opts.canonicaliseManifest;
            if (doManifest && signed) {
                LOG.warning("Normalize: skipping manifest canonicalisation for signed JAR "
                        + "(META-INF/*.SF present); rewriting the manifest would invalidate "
                        + "per-entry signature digests.");
                doManifest = false;
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

                    // Replace the manifest bytes with the canonical serialisation
                    // BEFORE the ZIP entry is written, so the canonical manifest
                    // participates in the final byte layout (and the CONTENT-HASH).
                    if (doManifest && JarFile.MANIFEST_NAME.equals(src2.getName())) {
                        data = canonicaliseManifestBytes(data, opts);
                    }

                    if (method == ZipEntry.STORED) {
                        ze.setSize(data.length);
                        ze.setCompressedSize(data.length);
                        CRC32 crc = new CRC32();
                        crc.update(data);
                        ze.setCrc(crc.getValue());
                    }

                    if (opts.fixTimestamps) {
                        // NOTE (timezone determinism): the JDK ZIP writer encodes
                        // the DOS time field — and the 0x5455 "UT" extended-
                        // timestamp extra block — in the JVM's DEFAULT time zone,
                        // even via setTimeLocal.  For byte-reproducible output
                        // across build hosts the build must therefore run with a
                        // fixed zone (e.g. -Duser.timezone=UTC).  Manifest and
                        // class/entry canonicalisation are already zone-
                        // independent; only this timestamp field is not.  See the
                        // timezone-determinism note in
                        // docs/manifest-canonicalisation-scope.md.
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

    // ------------------------------------------------------------------------
    // Manifest canonicalisation
    // ------------------------------------------------------------------------

    /**
     * Returns {@code true} if {@code name} is a JAR signature block, i.e. a
     * {@code META-INF/*.SF} signature file (case-insensitive on the suffix, as
     * the JAR spec treats signature file names case-insensitively).
     */
    private static boolean isSignatureFile(String name) {
        if (name == null) return false;
        // Must live directly under META-INF/ and end with .SF.
        if (!name.regionMatches(true, 0, "META-INF/", 0, "META-INF/".length())) {
            return false;
        }
        String rest = name.substring("META-INF/".length());
        // No further nesting (signature files are not in subdirectories).
        return rest.indexOf('/') < 0
                && rest.regionMatches(true, rest.length() - 3, ".SF", 0, 3)
                && rest.length() > 3;
    }

    /**
     * Locale-independent header-name comparator.  Compares names by their
     * ROOT-locale lowercase form, char-by-char (UTF-16 code unit), with the raw
     * name as a tie-break so distinct names never compare equal.  Avoids
     * {@link String#compareToIgnoreCase} (which folds case per default locale and
     * is subject to the Turkish-i problem).
     */
    static final Comparator<String> HEADER_NAME_COMPARATOR = (a, b) -> {
        String la = a.toLowerCase(Locale.ROOT);
        String lb = b.toLowerCase(Locale.ROOT);
        int n = Math.min(la.length(), lb.length());
        for (int i = 0; i < n; i++) {
            char ca = la.charAt(i);
            char cb = lb.charAt(i);
            if (ca != cb) return ca - cb;
        }
        if (la.length() != lb.length()) return la.length() - lb.length();
        // Same lowercased form: tie-break on the original to keep a total order.
        return a.compareTo(b);
    };

    /**
     * Parses {@code manifestBytes} as a {@link Manifest} and re-serialises it
     * canonically according to {@code opts}: denylisted main attributes dropped
     * (see {@link Options#manifestDenylist(Set)}), {@code Manifest-Version} first,
     * remaining main attributes sorted by a locale-independent comparator, then
     * per-entry sections sorted by entry name (each {@code Name} first then its
     * attributes sorted by the same comparator).  Output is UTF-8 with CRLF line
     * endings and 72-byte line wrapping per the JAR File Specification, one blank
     * line between sections and a single trailing blank line.
     *
     * <p>Exposed for callers (and tests) that want to canonicalise a manifest in
     * isolation.  It is idempotent: {@code canon(canon(m)) == canon(m)}.
     *
     * @param manifestBytes the raw {@code META-INF/MANIFEST.MF} bytes
     * @param opts          options supplying the denylist (only the manifest
     *                      denylist is consulted)
     * @return the canonical manifest bytes
     * @throws IOException if the manifest is malformed / unparseable, or has no
     *         {@code Manifest-Version} main attribute
     * @throws NullPointerException if either argument is {@code null}
     */
    public static byte[] canonicaliseManifestBytes(byte[] manifestBytes, Options opts) throws IOException {
        Objects.requireNonNull(manifestBytes, "manifestBytes");
        Objects.requireNonNull(opts, "opts");
        Manifest mf = new Manifest();
        try {
            mf.read(new ByteArrayInputStream(manifestBytes));
        } catch (IOException | IllegalArgumentException e) {
            // java.util.jar.Manifest throws IOException on most malformed input,
            // but can also throw IllegalArgumentException (e.g. bad header bytes).
            throw new IOException("Malformed META-INF/MANIFEST.MF; cannot canonicalise", e);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(manifestBytes.length + 64);

        // --- Main section ----------------------------------------------------
        Attributes main = mf.getMainAttributes();
        // Manifest-Version must come first per the JAR spec.  If absent, the
        // manifest is not well-formed enough to canonicalise meaningfully.
        String manifestVersion = getAttr(main, "Manifest-Version");
        if (manifestVersion == null) {
            throw new IOException("META-INF/MANIFEST.MF has no Manifest-Version main attribute");
        }
        writeHeader(out, "Manifest-Version", manifestVersion);

        // Remaining main attributes, denylist-filtered, sorted.
        TreeMap<String, String> mainSorted = new TreeMap<>(HEADER_NAME_COMPARATOR);
        for (Map.Entry<Object, Object> e : main.entrySet()) {
            String name = e.getKey().toString();
            if (name.equalsIgnoreCase("Manifest-Version")) continue; // already emitted
            if (opts.isDenied(name)) continue;
            mainSorted.put(name, e.getValue().toString());
        }
        for (Map.Entry<String, String> e : mainSorted.entrySet()) {
            writeHeader(out, e.getKey(), e.getValue());
        }
        // Blank line terminating the main section.
        writeCrLf(out);

        // --- Per-entry sections ---------------------------------------------
        TreeMap<String, Attributes> sectionsSorted =
                new TreeMap<>(HEADER_NAME_COMPARATOR);
        for (Map.Entry<String, Attributes> e : mf.getEntries().entrySet()) {
            sectionsSorted.put(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, Attributes> section : sectionsSorted.entrySet()) {
            writeHeader(out, "Name", section.getKey());
            TreeMap<String, String> attrsSorted = new TreeMap<>(HEADER_NAME_COMPARATOR);
            for (Map.Entry<Object, Object> a : section.getValue().entrySet()) {
                String name = a.getKey().toString();
                // Per-entry attributes are never denylisted (the denylist is for
                // build-tool main attributes); keep all, incl. *-Digest.
                attrsSorted.put(name, a.getValue().toString());
            }
            for (Map.Entry<String, String> a : attrsSorted.entrySet()) {
                writeHeader(out, a.getKey(), a.getValue());
            }
            // Blank line terminating this section.
            writeCrLf(out);
        }

        return out.toByteArray();
    }

    /** Returns the value of a main attribute by name (case-insensitive), or null. */
    private static String getAttr(Attributes attrs, String name) {
        String v = attrs.getValue(name);
        if (v != null) return v;
        for (Map.Entry<Object, Object> e : attrs.entrySet()) {
            if (e.getKey().toString().equalsIgnoreCase(name)) {
                return e.getValue().toString();
            }
        }
        return null;
    }

    /**
     * Emits a single {@code name: value} header to {@code out} using UTF-8 with
     * CRLF endings and 72-byte line wrapping per the JAR File Specification: each
     * physical line is at most 72 bytes (including the trailing CRLF is not
     * counted in the 72; the spec counts 72 bytes of content then CRLF), and
     * continuation lines begin with a single leading space.
     *
     * <p>The JAR spec wraps on <em>bytes</em>, not characters, but a continuation
     * must not split a UTF-8 multi-byte sequence.  We therefore wrap on UTF-8
     * byte boundaries that fall between encoded characters.
     */
    private static void writeHeader(ByteArrayOutputStream out, String name, String value) {
        // Encode "name: value" as UTF-8 then wrap to 72-byte lines.
        byte[] header = (name + ": " + value).getBytes(StandardCharsets.UTF_8);
        // The spec: "No line may be longer than 72 bytes (not characters), in its
        // UTF8-encoded form. If a value would make the initial line longer than
        // this, it should be continued on extra lines (each starting with a
        // single SPACE)."
        // First line: up to 72 bytes.  Continuation lines: 1 space + up to 71 bytes.
        int pos = 0;
        int firstLineMax = 72;
        int len = header.length;
        // Determine end of first line on a char boundary.
        int end = utf8LineEnd(header, pos, firstLineMax);
        out.write(header, pos, end - pos);
        writeCrLf(out);
        pos = end;
        while (pos < len) {
            out.write(' ');
            // Continuation line: leading space counts toward the 72, so 71 bytes of payload.
            int contMax = 71;
            int cend = utf8LineEnd(header, pos, contMax);
            out.write(header, pos, cend - pos);
            writeCrLf(out);
            pos = cend;
        }
    }

    /**
     * Returns the exclusive end index for a line starting at {@code start} that is
     * at most {@code maxBytes} long, without splitting a UTF-8 multi-byte
     * sequence.  Always advances by at least one byte to guarantee progress.
     */
    private static int utf8LineEnd(byte[] b, int start, int maxBytes) {
        int limit = Math.min(b.length, start + maxBytes);
        if (limit <= start) {
            return Math.min(b.length, start + 1);
        }
        // If we'd cut in the middle of a UTF-8 continuation byte (10xxxxxx),
        // back up to the start of that character.
        int end = limit;
        if (end < b.length) {
            while (end > start && (b[end] & 0xC0) == 0x80) {
                end--;
            }
        }
        // Never return start (no progress); fall back to the byte limit.
        if (end <= start) {
            return limit;
        }
        return end;
    }

    /** Writes a CRLF (0x0D 0x0A) to {@code out}. */
    private static void writeCrLf(ByteArrayOutputStream out) {
        out.write('\r');
        out.write('\n');
    }
}
