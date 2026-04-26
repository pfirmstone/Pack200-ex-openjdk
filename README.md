# Pack200-ex-openjdk
Pack200 from OpenJDK - updated to Java 27

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
