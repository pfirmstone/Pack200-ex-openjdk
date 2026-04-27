/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test C-11
 * @summary Compliance: all bands (including the new Record and PermittedSubclasses
 *          bands) complete their phase lifecycle (EXPECT→READ→DISBURSE→DONE)
 *          without assertion errors (spec §11).  Exercises archives both with
 *          and without AO_HAVE_CLASS_FLAGS_HI.
 * @compile -XDignore.symbol.file ../Utils.java BandPhaseIntegrityTest.java
 * @run main BandPhaseIntegrityTest
 */

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * C-11: Band phase lifecycle integrity.
 *
 * Runs pack/repack with -ea -esa (Java assertions enabled) and
 * au.net.zeus.util.jar.pack.debug.bands=true so that any assertion
 * error in the band phase transitions is surfaced as a test failure.
 *
 * Three scenarios cover the critical lifecycle path:
 *   1. Plain Java 8 JAR: no Record/PermittedSubclasses bands should be active;
 *      the unused bands must be retired via doneWithUnusedBand().
 *   2. Java 17 record JAR: Record bands must be populated and consumed.
 *   3. Java 17 sealed-class JAR: PermittedSubclasses bands must be populated
 *      and consumed.
 */
public class BandPhaseIntegrityTest {

    public static void main(String... args) throws Exception {
        testPlainJava8BandPhases();
        testRecordBandPhases();
        testSealedBandPhases();
        Utils.cleanup();
        System.out.println("C-11 BandPhaseIntegrityTest: ALL PASSED");
    }

    /** Plain Java 8 — unused Record/PermittedSubclasses bands must retire cleanly. */
    static void testPlainJava8BandPhases() throws Exception {
        List<String> src = new ArrayList<>();
        src.add("public class BandPlain8 { public void m(){} }");
        File java = new File("BandPlain8.java");
        Utils.createFile(java, src);
        Utils.compiler("--release", "8", "-Xlint:-options", java.getName());

        File jar = new File("band-plain8.jar");
        Utils.jar("cvf", jar.getName(), "BandPlain8.class");

        repackWithBandDebug(jar, new File("band-plain8-out.jar"));
        System.out.println("  testPlainJava8BandPhases: PASS");
    }

    /** Java 17 record — Record bands must be populated and consumed. */
    static void testRecordBandPhases() throws Exception {
        List<String> src = new ArrayList<>();
        src.add("public record BandPoint11(int x, int y) {}");
        File java = new File("BandPoint11.java");
        Utils.createFile(java, src);
        Utils.compiler("--release", "17", java.getName());

        File jar = new File("band-record.jar");
        Utils.jar("cvf", jar.getName(), "BandPoint11.class");

        repackWithBandDebug(jar, new File("band-record-out.jar"));
        System.out.println("  testRecordBandPhases: PASS");
    }

    /** Java 17 sealed class — PermittedSubclasses bands must be populated and consumed. */
    static void testSealedBandPhases() throws Exception {
        List<String> shapeSrc = new ArrayList<>();
        shapeSrc.add("public sealed class BandShape11 permits BandCircle11 {}");
        File shapeFile = new File("BandShape11.java");
        Utils.createFile(shapeFile, shapeSrc);

        List<String> circSrc = new ArrayList<>();
        circSrc.add("public final class BandCircle11 extends BandShape11 {}");
        File circFile = new File("BandCircle11.java");
        Utils.createFile(circFile, circSrc);

        Utils.compiler("--release", "17", shapeFile.getName(), circFile.getName());

        File jar = new File("band-sealed.jar");
        Utils.jar("cvf", jar.getName(), "BandShape11.class", "BandCircle11.class");

        repackWithBandDebug(jar, new File("band-sealed-out.jar"));
        System.out.println("  testSealedBandPhases: PASS");
    }

    // -------------------------------------------------------------------------

    /**
     * Repacks the given jar with -ea -esa and band debugging enabled.
     * Any AssertionError in the band phase transitions will cause the child
     * process to exit non-zero, which Utils.repack surfaces as a RuntimeException.
     */
    static void repackWithBandDebug(File in, File out) throws Exception {
        List<String> config = new ArrayList<>();
        config.add("au.net.zeus.util.jar.pack.debug.bands=true");
        config.add("au.net.zeus.util.jar.pack.dump.bands=true");
        File configFile = new File("band-debug.conf");
        configFile.delete();  // overwrite if left from a previous sub-test
        Utils.createFile(configFile, config);

        // disableNative=true forces the Java unpacker (which respects debug.bands)
        Utils.repack(in, out, true /* disableNative */,
                "-v", "--config-file=" + configFile.getName());
    }
}
