# This file identifies the root of the test-suite hierarchy.
# It also contains test-suite configuration information.

# The list of keywords supported in the entire test suite.  The
# "intermittent" keyword marks tests known to fail intermittently.
# The "randomness" keyword marks tests using randomness with test
# cases differing from run to run. (A test using a fixed random seed
# would not count as "randomness" by this definition.) Extra care
# should be taken to handle test failures of intermittent or
# randomness tests.
#
# A "headful" test requires a graphical environment to meaningfully
# run. Tests that are not headful are "headless".
# A test flagged with key "printer" requires a printer to succeed, else
# throws a PrinterException or the like.

keys=2d dnd headful i18n intermittent printer randomness jfr

# Group definitions
groups=TEST.groups

# Allow querying of various System properties in @requires clauses
#
# Pack200 tests only use built-in jtreg properties (jdk.version.major,
# sun.arch.data.model, os.maxMemory) which require no extra boot classes.
requires.properties= \
    sun.arch.data.model \
    java.runtime.name

# Minimum jtreg version
requiredVersion=4.2 b14

# Path to libraries in the topmost test directory. This is needed so @library
# does not need ../../ notation to reach them
external.lib.roots = ../../

# Use new module options
useNewOptions=true

# Use --patch-module instead of -Xmodule:
useNewPatchModule=true

# disabled till JDK-8219408 is fixed
allowSmartActionArgs=false
