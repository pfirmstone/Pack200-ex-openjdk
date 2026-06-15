/*
 * Copyright (c) 2026, Peter Firmstone.
 *
 * Licensed under the GNU General Public License version 2 with the
 * Classpath Exception (matching the parent Pack200-ex-openjdk project).
 */
package net.pack200.maven;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import net.pack200.Normalize;

/**
 * Runs {@link net.pack200.Normalize#normalize(java.io.InputStream, java.io.OutputStream,
 * net.pack200.Normalize.Options) Normalize.normalize} on the JAR Maven just built, then
 * (optionally) stamps a verifiable {@code SHA-256} content-hash line into a single
 * extra {@code META-INF} entry. The result replaces the original JAR.
 *
 * <h2>Stamp semantics</h2>
 * The stamp records {@code SHA-256(C)} &mdash; the hash of the canonical JAR
 * <em>before</em> the stamp entry is appended &mdash; <strong>not</strong> the hash of
 * the final stamped bytes. A consumer verifies a stamped JAR by reading the stamp,
 * removing the stamp entry, and recomputing {@code SHA-256} over the result; the two
 * must match.
 *
 * <p>The stamp entry is the last entry of the output JAR, stored uncompressed
 * ({@link ZipEntry#STORED}) with a fixed modification time and explicit CRC, so the
 * surrounding bytes remain deterministic across runs.
 *
 * <h2>Stamp format</h2>
 * <ul>
 *   <li>Entry name: configurable via {@code stampEntryName} (default
 *       {@code META-INF/CONTENT-HASH}).</li>
 *   <li>Entry contents (UTF-8): exactly {@code SHA-256:<hex>\n} where {@code <hex>} is
 *       64 lowercase hexadecimal characters and the terminator is a single LF
 *       ({@code 0x0A}).</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * <plugin>
 *   <groupId>au.net.zeus.pack200-ex-openjdk</groupId>
 *   <artifactId>pack200-normalize-maven-plugin</artifactId>
 *   <version>1.27.1</version>
 *   <executions>
 *     <execution>
 *       <goals><goal>normalize</goal></goals>
 *     </execution>
 *   </executions>
 * </plugin>
 * }</pre>
 */
@Mojo(name = "normalize",
      defaultPhase = LifecyclePhase.PACKAGE,
      threadSafe = true,
      requiresProject = true)
public class NormalizeMojo extends AbstractMojo {

    /** Fixed DOS time (1980-01-01 00:00:00 local) so all stamp ZIP local-headers are byte-stable. */
    private static final long FIXED_DOS_TIME = 0L; // ZipEntry.setTime(0) maps to 1980-01-01 in DOS time.

    /**
     * JAR to normalise. Defaults to the artifact Maven just built for this module.
     */
    @Parameter(defaultValue = "${project.build.directory}/${project.build.finalName}.jar",
               property = "pack200.normalize.inputFile",
               required = true)
    private File inputFile;

    /**
     * Where to write the normalised (and optionally stamped) JAR. When unset, the
     * input is replaced in place atomically (write {@code <output>.tmp}, then rename).
     */
    @Parameter(property = "pack200.normalize.outputFile")
    private File outputFile;

    /**
     * If {@code true}, append a {@code META-INF/CONTENT-HASH} entry recording the
     * SHA-256 of the canonical (pre-stamp) JAR bytes.
     */
    @Parameter(defaultValue = "true", property = "pack200.normalize.attachStamp")
    private boolean attachStamp;

    /**
     * The ZIP entry name under which the stamp is written.
     */
    @Parameter(defaultValue = "META-INF/CONTENT-HASH",
               property = "pack200.normalize.stampEntryName")
    private String stampEntryName;

    /**
     * If {@code true} and the input already carries a stamp entry whose recorded hash
     * matches the SHA-256 of the input with that stamp entry removed, the mojo skips
     * the work and leaves the file unchanged (idempotent fast path).
     */
    @Parameter(defaultValue = "false", property = "pack200.normalize.failOnAlreadyStamped")
    private boolean failOnAlreadyStamped;

