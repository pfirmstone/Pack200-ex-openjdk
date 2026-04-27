# Pack200-ex-openjdk: Revised Specification

## 1. Overview

Pack200-ex-openjdk is an extension of the JSR-200 Pack200 format originally defined
for Java 5–8. It adds **native band-compression** support for class-file attributes
introduced in Java 9 through Java 27, and defines new archive-format version-negotiation
rules for those class versions.

The format remains backward-compatible with the original JSR-200 specification (archive
magic `0xCAFED00D`). Implementations that do not recognise the new package versions
(≥ 180.0) will treat archives produced with them as unknown, which is the intended
behaviour.

The implementation JAR is a **Multi-Release JAR** (MRJAR) as defined by JEP 238. The
base class files target Java 8. `META-INF/versions/9/` adds a JPMS module descriptor.
`META-INF/versions/17/` carries Java-17-compiled class files; `AccessController.doPrivileged()`
is retained in those files and will be removed only when a future JEP formally
removes the API.

---

## 2. Archive Format Versions

A Pack200 archive begins with a 4-byte magic (`CA FE D0 0D`) followed by one
UNSIGNED5-encoded value for the minor version and one for the major version of the
archive format (the "package version"). Both values are small enough (≤ 220) to fit in a
single UNSIGNED5 byte.

| Package Version | Symbolic Name           | Class Versions Covered                                     | New Capability Added                                                |
|-----------------|-------------------------|------------------------------------------------------------|---------------------------------------------------------------------|
| 150.7           | JAVA5_PACKAGE_VERSION   | ≤ 49 (Java 1–5)                                           | Baseline JSR-200                                                    |
| 160.1           | JAVA6_PACKAGE_VERSION   | 50 (Java 6), and 51 without InvokeDynamic                 | Adds StackMapTable                                                  |
| 170.1           | JAVA7_PACKAGE_VERSION   | 51 (Java 7) with InvokeDynamic                            | Adds invokedynamic, CONSTANT_MethodHandle, CONSTANT_MethodType      |
| 171.0           | JAVA8_PACKAGE_VERSION   | 52 (Java 8), no Module/Dynamic CP entries                 | Adds MethodParameters, type annotations                             |
| 180.0           | JAVA9_PACKAGE_VERSION   | 53–54 (Java 9–10), or archives with Module/Package CP     | Adds CONSTANT_Dynamic, CONSTANT_Module, CONSTANT_Package; Module/ModulePackages/ModuleMainClass/NestHost/NestMembers |
| 190.0           | JAVA11_PACKAGE_VERSION  | 55–54 (Java 11–16)                                        | (same capability set; upper bound raised)                           |
| 190.1           | JAVA17_PACKAGE_VERSION  | 55–61 (Java 11–17)                                        | Adds Record, PermittedSubclasses                                    |
| 200.0           | JAVA18_PACKAGE_VERSION  | 62–65 (Java 18–21)                                        | Upper bound raised to cover Java 18–21 class files                  |
| 210.0           | JAVA22_PACKAGE_VERSION  | 66–69 (Java 22–25)                                        | Upper bound raised to cover Java 22–25 class files                  |
| 220.0           | JAVA26_PACKAGE_VERSION  | 70–71 (Java 26–27)                                        | Upper bound raised to cover Java 26–27 class files                  |

### 2.1 Version Selection Rule (Packer)

The packer selects the **lowest** package version sufficient to encode all content:

1. No class files in the archive → **150.7**
2. Highest class version ≤ 49.0 → **150.7**
3. Highest class version = 50.0, or (= 51.0 and no InvokeDynamic) → **160.1**
4. Highest class version = 51.0 with InvokeDynamic → **170.1**
5. Highest class version ≤ 52.0 and no Module/Dynamic CP entries → **171.0**
6. Highest class version ≤ 54.0, **or** any CONSTANT_Module/CONSTANT_Package/CONSTANT_Dynamic CP entry present → **180.0**
7. Highest class version ≤ 61.0 (Java 11–17) → **190.1**
8. Highest class version ≤ 65.0 (Java 18–21) → **200.0**
9. Highest class version ≤ 69.0 (Java 22–25) → **210.0**
10. Highest class version ≤ 71.0 (Java 26–27) → **220.0**

