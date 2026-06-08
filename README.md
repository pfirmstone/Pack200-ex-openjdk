# Pack200-ex-openjdk
Pack200 from OpenJDK - updated to Java 27

## Features

- **Pack200 packer / unpacker** — the Java SE 8 API
  (`java.util.jar.Pack200.Packer` / `Unpacker`) reborn as
  `net.pack200.Pack200`, maintained for current JDKs and extended to
  cover Java 9–27 classfile attributes.
- **JAR normalisation** — `net.pack200.Normalize`, the library replacement
  for the `jar --normalize` / `jar -n` flag that OpenJDK deleted with
  [JEP 367](https://openjdk.org/jeps/367) in JDK 15. Produces a Pack200
  fixed point suitable for *sign-then-pack* workflows and, with the default
  `Options.reproducible()`, produces a JAR whose SHA-256 depends only on
  the input class/resource bytes (not on timestamps, entry order, or host
  filesystem attributes). See [`docs/normalize.md`](docs/normalize.md).

## Build Requirements

- **JDK 11 or later** — The build uses the `--release` flag in the Ant `javac` task
  (via `maven-antrun-plugin`) to compile `module-info.java` for the Java 9 multi-release
  JAR layer.  The `--release` flag was introduced in Java 9 and is **not** available in
  Java 8's `javac`.  Attempting to build with JDK 8 will fail with
  `"invalid flag: --release"`.
- **Maven 3.9.15 or later**

## Build instructions:
Install Maven 3.9.15 or later
Install jtreg 7.5.1 or later.
set JT_HOME env variable to the jtreg install directory.

## Running tests

`mvn verify` invokes jtreg via Ant (`test/build.xml`).  Tests listed in
`test/jdk/ProblemList.txt` are automatically excluded from every run.

### Overriding the ProblemList path

Set the `JTREG_PROBLEM_LIST` environment variable to the path of an
alternative problem-list file before running Maven:

```
export JTREG_PROBLEM_LIST=/path/to/custom/ProblemList.txt
mvn verify
```

Alternatively, pass the Ant property directly through the Maven command line:

```
mvn verify -Dproblem.list=/path/to/custom/ProblemList.txt
```

If neither override is provided, `test/jdk/ProblemList.txt` (relative to the
repository root) is used as the default.
