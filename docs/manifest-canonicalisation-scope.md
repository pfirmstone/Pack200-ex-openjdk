# Scope — Manifest canonicalisation for reproducible normalised JARs

**Status:** Proposed
**Target:** `Pack200-ex-openjdk` (`net.pack200.Normalize`) + `pack200-normalize-maven-plugin`
**Proposed release:** `1.27.2`
**Author:** (scoped for JGDMS-STD-002 SCAP / STD-007 `javaCodeDigest` reproducibility)

---

## Problem

`Normalize.normalize()` canonicalises class-file bytes and the ZIP container, but
**passes `META-INF/MANIFEST.MF` through verbatim** (see `Normalize.java` "What
normalisation does NOT do"). The manifest is therefore the dominant remaining
source of non-determinism for content-addressed identity:

1. **Volatile tool-identity headers.** `Created-By`, `Build-Jdk`, `Build-Jdk-Spec`,
   `Built-By`, `Bnd-LastModified`, `Build-Time`/`Build-Timestamp`, `Ant-Version`,
   etc. differ per build host / JDK / wall-clock, so two builds of identical
   sources produce different `SHA-256(C)` — defeating reproducible builds, the
   `META-INF/CONTENT-HASH` stamp, and the SCAP/STD-007 shared `javaCodeDigest`.
2. **Non-deterministic attribute ordering.** `java.util.jar.Attributes` is backed
   by a `HashMap`, so `Manifest.write()` does **not** emit a stable header order.
   Even with zero volatile headers, ordering alone can change the bytes.

The empirical SCAP finding that motivated this: a Pack200 round-trip changed a
manifest by +3 bytes and that delta propagated into the content hash. Stripping
volatile headers and fixing order removes the class of "same source → different
hash" failures.

## Goal

Produce a **byte-stable, idempotent** manifest as part of (or immediately before)
normalisation, so that `SHA-256(C)` depends only on semantically meaningful
content. Must hold:

- `canon(canon(m)) == canon(m)` (idempotent — composes with `Normalize`'s
  fixed-point guarantee).
- Stable across JDK version, OS, locale, timezone, and filesystem ordering.

## Design

**Where.** Add the capability to `net.pack200.Normalize` behind an option so it is
reusable (CLI, library, plugin), and surface it through the mojo:

- `Normalize.Options.canonicaliseManifest(boolean)` — default **true** in
  `reproducible()`, **false** in `legacyJarN()`.
- `Normalize.Options.manifestDenylist(Set<String>)` — headers to drop (case-
  insensitive, exact name match); replaces the default set.
- `Normalize.Options.manifestDenylistAdd(String...)` / `...Remove(String...)` —
  tweak the default without replacing it.
- Mojo parameters mirror these: `canonicaliseManifest` (default true),
  `manifestDenylist`, `manifestDenylistAdd`, `manifestDenylistRemove`.

**Order of operations.** Manifest canonicalisation runs on the main-attributes and
per-entry attributes **after** the Pack200 round-trip and **before** the ZIP
canonicalisation/serialisation step, so the canonical manifest participates in the
final byte layout. The `META-INF/CONTENT-HASH` stamp is computed on the result
(after manifest canonicalisation), as today.

**Default denylist (drop these main attributes).** Curated to provably
build-environment/tool-identity headers only:

```
Created-By, Build-Jdk, Build-Jdk-Spec, Built-By, Build-Time, Build-Date,
Build-Timestamp, Bnd-LastModified, Tool, Hostname, Ant-Version, Maven-Version,
Originally-Created-By, Implementation-Build-Date
```

**Explicitly preserved (never auto-dropped).** Semantically significant headers:

```
Manifest-Version, Signature-Version, Main-Class, Class-Path, Multi-Release,
Automatic-Module-Name, Sealed, Name (per-entry),
Implementation-Title/Version/Vendor, Specification-Title/Version/Vendor,
all OSGi headers (Bundle-*, Import-Package, Export-Package, Require-Capability,
Provide-Capability, Private-Package, etc.)
```

> Open question for the maintainer: `Implementation-Version`/`-Vendor` sometimes
> embed build metadata (e.g. a git describe with a dirty/timestamp suffix). Default
> is **keep**; deployments that want them dropped add them via `manifestDenylistAdd`.

**Canonical serialisation (the determinism core).** Do not rely on
`Manifest.write()`. Emit manually:

1. Parse with `java.util.jar.Manifest` (handles continuation lines / 72-col
   unwrapping for us).
2. Main section: emit `Manifest-Version` first (JAR spec requires it first), then
   all surviving main attributes sorted by header name with a fixed
   locale-independent comparator (compare raw UTF-16 by code unit, or ASCII-lower
   then code point — pick one and pin it).
3. Per-entry sections: sort sections by entry `Name`; within each, `Name` first
   then remaining attributes sorted by the same comparator.
4. Fixed encoding/format: UTF-8 values, `\r\n` line endings, 72-byte line wrapping
   exactly per the JAR File Specification, one blank line between sections, single
   trailing blank line.

## Edge cases

- No manifest → no-op.
- Manifest with only `Manifest-Version` → unchanged (already canonical).
- Long header values → correct 72-col wrap; round-trip via the canonical writer
  must be a fixed point.
- Multi-release JARs (`Multi-Release: true`, `META-INF/versions/**`) → manifest
  canonicalised; versioned class entries handled by existing normalisation.
- **Signed JARs** → out of scope. `Normalize` already documents that it does not
  re-sign and that round-tripping invalidates signatures; manifest per-entry
  digests (`*-Digest`) must not be touched. Treat presence of `META-INF/*.SF` as
  "do not canonicalise manifest; warn" (or rely on the caller not normalising
  signed jars, consistent with current behaviour).
- Malformed manifest (unparseable) → fail with a clear error in the mojo; in the
  library, throw `IOException` (do not silently pass through, since that would
  reintroduce non-determinism).

## Interaction with existing features

- `META-INF/CONTENT-HASH` stamp: unchanged mechanism; it now records
  `SHA-256(C)` where `C` includes the canonical manifest. The stamp entry remains
  excluded from the hash it records.
- The mojo's idempotence guarantee extends: re-running on an already-canonicalised
  JAR is byte-equal.
- JGDMS: bump `pack200.version` to `1.27.2`; no JGDMS code change required (Host 4
  and clients already hash whatever `C` the producer ships).

## Testing

1. **Volatile-header independence:** two JARs identical except `Created-By` /
   `Build-Jdk` / `Bnd-LastModified` → byte-identical after normalisation.
2. **Order independence:** same headers inserted in different orders → identical.
3. **Idempotence:** `normalize(normalize(j))` byte-equal; manifest is a fixed point.
4. **Keep-list preserved:** `Main-Class`, `Multi-Release`, `Automatic-Module-Name`,
   OSGi `Bundle-*`/`Import-Package`/`Export-Package` survive verbatim (modulo
   re-wrapping/ordering).
5. **Configurable denylist:** add/remove entries changes which headers drop.
6. **Long-line wrapping:** a >72-byte value round-trips and is a fixed point.
7. **Multi-release + per-entry sections:** sections sorted, attributes sorted.
8. **Signed jar:** manifest left intact (or refused) — no `*-Digest` mutation.
9. **Cross-environment:** run under two locales/timezones (e.g. `tr-TR`,
   `Asia/Kolkata`) → identical output (guards against locale-sensitive casing in
   the comparator).

## Risks / mitigations

- *Dropping a header a consumer relies on at runtime* → conservative default
  denylist (tool-identity only); everything else opt-in via `manifestDenylistAdd`.
- *Comparator locale sensitivity* → use a locale-independent comparator; covered
  by test (9).
- *Spec-compliance of the hand-written writer* → reuse `java.util.jar.Manifest`
  for parsing; only the serialisation is custom and is fixed-point tested.

## Deliverables

- `Normalize` option + manifest canonicaliser (library).
- Mojo parameters + wiring.
- Unit tests (1–9 above).
- Doc updates: `Normalize` javadoc "What normalisation does NOT do" amended to
  state the manifest IS canonicalised under `reproducible()`; plugin README.
- Release `1.27.2`; JGDMS `pack200.version` bump.