### 2.2 Version Acceptance Rule (Unpacker)

An unpacker MUST accept any archive whose package version is in the supported set:
`{150.7, 160.1, 170.1, 171.0, 180.0, 190.0, 190.1, 200.0, 210.0, 220.0}`.

For an archive with an **unknown** package version (greater than `220.0`), the unpacker
SHOULD emit a forward-compatibility warning and attempt decoding up to the highest
class-file version it understands, rather than hard-failing. New structural features in
such archives (new CP tags, new header option bits, etc.) may not decode correctly.

---

## 3. Archive Option Bits

The `archive_options` field is a single UNSIGNED5-encoded integer in
`archive_header_0`, immediately after the minor and major version bytes. Bit definitions:

| Bit | Constant                  | Meaning                                                                         |
|-----|---------------------------|---------------------------------------------------------------------------------|
|  0  | AO_HAVE_SPECIAL_FORMATS   | Band-header count and layout-definition count are present                       |
|  1  | AO_HAVE_CP_NUMBERS        | CP numeric counts (int, float, long, double) are present                        |
|  2  | AO_HAVE_ALL_CODE_FLAGS    | All code attributes emit a flags word, even when there are no attrs             |
|  3  | AO_HAVE_CP_EXTRAS         | CP extra counts (MethodHandle, MethodType, InvokeDynamic, BSM) are present      |
|  4  | AO_HAVE_FILE_HEADERS      | File header counts (sizes, next, modtime, files) are present                    |
|  5  | AO_DEFLATE_HINT           | Archive-wide deflation hint                                                     |
|  6  | AO_HAVE_FILE_MODTIME      | Per-file modification times are present                                         |
|  7  | AO_HAVE_FILE_OPTIONS      | Per-file options are present                                                    |
|  8  | AO_HAVE_FILE_SIZE_HI      | Resources with uncompressed size ≥ 2³² bytes exist (64-bit file-length bands)  |
|  9  | AO_HAVE_CLASS_FLAGS_HI    | Classes emit a 32-bit (hi+lo) flags word; class attribute indices 32–33 (`Record`, `PermittedSubclasses`) are in use |
| 10  | AO_HAVE_FIELD_FLAGS_HI    | Fields emit a 32-bit flags word                                                 |
| 11  | AO_HAVE_METHOD_FLAGS_HI   | Methods emit a 32-bit flags word                                                |
| 12  | AO_HAVE_CODE_FLAGS_HI     | Code attributes emit a 32-bit flags word                                        |
| 13  | AO_HAVE_CP_MODULE_DYNAMIC | CONSTANT_Dynamic, CONSTANT_Module, and/or CONSTANT_Package entries are present  |
| 14–31 | AO_UNUSED_MBZ           | **MUST be zero** (reserved for future use)                                      |

---

## 4. UNSIGNED5 Coding

UNSIGNED5 is the variable-length integer coding used for `archive_header_0` values
and most integer bands. Its parameters are B=5, H=64, S=0, giving L=192.

**Decoding algorithm** (from `Coding.readInt`):

```
L = 192   (256 - H = 256 - 64)
H = 64
sum = 0
H_i = 1
for i in 0..B-1:
    b_i = next_byte & 0xFF
    sum += b_i * H_i
    H_i *= H
    if b_i < L: break        // terminal byte
return sum
```

This means:
- A single byte `b` in `[0, 191]` encodes the value `b` itself.
- Values ≥ 192 require a multi-byte sequence: the first byte ≥ 192 is not terminal.

**Examples** (relevant to archive header):

| Value | Encoding |
|-------|----------|
| 7     | `[07]` |
| 150   | `[96]` |
| 160   | `[A0]` |
| 170   | `[AA]` |
| 171   | `[AB]` |
| 180   | `[B4]` |
| 190   | `[BE]` |
| 200   | `[C8]` |
| 210   | `[D2]` |
| 220   | `[DC]` |
| 512   | `[C0, 05]` (= 192 + 0, then 5; since 192 + 5×64 = 512) |
| 8192  | `[C0, 7D]` (= 192 + 0, then 125; since 192 + 125×64 = 8192) |

---

## 5. Archive Header Structure

