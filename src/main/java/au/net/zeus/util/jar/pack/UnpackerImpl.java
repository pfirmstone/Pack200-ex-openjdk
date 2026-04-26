/*
 * Copyright (c) 2003, 2015, Oracle and/or its affiliates. All rights reserved.
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
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package au.net.zeus.util.jar.pack;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.EventListener;
import java.util.HashSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.TimeZone;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import net.pack200.Pack200;
import java.util.zip.CRC32;
import java.util.zip.CheckedOutputStream;
import java.util.zip.ZipEntry;
import org.osgi.service.component.annotations.Component;

/*
 * Implementation of the Pack provider.
 * </pre></blockquote>
 * @author John Rose
 * @author Kumar Srinivasan
 */
@Component(service = Pack200.Unpacker.class)
public class UnpackerImpl extends TLGlobals implements Pack200.Unpacker {


    /**
     * Register a listener for changes to options.
     * @param listener  An object to be invoked when a property is changed.
     */
    @Override
    public void addPropertyChangeListener(EventListener listener) {
        props.addListener(listener);
    }


    /**
     * Remove a listener for the PropertyChange event.
     * @param listener  The PropertyChange listener to be removed.
     */
    @Override
    public void removePropertyChangeListener(EventListener listener) {
        props.removeListener(listener);
    }

    public UnpackerImpl() {}



    /**
     * Get the set of options for the pack and unpack engines.
     * @return A sorted association of option key strings to option values.
     */
    @Override
    public SortedMap<String, String> properties() {
        return props;
    }

    @Override
    public String toString() {
        return Utils.getVersionString();
    }

    //Driver routines

    // The unpack worker...
    /**
     * Takes a packed-stream InputStream, and writes to a JarOutputStream. Internally
     * the entire buffer must be read, it may be more efficient to read the packed-stream
     * to a file and pass the File object, in the alternate method described below.
     * <p>
     * Closes its input but not its output.  (The output can accumulate more elements.)
     * @param in an InputStream.
     * @param out a JarOutputStream.
     * @exception IOException if an error is encountered.
     */
    @Override
    public synchronized void unpack(InputStream in, JarOutputStream out) throws IOException {
        if (in == null) {
            throw new NullPointerException("null input");
        }
        if (out == null) {
            throw new NullPointerException("null output");
        }
        assert(Utils.currentInstance.get() == null);
        TimeZone tz = (props.getBoolean(Utils.PACK_DEFAULT_TIMEZONE))
                      ? null
                      : TimeZone.getDefault();

        try {
            Utils.currentInstance.set(this);
            if (tz != null) TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            final int verbose = props.getInteger(Utils.DEBUG_VERBOSE);
            BufferedInputStream in0 = new BufferedInputStream(in);
            if (Utils.isJarMagic(Utils.readMagic(in0))) {
                if (verbose > 0)
                    Utils.log.info("Copying unpacked JAR file...");
                Utils.copyJarFile(new JarInputStream(in0), out);
            } else {
                (new DoUnpack()).run(in0, out);
                in0.close();
                Utils.markJarFile(out);
            }
        } finally {
            Utils.currentInstance.set(null);
            if (tz != null) TimeZone.setDefault(tz);
        }
    }

    /**
     * Takes an input File containing the pack file, and generates a JarOutputStream.
     * <p>
     * Does not close its output.  (The output can accumulate more elements.)
     * @param in a File.
     * @param out a JarOutputStream.
     * @exception IOException if an error is encountered.
     */
    @Override
    public synchronized void unpack(File in, JarOutputStream out) throws IOException {
        if (in == null) {
            throw new NullPointerException("null input");
        }
        if (out == null) {
            throw new NullPointerException("null output");
        }
        // Use the stream-based implementation.
        try (FileInputStream instr = new FileInputStream(in)) {
            unpack(instr, out);
        }
        if (props.getBoolean(Utils.UNPACK_REMOVE_PACKFILE)) {
            in.delete();
        }
    }

    private class DoUnpack {
        // Buffer size used when streaming file bytes to the JarOutputStream.
        static final int STREAM_BUF_SIZE = 1 << 16;

        final int verbose = props.getInteger(Utils.DEBUG_VERBOSE);

        {
            props.setInteger(Pack200.Unpacker.PROGRESS, 0);
        }

        // Here's where the bits are read from disk:
        final Package pkg = new Package();

        final boolean keepModtime
            = Pack200.Packer.KEEP.equals(
              props.getProperty(Utils.UNPACK_MODIFICATION_TIME, Pack200.Packer.KEEP));
        final boolean keepDeflateHint
            = Pack200.Packer.KEEP.equals(
              props.getProperty(Pack200.Unpacker.DEFLATE_HINT, Pack200.Packer.KEEP));
        final int modtime;
        final boolean deflateHint;
        {
            if (!keepModtime) {
                modtime = props.getTime(Utils.UNPACK_MODIFICATION_TIME);
            } else {
                modtime = pkg.default_modtime;
            }

            deflateHint = (keepDeflateHint) ? false :
                props.getBoolean(net.pack200.Pack200.Unpacker.DEFLATE_HINT);
        }

        // Checksum apparatus.
        final CRC32 crc = new CRC32();
        // Pre-sized to a typical class file to minimise internal resize-and-copy cycles.
        // Used only for STORED entries that require CRC and size before putNextEntry.
        final ByteArrayOutputStream bufOut = new ByteArrayOutputStream(4096);
        final OutputStream crcOut = new CheckedOutputStream(bufOut, crc);

        // Reusable transfer buffer — allocated once per DoUnpack and shared across
        // all transferBytes calls to avoid repeated 64 KB allocations.
        final byte[] transferBuf = new byte[STREAM_BUF_SIZE];

