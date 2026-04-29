# Pack200-ex-openjdk
Pack200 from OpenJDK - updated to Java 27

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