```
archive_magic          : 4 bytes  = CA FE D0 0D
archive_minor_version  : UNSIGNED5 (always 1 byte)
archive_major_version  : UNSIGNED5 (always 1 byte)
archive_options        : UNSIGNED5 (1–3 bytes; bits defined in §3)
```

If `AO_HAVE_FILE_HEADERS` (bit 4) is set:
```
archive_size_hi        : UNSIGNED5
archive_size_lo        : UNSIGNED5
archive_next_count     : UNSIGNED5
archive_modtime        : UNSIGNED5
archive_file_count     : UNSIGNED5
```

If `AO_HAVE_SPECIAL_FORMATS` (bit 0) is set:
```
band_headers_size      : UNSIGNED5
attr_definition_count  : UNSIGNED5
```

If `AO_HAVE_CP_NUMBERS` (bit 1) is set:
```
cp_Int_count           : UNSIGNED5
cp_Float_count         : UNSIGNED5
cp_Long_count          : UNSIGNED5
cp_Double_count        : UNSIGNED5
```

If `AO_HAVE_CP_EXTRAS` (bit 3) is set:
```
cp_MethodHandle_count  : UNSIGNED5
cp_MethodType_count    : UNSIGNED5
cp_InvokeDynamic_count : UNSIGNED5
cp_BootstrapMethod_count : UNSIGNED5
```

If `AO_HAVE_CP_MODULE_DYNAMIC` (bit 13) is set:
```
cp_Dynamic_count       : UNSIGNED5
cp_Module_count        : UNSIGNED5
cp_Package_count       : UNSIGNED5
```

---

## 6. Constant Pool

Entry types map directly to JVM class-file constant-pool tags:

| Tag | Constant Name                | Pack200 Band(s)                                       | Version Required |
|-----|------------------------------|-------------------------------------------------------|-----------------|
|  1  | CONSTANT_Utf8                | cp_Utf8_prefix / cp_Utf8_suffix / cp_Utf8_chars       | 150.7           |
|  3  | CONSTANT_Integer             | cp_Int                                                | 150.7           |
|  4  | CONSTANT_Float               | cp_Float                                              | 160.1           |
|  5  | CONSTANT_Long                | cp_Long_hi / cp_Long_lo                               | 150.7           |
|  6  | CONSTANT_Double              | cp_Double_hi / cp_Double_lo                           | 160.1           |
|  7  | CONSTANT_Class               | cp_Class                                              | 150.7           |
|  8  | CONSTANT_String              | cp_String                                             | 150.7           |
|  9  | CONSTANT_Fieldref            | cp_Field_class / cp_Field_desc                        | 150.7           |
| 10  | CONSTANT_Methodref           | cp_Method_class / cp_Method_desc                      | 150.7           |
| 11  | CONSTANT_InterfaceMethodref  | cp_Imethod_class / cp_Imethod_desc                    | 150.7           |
| 12  | CONSTANT_NameAndType         | cp_Descr_name / cp_Descr_type                         | 150.7           |
| 15  | CONSTANT_MethodHandle        | cp_MethodHandle_refkind / cp_MethodHandle_member      | 170.1           |
| 16  | CONSTANT_MethodType          | cp_MethodType                                         | 170.1           |
| 17  | CONSTANT_Dynamic             | cp_Dynamic_spec / cp_Dynamic_desc (AO_HAVE_CP_MODULE_DYNAMIC) | 180.0   |
| 18  | CONSTANT_InvokeDynamic       | cp_InvokeDynamic_spec / cp_InvokeDynamic_desc         | 170.1           |
| 19  | CONSTANT_Module              | cp_Module (AO_HAVE_CP_MODULE_DYNAMIC)                 | 180.0           |
| 20  | CONSTANT_Package             | cp_Package (AO_HAVE_CP_MODULE_DYNAMIC)                | 180.0           |
| 22  | CONSTANT_BootstrapMethod (pseudo) | cp_BootstrapMethod_ref / cp_BootstrapMethod_arg_count / cp_BootstrapMethod_arg | 170.1 |

Tags 17, 19, 20 and their associated bands are present **only when** bit 13
(`AO_HAVE_CP_MODULE_DYNAMIC`) is set in `archive_options`.

---