        public void run(BufferedInputStream in, JarOutputStream out) throws IOException {
            if (verbose > 0) {
                props.list(System.out);
            }
            for (int seg = 1; ; seg++) {
                unpackSegment(in, out);

                // Try to get another segment.
                if (!Utils.isPackMagic(Utils.readMagic(in)))  break;
                if (verbose > 0)
                    Utils.log.info("Finished segment #"+seg);
            }
        }

        /**
         * Transfers exactly {@code size} bytes from {@code src} to {@code dst},
         * reusing the shared {@link #transferBuf}.  Throws {@link java.io.EOFException}
         * if the source stream ends prematurely.
         */
        private void transferBytes(InputStream src, OutputStream dst, long size)
                throws IOException {
            long remaining = size;
            while (remaining > 0) {
                int nr = (int) Math.min(transferBuf.length, remaining);
                nr = src.read(transferBuf, 0, nr);
                if (nr < 0)  throw new java.io.EOFException();
                dst.write(transferBuf, 0, nr);
                remaining -= nr;
            }
        }

        private void unpackSegment(InputStream in, JarOutputStream out) throws IOException {
            props.setProperty(net.pack200.Pack200.Unpacker.PROGRESS,"0");
            // Process the output directory or jar output.
            // Resource files (non-class-stub entries) are written directly to
            // the JarOutputStream via the consumer below, avoiding a full heap
            // copy into Package.File.  Class-stub entries still go through the
            // normal reconstructClass/ClassWriter path after read() returns.
            PackageReader reader = new PackageReader(pkg, in);
            reader.setResourceFileConsumer((file, data, size) -> {
                String name = file.nameString;
                JarEntry je = new JarEntry(Utils.getJarEntryName(name));
                boolean deflate = (keepDeflateHint)
                        ? (((file.options & Constants.FO_DEFLATE_HINT) != 0) ||
                          ((pkg.default_options & Constants.AO_DEFLATE_HINT) != 0))
                        : deflateHint;
                boolean needCRC = !deflate;
                if (keepModtime) {
                    je.setTime((long)file.modtime * 1000);
                } else {
                    je.setTime((long)modtime * 1000);
                }
                if (needCRC) {
                    // STORED entry: must supply CRC and size before putNextEntry,
                    // so buffer once through crcOut to compute both.
                    crc.reset();
                    bufOut.reset();
                    transferBytes(data, crcOut, size);
                    if (verbose > 0)
                        Utils.log.info("stored size="+bufOut.size()+" and crc="+crc.getValue());
                    je.setMethod(JarEntry.STORED);
                    je.setSize(bufOut.size());
                    je.setCrc(crc.getValue());
                    out.putNextEntry(je);
                    bufOut.writeTo(out);
                } else {
                    // DEFLATED entry: stream bytes directly — no intermediate buffer needed.
                    je.setMethod(JarEntry.DEFLATED);
                    out.putNextEntry(je);
                    transferBytes(data, out, size);
                }
                out.closeEntry();
                if (verbose > 0)
                    Utils.log.info("Writing "+Utils.zeString((ZipEntry)je));
            });
            reader.read();

            if (props.getBoolean("unpack.strip.debug"))    pkg.stripAttributeKind("Debug");
            if (props.getBoolean("unpack.strip.compile"))  pkg.stripAttributeKind("Compile");
            props.setProperty(net.pack200.Pack200.Unpacker.PROGRESS,"50");
            pkg.ensureAllClassFiles();
            // Write class-stub entries (resource files were already written above).
            Set<Package.Class> classesToWrite = new HashSet<>(pkg.getClasses());
            for (Package.File file : pkg.getFiles()) {
                if (!file.isClassStub()) continue;  // Already streamed by consumer

                String name = file.nameString;
                JarEntry je = new JarEntry(Utils.getJarEntryName(name));
                boolean deflate;

                deflate = (keepDeflateHint)
                          ? (((file.options & Constants.FO_DEFLATE_HINT) != 0) ||
                            ((pkg.default_options & Constants.AO_DEFLATE_HINT) != 0))
                          : deflateHint;

                boolean needCRC = !deflate;  // STORE mode requires CRC

                Package.Class cls = file.getStubClass();
                assert(cls != null);
                if (keepModtime) {
                    je.setTime((long)file.modtime * 1000);
                } else {
                    je.setTime((long)modtime * 1000);
                }
                if (needCRC) {
                    // STORED entry: must supply CRC and size before putNextEntry,
                    // so buffer once through crcOut to compute both.
                    crc.reset();
                    bufOut.reset();
                    new ClassWriter(cls, crcOut).write();
                    classesToWrite.remove(cls);  // for an error check
                    if (verbose > 0)
                        Utils.log.info("stored size="+bufOut.size()+" and crc="+crc.getValue());
                    je.setMethod(JarEntry.STORED);
                    je.setSize(bufOut.size());
                    je.setCrc(crc.getValue());
                    out.putNextEntry(je);
                    bufOut.writeTo(out);
                } else {
                    // DEFLATED entry: stream class bytes directly — no bufOut copy needed.
                    classesToWrite.remove(cls);  // for an error check
                    je.setMethod(JarEntry.DEFLATED);
                    out.putNextEntry(je);
                    new ClassWriter(cls, out).write();
                }
                out.closeEntry();
                if (verbose > 0)
                    Utils.log.info("Writing "+Utils.zeString((ZipEntry)je));
            }
            assert(classesToWrite.isEmpty());
            props.setProperty(net.pack200.Pack200.Unpacker.PROGRESS,"100");
            pkg.reset();  // reset for the next segment, if any
        }
    }
}