    /**
     * Skip execution entirely.
     */
    @Parameter(defaultValue = "false", property = "pack200.normalize.skip")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("pack200-normalize: skip=true, doing nothing");
            return;
        }

        if (inputFile == null) {
            throw new MojoExecutionException("inputFile is not configured");
        }

        if (!inputFile.isFile()) {
            throw new MojoExecutionException("Input JAR does not exist: " + inputFile
                + " (this goal must be bound to a module that produces a JAR; for "
                + "packaging=pom modules, set <skip>true</skip> or unbind the execution)");
        }

        File effectiveOutput = (outputFile != null) ? outputFile : inputFile;

        try {
            run(effectiveOutput);
        } catch (IOException e) {
            throw new MojoExecutionException(
                "Failed to normalise " + inputFile + " -> " + effectiveOutput + ": " + e, e);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by every JRE.
            throw new MojoExecutionException("SHA-256 not available: " + e, e);
        }
    }

    private void run(File effectiveOutput) throws IOException, NoSuchAlgorithmException,
                                                   MojoExecutionException {
        long inSize = inputFile.length();

        // Fast-path: input already carries a self-consistent stamp.
        if (failOnAlreadyStamped && hasMatchingStamp(inputFile)) {
            getLog().info("pack200-normalize: input " + inputFile
                + " already contains a self-consistent " + stampEntryName + "; leaving unchanged");
            return;
        }

        // 1. Read input bytes (Maven-produced JAR; size is bounded by the build artifact).
        byte[] inputBytes = Files.readAllBytes(inputFile.toPath());

        // 1a. If the input already carries our stamp, strip it before normalising so
        //     that re-running the mojo on a stamped output is byte-equal to running it
        //     once on the original (the stamp would otherwise be normalised as content
        //     and a second stamp appended on top).
        byte[] strippedInput = jarBytesWithEntryRemoved(inputBytes, stampEntryName);
        if (strippedInput != null) {
            inputBytes = strippedInput;
        }

        // 2. Normalise into memory.
        ByteArrayOutputStream normalised = new ByteArrayOutputStream(inputBytes.length);
        try (InputStream in = new ByteArrayInputStream(inputBytes)) {
            Normalize.normalize(in, normalised, Normalize.Options.reproducible());
        }
        byte[] canonical = normalised.toByteArray();

        // 3. Hash the canonical (pre-stamp) bytes.
        String canonicalHashHex = sha256Hex(canonical);

        // 4. Optionally append stamp entry.
        byte[] finalBytes;
        if (attachStamp) {
            finalBytes = appendStamp(canonical, stampEntryName, canonicalHashHex);
        } else {
            finalBytes = canonical;
        }

        // 5. Atomic write: <output>.tmp -> rename to <output>.
        atomicWrite(effectiveOutput, finalBytes);

        // 6. Log.
        getLog().info("pack200-normalize: input  = " + inputFile + " (" + inSize + " bytes)");
        getLog().info("pack200-normalize: output = " + effectiveOutput + " (" + finalBytes.length + " bytes)");
        getLog().info("pack200-normalize: SHA-256(canonical) = " + canonicalHashHex);
        getLog().info("pack200-normalize: stamp added = " + attachStamp
            + (attachStamp ? " (" + stampEntryName + ")" : ""));
    }

    /**
     * Returns {@code true} iff {@code jar} contains {@link #stampEntryName} and that
     * entry records the SHA-256 of {@code jar} with the stamp entry removed.
     */
    private boolean hasMatchingStamp(File jar) throws IOException, NoSuchAlgorithmException {
        String recorded = readStampHash(jar);
        if (recorded == null) {
            return false;
        }
        byte[] withoutStamp = jarWithEntryRemoved(jar, stampEntryName);
        return recorded.equalsIgnoreCase(sha256Hex(withoutStamp));
    }

    /**
     * Reads {@code stampEntryName} from {@code jar} and parses the {@code SHA-256:<hex>}
     * value. Returns {@code null} if absent or malformed.
     */
    private String readStampHash(File jar) throws IOException {
        try (JarFile jf = new JarFile(jar, false)) {
            JarEntry e = jf.getJarEntry(stampEntryName);
            if (e == null) {
                return null;
            }
            byte[] body;
            try (InputStream s = jf.getInputStream(e)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[256];
                int n;
                while ((n = s.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                body = bos.toByteArray();
            }
            String text = new String(body, StandardCharsets.UTF_8).trim();
            if (!text.startsWith("SHA-256:")) {
                return null;
            }
            String hex = text.substring("SHA-256:".length()).trim();
            if (hex.length() != 64) {
                return null;
            }
            for (int i = 0; i < hex.length(); i++) {
                char c = hex.charAt(i);
                boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
                if (!ok) {
                    return null;
                }
            }
            return hex;
        }
    }

    /**
     * Returns the bytes of {@code jar} with the entry named {@code dropEntry} surgically
     * removed at the ZIP level: the entry's local-file-header + payload are deleted, its
     * central-directory header is deleted, all offsets in subsequent central-directory
     * entries are decremented by the removed LFH+data length, and the EOCD is rewritten
     * with the new counts/offsets.
     *
     * <p>If {@code dropEntry} was the only/last entry appended by
     * {@link #appendStamp(byte[], String, String)}, this is the exact inverse, so
     * {@code SHA-256(jarWithEntryRemoved(C_stamped))} equals {@code SHA-256(C)}.
     *
     * <p>Returns {@code null} if the entry is not found in the central directory.
     */
    static byte[] jarWithEntryRemoved(File jar, String dropEntry) throws IOException {
        byte[] all = Files.readAllBytes(jar.toPath());
        return jarBytesWithEntryRemoved(all, dropEntry);
    }

    static byte[] jarBytesWithEntryRemoved(byte[] all, String dropEntry) throws IOException {
        EocdInfo eocd = findEocd(all);
        if (eocd == null) {
            throw new IOException("Input has no End-of-Central-Directory record");
        }

        // Walk the central directory and find the CDH for dropEntry. Capture its
        // LFH offset and the byte-range of the CDH itself, and compute the LFH size
        // (header + name + extra + data) so we know how many bytes to remove.
        int cdStart = eocd.cdOffset;
        int cdEnd = eocd.cdOffset + eocd.cdSize;
        int cur = cdStart;
        int dropCdhStart = -1, dropCdhEnd = -1;
        int dropLfhOffset = -1;
        int dropLfhAndDataLen = -1;

        int entriesSeen = 0;
        while (cur < cdEnd && entriesSeen < eocd.totalEntries) {
            if (readLE32(all, cur) != 0x02014b50L) {
                throw new IOException("Malformed central directory at offset " + cur);
            }
            int nameLen = readLE16(all, cur + 28);
            int extraLen = readLE16(all, cur + 30);
            int commentLen = readLE16(all, cur + 32);
            int compSize = readLE32i(all, cur + 20);
            int lfhOffset = readLE32i(all, cur + 42);

            String name = new String(all, cur + 46, nameLen, StandardCharsets.UTF_8);
            int cdhLen = 46 + nameLen + extraLen + commentLen;

            if (name.equals(dropEntry)) {
                dropCdhStart = cur;
                dropCdhEnd = cur + cdhLen;
                dropLfhOffset = lfhOffset;
                // LFH = 30 + name + extra + compSize.
                int lfhNameLen = readLE16(all, lfhOffset + 26);
                int lfhExtraLen = readLE16(all, lfhOffset + 28);
                dropLfhAndDataLen = 30 + lfhNameLen + lfhExtraLen + compSize;
                break;
            }

            cur += cdhLen;
            entriesSeen++;
        }

        if (dropCdhStart < 0) {
            return null;
        }

        // 1. Remove the LFH+data block. Other LFHs at higher offsets must have their
        //    offsets fixed up in their CDH entries.
        ByteArrayOutputStream out = new ByteArrayOutputStream(all.length - dropLfhAndDataLen);
        // 1a. Local-file-header section up to the dropped entry.
        out.write(all, 0, dropLfhOffset);
        // 1b. Local-file-header section after the dropped entry, up to start of CD.
        int afterDropStart = dropLfhOffset + dropLfhAndDataLen;
        out.write(all, afterDropStart, cdStart - afterDropStart);

        // 2. Rewrite central directory: copy CDH entries, skip the dropped one, fix
        //    up lfhOffset fields for entries whose original LFH was after the dropped LFH.
        int newCdStart = out.size();
        cur = cdStart;
        entriesSeen = 0;
        while (cur < cdEnd && entriesSeen < eocd.totalEntries) {
            int nameLen = readLE16(all, cur + 28);
            int extraLen = readLE16(all, cur + 30);
            int commentLen = readLE16(all, cur + 32);
            int cdhLen = 46 + nameLen + extraLen + commentLen;

            if (cur == dropCdhStart) {
                cur += cdhLen;
                entriesSeen++;
                continue;
            }

            // Copy CDH bytes, then patch lfhOffset at +42 if needed.
            byte[] cdh = new byte[cdhLen];
            System.arraycopy(all, cur, cdh, 0, cdhLen);
            int origLfhOffset = readLE32i(all, cur + 42);
            if (origLfhOffset > dropLfhOffset) {
                int adjusted = origLfhOffset - dropLfhAndDataLen;
                cdh[42] = (byte) (adjusted & 0xff);
                cdh[43] = (byte) ((adjusted >>> 8) & 0xff);
                cdh[44] = (byte) ((adjusted >>> 16) & 0xff);
                cdh[45] = (byte) ((adjusted >>> 24) & 0xff);
            }
            out.write(cdh);

            cur += cdhLen;
            entriesSeen++;
        }
        int newCdSize = out.size() - newCdStart;
        int newTotalEntries = eocd.totalEntries - 1;

        // 3. Rewritten EOCD.
        writeLE32(out, 0x06054b50);
        writeLE16(out, 0);
        writeLE16(out, 0);
        writeLE16(out, newTotalEntries);
        writeLE16(out, newTotalEntries);
        writeLE32(out, newCdSize);
        writeLE32(out, newCdStart);
        writeLE16(out, 0);

        return out.toByteArray();
    }

    /**
     * Appends a single {@code STORED} stamp entry at the end of the given canonical
     * JAR bytes by pure ZIP-level splicing: the canonical local-file-header section
     * and existing central-directory headers are preserved verbatim, and a new local
     * file header + central-directory header for the stamp entry are inserted between
     * the existing CD and the (rewritten) End-of-Central-Directory record.
     *
     * <p>This means {@code C_stamped} with the stamp entry's local-file-header,
     * file data, central-directory entry, and the rewritten EOCD removed is
     * byte-identical to {@code C}, so a consumer can verify the stamp by stripping
     * just the appended bytes and recomputing {@code SHA-256}.
     */
    static byte[] appendStamp(byte[] canonical, String entryName, String hashHex)
            throws IOException {
        EocdInfo eocd = findEocd(canonical);
        if (eocd == null) {
            throw new IOException("Canonical JAR has no End-of-Central-Directory record");
        }

        byte[] nameBytes = entryName.getBytes(StandardCharsets.UTF_8);
        byte[] stampBody = (hashHex.startsWith("SHA-256:")
                            ? hashHex
                            : ("SHA-256:" + hashHex + "\n")).getBytes(StandardCharsets.UTF_8);

        long crc = crc32(stampBody);
        // Fixed DOS time = 1980-01-01 00:00:00 (date=0x0021, time=0x0000).
        int dosTime = 0x0000;
        int dosDate = 0x0021;

        // Local file header for the stamp entry, placed immediately after the
        // canonical central directory's last local-file entry block (i.e., at
        // offset eocd.cdOffset).
        int newLfhOffset = eocd.cdOffset;
        ByteArrayOutputStream lfh = new ByteArrayOutputStream(30 + nameBytes.length + stampBody.length);
        writeLE32(lfh, 0x04034b50);        // local file header sig
        writeLE16(lfh, 20);                // version needed (2.0)
        writeLE16(lfh, 0);                 // gp bit flag
        writeLE16(lfh, 0);                 // compression = STORED
        writeLE16(lfh, dosTime);           // last mod time
        writeLE16(lfh, dosDate);           // last mod date
        writeLE32(lfh, crc);               // crc-32
        writeLE32(lfh, stampBody.length);  // compressed size
        writeLE32(lfh, stampBody.length);  // uncompressed size
        writeLE16(lfh, nameBytes.length);  // file name length
        writeLE16(lfh, 0);                 // extra length
        lfh.write(nameBytes);
        lfh.write(stampBody);

        // Central directory entry for the stamp.
        ByteArrayOutputStream cdh = new ByteArrayOutputStream(46 + nameBytes.length);
        writeLE32(cdh, 0x02014b50);        // CD entry sig
        writeLE16(cdh, 20);                // version made by
        writeLE16(cdh, 20);                // version needed
        writeLE16(cdh, 0);                 // gp bit flag
        writeLE16(cdh, 0);                 // compression
        writeLE16(cdh, dosTime);
        writeLE16(cdh, dosDate);
        writeLE32(cdh, crc);
        writeLE32(cdh, stampBody.length);  // comp size
        writeLE32(cdh, stampBody.length);  // uncomp size
        writeLE16(cdh, nameBytes.length);
        writeLE16(cdh, 0);                 // extra
        writeLE16(cdh, 0);                 // comment
        writeLE16(cdh, 0);                 // disk start
        writeLE16(cdh, 0);                 // internal attrs
        writeLE32(cdh, 0);                 // external attrs
        writeLE32(cdh, newLfhOffset);      // local header offset
        cdh.write(nameBytes);

        // Compose:
        //   canonical[0 .. cdOffset)               -- all existing local file headers + data
        //   new LFH + stamp body                   -- the appended stamp entry
        //   canonical[cdOffset .. eocdOffset)      -- existing central directory entries
        //   new CDH for the stamp                  -- appended CD entry
        //   rewritten EOCD                         -- with bumped counts and new cd offset/size
        int existingCdSize = eocd.cdSize;
        int newCdSize = existingCdSize + cdh.size();
        int newCdOffset = newLfhOffset + lfh.size();
        int newTotalEntries = eocd.totalEntries + 1;

        ByteArrayOutputStream bos = new ByteArrayOutputStream(canonical.length + lfh.size() + cdh.size() + 22);
        bos.write(canonical, 0, newLfhOffset);
        bos.write(lfh.toByteArray());
        bos.write(canonical, newLfhOffset, existingCdSize);
        bos.write(cdh.toByteArray());

        // Rewritten EOCD.
        writeLE32(bos, 0x06054b50);                    // EOCD sig
        writeLE16(bos, 0);                             // disk number
        writeLE16(bos, 0);                             // disk with CD
        writeLE16(bos, newTotalEntries);               // CD entries on this disk
        writeLE16(bos, newTotalEntries);               // total CD entries
        writeLE32(bos, newCdSize);                     // CD size
        writeLE32(bos, newCdOffset);                   // CD offset
        writeLE16(bos, 0);                             // comment length

        return bos.toByteArray();
    }

    /** Result of locating the EOCD record in a ZIP byte array. */
    private static final class EocdInfo {
        final int eocdOffset;
        final int cdOffset;
        final int cdSize;
        final int totalEntries;
        EocdInfo(int eocdOffset, int cdOffset, int cdSize, int totalEntries) {
            this.eocdOffset = eocdOffset;
            this.cdOffset = cdOffset;
            this.cdSize = cdSize;
            this.totalEntries = totalEntries;
        }
    }

    /**
     * Locates the End-of-Central-Directory record by scanning backwards from end-of-file
     * for signature 0x06054b50, allowing for an optional comment of up to 65535 bytes.
     */
    private static EocdInfo findEocd(byte[] bytes) {
        final int sig = 0x06054b50;
        int maxCommentLen = 0xffff;
        int start = Math.max(0, bytes.length - 22 - maxCommentLen);
        for (int i = bytes.length - 22; i >= start; i--) {
            if (readLE32(bytes, i) == sig) {
                int commentLen = readLE16(bytes, i + 20);
                if (i + 22 + commentLen == bytes.length) {
                    int totalEntries = readLE16(bytes, i + 10);
                    int cdSize = readLE32i(bytes, i + 12);
                    int cdOffset = readLE32i(bytes, i + 16);
                    if (cdOffset >= 0 && cdSize >= 0 && cdOffset + cdSize <= bytes.length) {
                        return new EocdInfo(i, cdOffset, cdSize, totalEntries);
                    }
                }
            }
        }
        return null;
    }

    private static long readLE32(byte[] b, int off) {
        return (b[off] & 0xffL)
             | ((b[off + 1] & 0xffL) << 8)
             | ((b[off + 2] & 0xffL) << 16)
             | ((b[off + 3] & 0xffL) << 24);
    }

    private static int readLE32i(byte[] b, int off) {
        long v = readLE32(b, off);
        return (v > Integer.MAX_VALUE) ? -1 : (int) v;
    }

    private static int readLE16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static void writeLE16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >>> 8) & 0xff);
    }

    private static void writeLE32(ByteArrayOutputStream o, long v) {
        o.write((int) (v & 0xff));
        o.write((int) ((v >>> 8) & 0xff));
        o.write((int) ((v >>> 16) & 0xff));
        o.write((int) ((v >>> 24) & 0xff));
    }

    private static long crc32(byte[] data) {
        CRC32 c = new CRC32();
        c.update(data, 0, data.length);
        return c.getValue();
    }

    private static String sha256Hex(byte[] data) throws NoSuchAlgorithmException {
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

    private static void atomicWrite(File target, byte[] data) throws IOException {
        Path targetPath = target.toPath();
        Path parent = targetPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = (parent != null)
                   ? Files.createTempFile(parent, target.getName() + ".", ".tmp")
                   : Files.createTempFile(target.getName() + ".", ".tmp");
        try {
            Files.write(tmp, data);
            try {
                Files.move(tmp, targetPath,
                           StandardCopyOption.REPLACE_EXISTING,
                           StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* best effort */ }
            throw e;
        }
    }

    // ---- test-visible setters (package-private) ---------------------------------

    void setInputFile(File f)             { this.inputFile = f; }
    void setOutputFile(File f)            { this.outputFile = f; }
    void setAttachStamp(boolean v)        { this.attachStamp = v; }
    void setStampEntryName(String n)      { this.stampEntryName = n; }
    void setFailOnAlreadyStamped(boolean v){ this.failOnAlreadyStamped = v; }
    void setSkip(boolean v)               { this.skip = v; }
}