## 7. Predefined Attribute Index Table

Attributes are encoded by bit-position in per-element flags words. Indices 0–16 are the
low-order "standard" bits; indices 17 and above are the extension bits described here.

### 7.1 Class-context Attributes

| Index | Attribute Name                        | Layout String       | Package Version Required | Notes                              |
|-------|---------------------------------------|---------------------|--------------------------|------------------------------------|
| 0–16  | (standard JVM flags)                  | —                   | 150.7                    |                                    |
| 17    | SourceFile                            | `RUNH`              | 150.7                    | nullable Utf8                      |
| 18    | EnclosingMethod                       | `RCHRDNH`           | 150.7                    | nullable NameAndType               |
| 19    | Signature                             | `RSH`               | 150.7                    |                                    |
| 20    | Deprecated                            | `""`                | 150.7                    | zero-length                        |
| 21    | RuntimeVisibleAnnotations             | (annotation layout) | 160.1                    |                                    |
| 22    | RuntimeInvisibleAnnotations           | (annotation layout) | 160.1                    |                                    |
| 23    | InnerClasses                          | `NH[RCHRCNHRUNHFH]` | 150.7                    | factored into ic_bands global pool |
| 24    | .ClassFile.version                    | `HH`                | 150.7                    | minor, major per-class override    |
| 25    | NestHost                              | `RCH`               | 180.0                    |                                    |
| 26    | NestMembers                           | `NH[RCH]`           | 180.0                    |                                    |
| 27    | RuntimeVisibleTypeAnnotations         | (type-anno layout)  | 171.0                    |                                    |
| 28    | RuntimeInvisibleTypeAnnotations       | (type-anno layout)  | 171.0                    |                                    |
| 29    | Module                                | see §7.2            | 180.0                    | dedicated band group               |
| 30    | ModulePackages                        | `NH[RXH]`           | 180.0                    | Package CP refs                    |
| 31    | ModuleMainClass                       | `RCH`               | 180.0                    |                                    |
| 32    | Record                                | special (§7.3)      | 190.0                    | **requires AO_HAVE_CLASS_FLAGS_HI**|
| 33    | PermittedSubclasses                   | `NH[RCH]`           | 190.0                    | **requires AO_HAVE_CLASS_FLAGS_HI**|

Indices 32 and 33 occupy bits in the **hi** flags word and are therefore only accessible
when `AO_HAVE_CLASS_FLAGS_HI` (bit 9 of `archive_options`) is set.

### 7.2 Module Attribute Layout (index 29)

The Module attribute uses a dedicated `class_Module_bands` sub-group nested inside
`class_attr_bands`. The attribute layout string (concatenated without spaces) is:

```
RJHHRUNH NH[RJHHRUNH] NH[RXHHNH[RJH]] NH[RXHHNH[RJH]] NH[RCH] NH[RCHNH[RCH]]
```

Structural breakdown:

| Sub-element | Layout fragment       | Bands                                     |
|-------------|-----------------------|-------------------------------------------|
| Module identity | `RJ H H RU`       | class_Module_MN, class_Module_MF, (version-flags), class_Module_MV |
| Requires count | `NH`               | class_Module_R_N                          |
| Requires entries | `RJ H H RU`     | class_Module_R_MN, class_Module_R_MF, (version-flags), class_Module_R_MV |
| Exports count  | `NH`               | class_Module_E_N                          |
| Exports entries | `RX H H NH[RJ]`  | class_Module_E_PN, class_Module_E_EF, class_Module_E_ET_N, class_Module_E_ET_MN |
| Opens count    | `NH`               | class_Module_O_N                          |
| Opens entries  | `RX H H NH[RJ]`  | class_Module_O_PN, class_Module_O_OF, class_Module_O_OT_N, class_Module_O_OT_MN |
| Uses count     | `NH`               | class_Module_U_N                          |
| Uses entries   | `RC`               | class_Module_U_RC                         |
| Provides count | `NH`               | class_Module_P_N                          |
| Provides entries | `RC NH[RC]`      | class_Module_P_RC, class_Module_P_PC_N, class_Module_P_PC_RC |

### 7.3 Record Attribute — Special Handling (index 32)

