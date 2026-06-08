# Pack200 JAR Normalisation

`net.pack200.Normalize` canonicalises a JAR file so that semantically-equivalent
inputs produce byte-identical outputs. It is the modern, library-form replacement
for the `jar --normalize` / `jar -n` option that OpenJDK removed in
[JDK-8234542](https://bugs.openjdk.org/browse/JDK-8234542) (JEP 367, Java 15)
when Pack200 was deleted.

> **TL;DR**
> ```java
> import net.pack200.Normalize;
>
> Normalize.normalize(new File("in.jar"), new File("out.jar"));
> ```
> `out.jar` is a Pack200 fixed point and, with the default
> `Options.reproducible()`, is byte-identical for any two builds whose
> classfiles and resources are identical.

---

## 1. History

The original OpenJDK `jar -n` flag was implemented inside
`sun.tools.jar.Main` as a Pack200 packer→unpacker round-trip with
`EFFORT=1`:

```java
File packFile = createTemporaryFile(tmpbase, ".pack");
java.util.jar.Pack200.Packer packer = java.util.jar.Pack200.newPacker();
Map<String, String> p = packer.properties();
p.put(java.util.jar.Pack200.Packer.EFFORT, "1");
try (JarFile jarFile = new JarFile(tmpFile.getCanonicalPath());
     OutputStream pack = new FileOutputStream(packFile)) {
    packer.pack(jarFile, pack);
}
tmpFile = createTemporaryFile(tmpbase, ".jar");
try (OutputStream out = new FileOutputStream(tmpFile);
     JarOutputStream jos = new JarOutputStream(out)) {
    java.util.jar.Pack200.Unpacker unpacker = java.util.jar.Pack200.newUnpacker();
    unpacker.unpack(packFile, jos);
}
```

That block (and the Pack200 API behind it) was removed in JDK 15 by commit
[`9ac2f8b6543`](https://github.com/openjdk/jdk/commit/9ac2f8b6543), leaving JDK
users with no in-platform way to normalise a JAR. The leftover flag declarations
were finally cleaned up in JDK 24 by commit
[`51662c23843`](https://github.com/openjdk/jdk/commit/51662c23843)
([JDK-8346232](https://bugs.openjdk.org/browse/JDK-8346232)).

This library re-introduces the routine as a public, documented API, and extends
it with the ZIP-level normalisation that reproducible-builds tooling needs.

---

## 2. Why normalise?

Two distinct reasons:

### 2.1 Pack200 fixed-point (the original use case)

Packing a JAR with Pack200 is **not idempotent on raw input bytes**. The packer
rewrites class-file constant pools into a canonical band layout, reorders
attributes, and drops redundant default values; the unpacker reconstructs a
fresh classfile from those bands. The classfile you started with and the
classfile you get back are *semantically* identical but their bytes differ.

This breaks **sign-then-pack** workflows:

- Sign `original.jar` → `original.signed.jar`
- Distribute as `pack200(original.signed.jar)` → `distributed.pack.gz`
- Recipient runs `unpack200` → `received.jar`
- `received.jar`'s classfiles differ from `original.signed.jar`'s classfiles
- Therefore `received.jar`'s signatures don't verify

The fix is to **normalise first, then sign**:

```
original.jar
   └─► Normalize.normalize(...) ──► canonical.jar    (← Pack200 fixed point)
                                       └─► jarsigner ──► signed.jar
                                                            └─► pack200 ──► dist.pack.gz
                                                                              └─► unpack200 ──► identical-to-signed.jar
```

The Pack200 round-trip drives the JAR to its fixed point: a JAR whose classfiles
are already in the form the unpacker would produce. After normalisation,
packing-then-unpacking is a no-op, signatures survive the trip, and Pack200
becomes the byte-stable distribution format it was designed to be.

### 2.2 Reproducible builds & content addressing

For artefact-hash trust models — SCAP, Sigstore, Nix derivations,
[reproducible-builds.org](https://reproducible-builds.org/) — the same source
must produce the same bytes. Stock JARs fail this trivially:

| Source of variance              | How `Normalize` removes it                     |
|---------------------------------|-----------------------------------------------|
| Per-entry mtimes                | Pinned to a fixed instant (default 1980-01-01) |
| Entry order (filesystem-dep.)   | Sorted lexicographically, manifest first       |
| ZIP extra fields (Unix perms…)  | Stripped                                       |
| ZIP file comment                | Cleared                                        |
| Empty directory entries         | Dropped                                        |
| STORED-vs-DEFLATED for tiny entries | Uniformly DEFLATED                         |
| Classfile attribute ordering    | Canonicalised by the Pack200 round-trip        |
| Constant-pool layout            | Canonicalised by the Pack200 round-trip        |

The output of `Normalize.normalize(..., Options.reproducible())` is suitable
for SHA-256 content addressing.

---

## 3. API

The whole API is three static methods on `net.pack200.Normalize`:

```java
public final class Normalize {
    public static void normalize(File in,        File out)                          throws IOException;
    public static void normalize(File in,        File out,        Options opts)     throws IOException;
    public static void normalize(JarFile in,     OutputStream out)                  throws IOException;
    public static void normalize(JarFile in,     OutputStream out, Options opts)    throws IOException;
    public static void normalize(InputStream in, OutputStream out, Options opts)    throws IOException;
}
```

…plus a fluent `Options` builder with two factory methods:

```java
Normalize.Options.reproducible()   // full canonicalisation (default)
Normalize.Options.legacyJarN()     // bit-for-bit equivalent of OpenJDK's jar -n
```

### 3.1 Options

| Setter                       | Default     | Effect                                                                  |
|------------------------------|-------------|-------------------------------------------------------------------------|
| `.effort(int)`               | `1`         | Pack200 effort 0..9. `1` matches the historical `jar -n` setting.        |
| `.segmentLimit(int)`         | `-1`        | `-1` = single segment, never auto-split. Strongly recommended.          |
| `.dropDirectories(boolean)`  | `true`      | Drop entries whose name ends in `/`.                                    |
| `.fixTimestamps(boolean)`    | `true`      | Pin every entry mtime to `fixedTime`.                                   |
| `.fixedTime(long)`           | 1980-01-01Z | Epoch millis to pin to. Must be ≥ 1980-01-01 UTC (ZIP epoch).            |
| `.sortEntries(boolean)`      | `true`      | Lexicographic sort, manifest forced first.                              |
| `.unifyDeflateHint(boolean)` | `true`      | Force every entry to DEFLATED.                                          |
| `.clearZipComment(boolean)`  | `true`      | Clear the ZIP file comment (otherwise contains the `"PACK200"` marker). |
| `.stripExtra(boolean)`       | `true`      | Strip per-entry ZIP extra fields (but see note below).                  |

> **Note on `stripExtra`.** When `fixTimestamps` is true on JDK 9+,
> `ZipOutputStream` unconditionally writes a 9-byte Info-ZIP Extended Timestamp
> extra-field block (`0x5455` "UT") that carries the modification time as a
> Unix epoch value. This is the platform's behaviour, not something Normalize
> can override without reflection into `java.util.zip`. The block is fully
> deterministic for a given `fixedTime`, so reproducibility is preserved; the
> only effect is that `JarEntry.getExtra()` returns a 9-byte buffer rather than
> `null`. If you need absolutely empty extra fields, set
> `fixTimestamps(false)` (and accept that the DOS time field still encodes
> 1980-01-01).

`legacyJarN()` returns Options with every Step-2 toggle disabled
(`dropDirectories=false`, `fixTimestamps=false`, `sortEntries=false`,
`unifyDeflateHint=false`, `clearZipComment=false`, `stripExtra=false`); only the
Pack200 round-trip remains.

### 3.2 Threading

`Normalize` is stateless and its static methods are thread-safe. Each call
allocates its own `Pack200.Packer` and `Pack200.Unpacker`; they are not shared
across threads even within a single call.

### 3.3 Stream ownership

The `File` overloads handle their own files. The `OutputStream` overloads
**do not close** the caller's destination stream — they only flush it. This
matches the contract of `Pack200.Packer.pack`. The `InputStream` overload
**does close** its input stream after spooling it to a temporary file, again
matching the Pack200 packer contract.

### 3.4 Temporary files

Each call creates one (`File`/`JarFile`/`OutputStream` paths) or two
(`InputStream` path) temporary files via `Files.createTempFile`. They are
deleted in a `finally` block even if normalisation throws. If you need to
control the temp-file location, set the `java.io.tmpdir` system property as
normal.

---

## 4. Pipeline

```
                ┌─────────────────────────────────────────────┐
                │             input JAR (in memory)           │
                └─────────────────────┬───────────────────────┘
                                      │
                              Step 1: Pack200 round-trip
                              EFFORT=opts.effort
                              SEGMENT_LIMIT=-1
                              KEEP_FILE_ORDER per opts.sortEntries
                              MODIFICATION_TIME=LATEST per opts.fixTimestamps
                              DEFLATE_HINT=TRUE  per opts.unifyDeflateHint
                                      │
                ┌─────────────────────▼───────────────────────┐
                │   intermediate JAR (Pack200 fixed point)    │
                └─────────────────────┬───────────────────────┘
                                      │
                              Step 2: ZIP canonicalisation
                              (skipped for legacyJarN)
                              ─ drop directory entries
                              ─ sort entries (manifest first)
                              ─ pin per-entry mtimes
                              ─ strip ZIP extra fields
                              ─ clear ZIP file comment
                              ─ unify deflate method
                                      │
                ┌─────────────────────▼───────────────────────┐
                │              output JAR                     │
                └─────────────────────────────────────────────┘
```

---

## 5. Guarantees

After `Normalize.normalize(in, out, Options.reproducible())`:

1. **Pack200 fixed point.**
   `unpack(pack(out)) == out`, byte-for-byte, for any conforming Pack200
   implementation using the same packer settings.

2. **Idempotence.**
   `normalize(normalize(x)) == normalize(x)`, byte-for-byte.

3. **Order independence.**
   Two input JARs with the same set of `(entry-name, entry-bytes)` pairs but
   different entry orders, mtimes, or directory entries produce the same
   output bytes.

4. **Host independence.**
   The output does not depend on filesystem case-sensitivity, file-attribute
   layer, OS line-ending convention, or the locale/timezone of the host.

5. **JDK independence within a major version family.**
   The output depends on the JDK's `ZipOutputStream` deflate implementation,
   which is stable across patch releases. Cross-major-version reproducibility
   (e.g. JDK 17 vs JDK 21) is not guaranteed by this library; pin your JDK
   version if you need a hash invariant across decades.

### What's NOT guaranteed

- **Manifest content** is passed through verbatim. Lines like
  `Created-By:` and `Build-Jdk:` are typical sources of nondeterminism;
  remove them from the source manifest before normalising.
- **Signature blocks** are also passed through verbatim, but the underlying
  classfile bytes change during the round-trip. *Sign after normalising, not
  before.*
- **Multi-Release JAR layering** is preserved (entries under
  `META-INF/versions/N/` are sorted alphabetically by full path), but the
  individual versioned classfiles are subject to the same Pack200 round-trip
  as the base layer.

---

## 6. Usage examples

### 6.1 Reproducible build artefact

```java
import net.pack200.Normalize;
import java.io.File;

public final class BuildJar {
    public static void main(String[] args) throws Exception {
        File raw  = new File(args[0]);
        File out  = new File(args[1]);
        Normalize.normalize(raw, out);   // Options.reproducible() implied
    }
}
```

Anchored to `SOURCE_DATE_EPOCH`:

```java
long sde = Long.parseLong(System.getenv().getOrDefault("SOURCE_DATE_EPOCH", "315532800"));
Normalize.normalize(raw, out,
    Normalize.Options.reproducible().fixedTime(sde * 1000L));
```

### 6.2 Sign-then-pack workflow (the original use case)

```java
// Step A: normalise to a Pack200 fixed point.
Normalize.normalize(jarFile, normFile, Normalize.Options.legacyJarN());

// Step B: sign the fixed-point JAR.
runJarsigner(normFile, keystore, alias);

// Step C: pack for distribution.
try (JarFile jf = new JarFile(normFile);
     OutputStream pack = new GZIPOutputStream(Files.newOutputStream(packFile))) {
    Pack200.Packer packer = Pack200.newPacker();
    packer.properties().put(Pack200.Packer.EFFORT, "9");
    packer.pack(jf, pack);
}

// On the recipient side, unpack200(packFile) yields a JAR whose signature
// verifies because the classfiles match those that were signed in Step B.
```

### 6.3 Pipe input

```java
try (InputStream is = url.openStream();
     OutputStream os = Files.newOutputStream(destination)) {
    Normalize.normalize(is, os, Normalize.Options.reproducible());
}
```

---

## 7. Limitations

- **Performance.** Two temp files and a Pack200 round-trip are not cheap for
  large JARs. Expect 5–20× the wall-clock time of a plain `cp`. Use
  parallelism at the build-system layer if you have many JARs.
- **Memory.** Each entry is loaded into a `ByteArrayOutputStream` during Step 2.
  Very large single entries (gigabyte resource blobs) will allocate a single
  large buffer.
- **No partial output.** A failed normalisation deletes its temp files but
  leaves the destination stream in whatever state it had reached when the
  exception was thrown. Callers writing to a final destination should write
  to a sibling temp file and rename on success.
- **Resource-only JARs work**, but the Pack200 round-trip is then a relatively
  expensive way to canonicalise a ZIP. If you have no classfiles, a plain ZIP
  canonicaliser (e.g. the strip-nondeterminism family) is faster.

---

## 8. See also

- [JSR 200: Network Transfer Format Specification](https://docs.oracle.com/en/java/javase/13/docs/specs/pack-spec.html)
- [JEP 367: Remove the Pack200 Tools and API](https://openjdk.org/jeps/367) — the removal that motivated this library
- [`jar -n` documentation in JDK 8](https://docs.oracle.com/javase/8/docs/technotes/tools/unix/jar.html) — the original normalize flag
- [reproducible-builds.org SOURCE_DATE_EPOCH spec](https://reproducible-builds.org/specs/source-date-epoch/)
- [pack200-spec.md](pack200-spec.md) — this fork's Pack200 archive format extensions for Java 9–27