The Record attribute is **special-cased** in the same way as InnerClasses: its components
are not stored inline in the generic attribute stream. Instead they are factored into three
dedicated bands:

| Band                  | Content                                         |
|-----------------------|-------------------------------------------------|
| `class_Record_N`      | Number of record components (one entry per class with Record) |
| `class_Record_name_RU` | Utf8 CP reference for each component name      |
| `class_Record_type_RS` | Signature CP reference for each component descriptor |

Sub-attributes of each record component (e.g., `Signature`, `RuntimeVisibleAnnotations`)
are written into the standard metadata bands exactly as field/method sub-attributes are.

**Presence rule:** The Record bands exist **only** when `AO_HAVE_CLASS_FLAGS_HI` is set.
When that bit is absent, a reader MUST call `doneWithUnusedBand()` on all five
Record/PermittedSubclasses bands to satisfy the band-phase lifecycle.

### 7.4 Field-context Attributes (indices 17–28)

| Index | Attribute Name                        | Layout String    |
|-------|---------------------------------------|------------------|
| 17    | ConstantValue                         | `KQH`            |
| 19    | Signature                             | `RSH`            |
| 20    | Deprecated                            | `""`             |
| 21–22 | Runtime*Annotations                   | annotation layout|
| 27–28 | Runtime*TypeAnnotations               | type-anno layout |

### 7.5 Method-context Attributes (indices 17–28)

| Index | Attribute Name                              | Layout String    |
|-------|---------------------------------------------|------------------|
| 17    | Code                                        | (special)        |
| 18    | Exceptions                                  | `NH[RCH]`        |
| 19    | Signature                                   | `RSH`            |
| 20    | Deprecated                                  | `""`             |
| 21–22 | Runtime*Annotations                         | annotation layout|
| 23–24 | Runtime*ParameterAnnotations                | annotation layout|
| 25    | AnnotationDefault                           | annotation layout|
| 26    | MethodParameters                            | `NB[RUNHFH]`     |
| 27–28 | Runtime*TypeAnnotations                     | type-anno layout |

### 7.6 Code-context Attributes (indices 0–3)

| Index | Attribute Name            | Notes                             |
|-------|---------------------------|-----------------------------------|
| 0     | StackMapTable             | dedicated `stackmap_bands` group  |
| 1     | LineNumberTable           | `NH[PHH]`                         |
| 2     | LocalVariableTable        | `NH[PHOHRUHRSHH]`                 |
| 3     | LocalVariableTypeTable    | `NH[PHOHRUHRSHH]`                 |

---

## 8. Attribute Layout Language

The attribute layout language (from JSR-200 §7.5) encodes how attribute content maps to
integer bands. Reference types carry an implicit CP-index operand.

| Token | Meaning                                                    |
|-------|------------------------------------------------------------|
| `H`   | Unsigned short — stored in the next UNSIGNED5 slot         |
| `I`   | Unsigned int — stored in the next UNSIGNED5 slot           |
| `B`   | Signed byte — stored in the next BYTE1 slot                |
| `P`   | BCI offset — stored in the next BCI5 slot                  |
| `O`   | BCI branch offset — stored in the next BRANCH5 slot        |
| `N`   | Count prefix for a repeated group                          |
| `[…]` | Body of a repeated group (repeated `N` times)              |
| `T`   | Union tag byte followed by `(v)[…]` case branches         |
| `RU`  | Reference to Utf8 CP entry                                 |
| `RC`  | Reference to Class CP entry                                |
| `RS`  | Reference to Signature pseudo-type (tag 13) CP entry       |
| `RD`  | Reference to NameAndType CP entry                          |
| `RJ`  | Reference to Module CP entry (tag 19)                      |
| `RX`  | Reference to Package CP entry (tag 20)                     |
| `RM`  | Reference to MethodHandle CP entry                         |
| `RL`  | Reference to LoadableValue pseudo-group CP entry           |
| `KQ`  | FieldSpecific CP entry reference (ConstantValue)           |
| `KL`  | LoadableValue CP entry reference (BootstrapMethod arg)     |
| `FH`  | Access-flags word (stored as `H`)                          |

A trailing `H` on a reference token (e.g., `RCH`, `RUH`) makes the reference **nullable**
(zero CP index is permitted). A leading `N` before `[…]` introduces a count band followed
by that many repetitions of the bracketed body.

---

## 9. Band Ordering

All bands are written to and consumed from the output stream in a strict, fixed order
determined by the band declarations in `BandStructure.java`. The complete top-level
sequence is:

1. `archive_magic` — 4 literal bytes: `CA FE D0 0D`
2. `archive_header_0` — minver, majver, options (three UNSIGNED5 integers)
3. `archive_header_S` — size_hi, size_lo (conditional: `AO_HAVE_FILE_HEADERS`)
4. `archive_header_1` — cp counts, class counts, and all conditional extra counts
5. `band_headers` — per-band coding overrides (conditional: `AO_HAVE_SPECIAL_FORMATS`)
6. CP bands — in CP-tag order (§6)
7. `attr_definition_bands` — custom attribute name/layout definitions
8. `ic_bands` — InnerClasses global pool
9. `class_bands` — class schema plus all attribute data (§7)
10. Bytecode bands — `bc_codes` and all operand bands
11. File bands — filenames, sizes, modtimes, options, raw file-data bytes

Implementors MUST produce and consume all bands in exactly this order. A band that is
empty (no elements for the current archive) still MUST complete its full phase lifecycle
(`EXPECT → READ → DISBURSE → DONE`); the implementation calls `doneWithUnusedBand()` for
bands that have no data.

---

## 10. Conformance Requirements

An implementation is **conformant** if and only if all of the following hold.

### 10.1 Packer (Encoder)

Given a JAR containing `.class` files with major versions 45–71 (Java 1–27) and
optionally `module-info.class` files:

1. The produced archive begins with magic `CA FE D0 0D`.
2. The archive's package version follows the selection rule in §2.1.
3. `archive_options` bits are set exactly as specified in §3.
4. All CP entries defined in §6 are encoded in the appropriate CP bands.
5. All predefined attributes listed in §7 are encoded natively — no unknown-attribute
   warnings are emitted for attributes listed in §7.1–§7.6.
6. No `AO_UNUSED_MBZ` bits are set in `archive_options`. A packer MUST throw
   `IOException` if it would set a reserved bit.

### 10.2 Unpacker (Decoder)

Given a conformant archive:

1. The produced JAR is **semantically equivalent** to the original JAR: every class file's
   constant pool entries and all listed attributes (§7) round-trip without loss. CP index
   ordering may differ (the JVM format does not mandate a particular CP order), but all
   referenced names, types, and values MUST be identical.
2. The unpacker MUST accept all archives with package versions in the supported set
   `{150.7, 160.1, 170.1, 171.0, 180.0, 190.0, 190.1, 200.0, 210.0, 220.0}`.
3. The unpacker SHOULD attempt to decode archives with unknown future package versions
   (> 220.0) with a forward-compatibility warning, rather than hard-failing.

### 10.3 Unknown Attributes

Class files containing attributes not listed in §7 and not declared via
`attr_definition_bands` MUST be handled according to the `--unknown-attribute` policy:

| Policy  | Behaviour                                                         |
|---------|-------------------------------------------------------------------|
| `pass`  | Class file is transmitted as raw bytes; warning log entry emitted |
| `error` | `IOException` is thrown; message identifies the class and attr    |
| `strip` | Attribute is omitted from the packed output; warning is emitted   |

---

## 11. Multi-Release JAR Deployment

The implementation is distributed as a **Multi-Release JAR** (MRJAR, JEP 238) with the
following layer structure:

```
(root)/                           Java 8 base classes (--release 8)
META-INF/
  MANIFEST.MF                     Multi-Release: true
  versions/
    9/
      module-info.class           JPMS module descriptor (--release 9)
    17/
      net/pack200/Pack200.class           Java-17-compiled (AccessController retained)
      au/net/zeus/util/jar/pack/
        PropMap.class                     Java-17-compiled (AccessController retained)
```

### 11.1 JPMS Module Descriptor (Java 9+)

The `module-info.class` at `META-INF/versions/9/` declares the named module
`au.net.zeus.util.jar.pack`:

- **Exports** `net.pack200` — the public Pack200 API. The implementation package
  `au.net.zeus.util.jar.pack` is **not** exported.
- **Requires** `java.logging` — used for diagnostics throughout the implementation.
- **Requires static** `java.desktop` — `java.beans.PropertyChangeListener` and
  `java.beans.PropertyChangeEvent` are accessed optionally via reflection in
  `PropMap.Beans`; the `static` qualifier allows the module to load on runtimes without
  `java.desktop`.
- **Provides** `net.pack200.Pack200.Packer` **with** `au.net.zeus.util.jar.pack.PackerImpl`
- **Provides** `net.pack200.Pack200.Unpacker` **with** `au.net.zeus.util.jar.pack.UnpackerImpl`

The `provides` declarations enable callers that use `ServiceLoader` to obtain
`Packer`/`Unpacker` instances without depending on internal class names.

### 11.2 Java 17+ Class Files

The class files at `META-INF/versions/17/` are compiled with `--release 17`.
`java.security.AccessController.doPrivileged()` is **retained** in these class files —
the same way it appears in the Java 8 base classes — and will only be removed when a
future JEP formally eliminates the API from the platform:

| Class | Status |
|-------|--------|
| `net.pack200.Pack200` | `GetPropertyAction.privilegedGetProperty()` continues to use `AccessController.doPrivileged()` |
| `au.net.zeus.util.jar.pack.PropMap` | Static initializer and `getPropertyValue()` continue to use `AccessController.doPrivileged()` |

### 11.3 Build

Versioned sources are compiled during the `compile` phase using `maven-antrun-plugin`
with `javac --release N --patch-module au.net.zeus.util.jar.pack=target/classes` to
staging directories (`target/java9/`, `target/java17/`). This keeps `target/classes/`
clean during `bnd-process` (OSGi metadata generation). A `maven-resources-plugin`
execution in `prepare-package` copies the staged class files into
`target/classes/META-INF/versions/N/`. The manifest entry `Multi-Release: true` is added
by `maven-jar-plugin`; `bnd.bnd` carries `-multiRelease: true` for OSGi tooling.

---

## 12. Band Phase Lifecycle

Every band object progresses through four ordered phases:

1. **EXPECT** — The band is declared but not yet populated.
2. **READ** — The band's data has been read from the stream (reader) or elements have
   been added (writer).
3. **DISBURSE** — Elements are being consumed by the attribute-writing code.
4. **DONE** — All elements have been consumed.

A band that receives no elements (e.g., `class_Record_N` in an archive that contains no
record classes) MUST still be explicitly retired via `doneWithUnusedBand()` so the
enclosing `MultiBand` can confirm all its children have completed.

---

## 13. Compliance Test Suite

See `test/jdk/tools/pack200/compliance/` for the full compliance test suite. Tests are
designed to run under jtreg and follow the same `@test` / `@compile` / `@run` pattern
used by the existing tests in `test/jdk/tools/pack200/`.

| Test ID | File                             | Purpose                                       |
|---------|----------------------------------|-----------------------------------------------|
| C-01    | VersionNegotiationTest.java      | Pack version selected per §2.1                |
| C-02    | ArchiveOptionBitsTest.java       | Option bits set/clear per §3                  |
| C-03    | NestHostMembersTest.java         | NestHost/NestMembers round-trip (§7.1)        |
| C-04    | RecordAttributeTest.java         | Record attribute round-trip (§7.3)            |
| C-05    | PermittedSubclassesTest.java     | PermittedSubclasses round-trip (§7.1)         |
| C-06    | ModuleAttributeTest.java         | Module attribute round-trip (§7.2)            |
| C-07    | ModulePackagesMainClassTest.java | ModulePackages + ModuleMainClass (§7.1)       |
| C-08    | AnnotationRoundTripTest.java     | All four annotation variants (§7.1–§7.5)      |
| C-09    | LambdaBootstrapTest.java         | BootstrapMethods / InvokeDynamic (§6)         |
| C-10    | UnknownAttributeTest.java        | Unknown-attribute policies (§10.3)            |
| C-11    | BandPhaseIntegrityTest.java      | Band phase lifecycle with assertions (§12)    |
| C-12    | MultiClassVersionTest.java       | Mixed-version segment handling (§7.1 index 24)|
| C-13    | RoundTripSemanticVerify.java     | Attribute-content validation after round-trip |
