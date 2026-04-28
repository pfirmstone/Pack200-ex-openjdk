/*
 * Copyright (c) 2010, 2024, Oracle and/or its affiliates. All rights reserved.
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
package xmlkit; // -*- mode: java; indent-tabs-mode: nil -*-

import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import xmlkit.XMLKit.Element;

import java.io.*;
import java.util.*;
import java.util.jar.*;

/*
 * ASM-based class file reader producing XMLKit.Element trees.
 * Replaces the com.sun.tools.classfile-based implementation.
 * Uses org.ow2.asm:asm 9.x - no internal JDK APIs required.
 *
 * @author jrose, ksrini (original); rewritten for ASM 9.x
 */
public class ClassReader {

    private static final CommandLineParser CLP = new CommandLineParser(""
            + "-source:     +> = \n"
            + "-dest:       +> = \n"
            + "-encoding:   +> = \n"
            + "-jcov           $ \n   -nojcov         !-jcov        \n"
            + "-verbose        $ \n   -noverbose      !-verbose     \n"
            + "-keepPath       $ \n   -nokeepPath     !-keepPath    \n"
            + "-keepCP         $ \n   -nokeepCP       !-keepCP      \n"
            + "-keepOrder      $ \n   -nokeepOrder    !-keepOrder   \n"
            + "-continue       $ \n   -nocontinue     !-continue    \n"
            + "-@         >-@  . \n"
            + "-              +? \n"
            + "\n");

    protected Element cfile;

    public static void main(String[] ava) throws IOException {
        ArrayList<String> av = new ArrayList<>(Arrays.asList(ava));
        HashMap<String, String> props = new HashMap<>();
        props.put("-encoding:", "UTF8");
        props.put("-keepOrder", null);
        props.put("-pretty", "1");
        props.put("-continue", "1");
        CLP.parse(av, props);
        File source = asFile(props.get("-source:"));
        File dest = asFile(props.get("-dest:"));
        String encoding = props.get("-encoding:");
        boolean contError = props.containsKey("-continue");
        ClassReader options = new ClassReader();
        options.copyOptionsFrom(props);
        if (av.isEmpty()) {
            av.add("");
        }
        boolean readList = false;
        for (String a : av) {
            if (readList) {
                readList = false;
                InputStream fin;
                if (a.equals("-")) {
                    fin = System.in;
                } else {
                    fin = new FileInputStream(a);
                }
                BufferedReader files = makeReader(fin, encoding);
                for (String file; (file = files.readLine()) != null;) {
                    doFile(file, source, dest, options, encoding, contError);
                }
                if (fin != System.in) {
                    fin.close();
                }
            } else if (a.equals("-@")) {
                readList = true;
            } else if (a.startsWith("-")) {
                throw new RuntimeException("Bad flag argument: " + a);
            } else if (source != null && source.getName().endsWith(".jar")) {
                doJar(a, source, dest, options, encoding, contError);
            } else {
                doFile(a, source, dest, options, encoding, contError);
            }
        }
    }

    private static File asFile(String str) {
        return (str == null) ? null : new File(str);
    }

    private static void doFile(String a, File source, File dest,
            ClassReader options, String encoding,
            boolean contError) throws IOException {
        if (!contError) {
            doFile(a, source, dest, options, encoding);
        } else {
            try {
                doFile(a, source, dest, options, encoding);
            } catch (Exception ee) {
                System.out.println("Error processing " + source + ": " + ee);
                ee.printStackTrace();
            }
        }
    }

    private static void doJar(String a, File source, File dest,
            ClassReader options, String encoding,
            boolean contError) throws IOException {
        try (JarFile jf = new JarFile(source)) {
            for (JarEntry je : Collections.list(jf.entries())) {
                String name = je.getName();
                if (!name.endsWith(".class")) {
                    continue;
                }
                try {
                    doStream(name, jf.getInputStream(je), dest, options, encoding);
                } catch (Exception e) {
                    if (contError) {
                        System.out.println("Error processing " + source + ": " + e);
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private static void doStream(String a, InputStream in, File dest,
                                  ClassReader options, String encoding) throws IOException {
        File f = new File(a);
        ClassReader cr = new ClassReader(options);
        if (options.verbose) {
            System.out.println("Reading " + f);
        }
        Element e = cr.readFrom(in);
        OutputStream out;
        if (dest == null) {
            out = System.out;
        } else {
            File outf = new File(dest, f.isAbsolute() ? f.getName() : f.getPath());
            String outName = outf.getName();
            File outSubdir = outf.getParentFile();
            outSubdir.mkdirs();
            int extPos = outName.lastIndexOf('.');
            if (extPos > 0) {
                outf = new File(outSubdir, outName.substring(0, extPos) + ".xml");
            }
            out = new FileOutputStream(outf);
        }
        Writer outw = makeWriter(out, encoding);
        if (options.pretty || !options.keepOrder) {
            e.writePrettyTo(outw);
        } else {
            e.writeTo(outw);
        }
        if (out == System.out) {
            outw.write("\n");
            outw.flush();
        } else {
            outw.close();
        }
    }

    private static void doFile(String a, File source, File dest,
            ClassReader options, String encoding) throws IOException {
        File inf = new File(source, a);
        if (dest != null && options.verbose) {
            System.out.println("Reading " + inf);
        }
        doStream(a, new BufferedInputStream(new FileInputStream(inf)), dest, options, encoding);
    }

    public static BufferedReader makeReader(InputStream in,
                                            String encoding) throws IOException {
        Reader inw;
        in = new BufferedInputStream(in);
        if (encoding == null) {
            inw = new InputStreamReader(in);
        } else {
            inw = new InputStreamReader(in, encoding);
        }
        return new BufferedReader(inw);
    }

    public static Writer makeWriter(OutputStream out,
                                    String encoding) throws IOException {
        Writer outw;
        if (encoding == null) {
            outw = new OutputStreamWriter(out);
        } else {
            outw = new OutputStreamWriter(out, encoding);
        }
        return new BufferedWriter(outw);
    }

    public Element result() {
        return cfile;
    }

    public boolean pretty = false;
    public boolean verbose = false;
    public boolean keepPath = false;
    public boolean keepCP = false;
    public boolean keepOrder = true;

    public ClassReader() {
        cfile = new Element("ClassFile");
    }

    public ClassReader(ClassReader options) {
        this();
        copyOptionsFrom(options);
    }

    public void copyOptionsFrom(ClassReader options) {
        pretty = options.pretty;
        verbose = options.verbose;
        keepPath = options.keepPath;
        keepCP = options.keepCP;
        keepOrder = options.keepOrder;
    }

    public void copyOptionsFrom(Map<String, String> options) {
        if (options.containsKey("-pretty")) {
            pretty = (options.get("-pretty") != null);
        }
        if (options.containsKey("-verbose")) {
            verbose = (options.get("-verbose") != null);
        }
        if (options.containsKey("-keepPath")) {
            keepPath = (options.get("-keepPath") != null);
        }
        if (options.containsKey("-keepCP")) {
            keepCP = (options.get("-keepCP") != null);
        }
        if (options.containsKey("-keepOrder")) {
            keepOrder = (options.get("-keepOrder") != null);
        }
    }

    public Element readFrom(InputStream in) throws IOException {
        byte[] bytes = readAllBytes(in);
        org.objectweb.asm.ClassReader asmReader = new org.objectweb.asm.ClassReader(bytes);
        cfile = new Element("ClassFile");
        // 0xCAFEBABE as signed int = -889275714
        cfile.setAttr("magic", "-889275714");
        XmlClassVisitor visitor = new XmlClassVisitor(this);
        asmReader.accept(visitor, 0);
        if (keepCP) {
            // ASM resolves CP entries; add empty marker element for keepCP mode
            cfile.add(0, new Element("ConstantPool"));
        }
        if (!keepOrder) {
            sortRecursively(cfile);
        }
        return cfile;
    }

    /** Recursively sort all children of an element for order-independent comparison. */
    static void sortRecursively(Element e) {
        e.sort();
        for (int i = 0; i < e.size(); i++) {
            Object child = e.get(i);
            if (child instanceof Element) {
                sortRecursively((Element) child);
            }
        }
    }

    public Element readFrom(File file) throws IOException {
        try (InputStream strm = new FileInputStream(file)) {
            Element e = readFrom(new BufferedInputStream(strm));
            if (keepPath) {
                e.setAttr("path", file.toString());
            }
            return e;
        }
    }

    // ====================================================================
    // Package-internal helpers used by visitor classes
    // ====================================================================

    static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(4096);
        byte[] tmp = new byte[4096];
        int n;
        while ((n = in.read(tmp)) != -1) {
            buf.write(tmp, 0, n);
        }
        return buf.toByteArray();
    }

    // ---- Flag strings ----

    static String flagString(int access, String kind) {
        // Mask to standard JVM bits only (exclude ASM-specific high bits)
        int flags = access & 0xFFFF;
        StringBuilder sb = new StringBuilder();
        switch (kind) {
            case "Class":
                appendFlag(sb, flags, 0x0001, "public");
                appendFlag(sb, flags, 0x0010, "final");
                appendFlag(sb, flags, 0x0020, "super");
                appendFlag(sb, flags, 0x0200, "interface");
                appendFlag(sb, flags, 0x0400, "abstract");
                appendFlag(sb, flags, 0x1000, "synthetic");
                appendFlag(sb, flags, 0x2000, "annotation");
                appendFlag(sb, flags, 0x4000, "enum");
                appendFlag(sb, flags, 0x8000, "module");
                break;
            case "Field":
                appendFlag(sb, flags, 0x0001, "public");
                appendFlag(sb, flags, 0x0002, "private");
                appendFlag(sb, flags, 0x0004, "protected");
                appendFlag(sb, flags, 0x0008, "static");
                appendFlag(sb, flags, 0x0010, "final");
                appendFlag(sb, flags, 0x0040, "volatile");
                appendFlag(sb, flags, 0x0080, "transient");
                appendFlag(sb, flags, 0x1000, "synthetic");
                appendFlag(sb, flags, 0x4000, "enum");
                break;
            case "Method":
                appendFlag(sb, flags, 0x0001, "public");
                appendFlag(sb, flags, 0x0002, "private");
                appendFlag(sb, flags, 0x0004, "protected");
                appendFlag(sb, flags, 0x0008, "static");
                appendFlag(sb, flags, 0x0010, "final");
                appendFlag(sb, flags, 0x0020, "synchronized");
                appendFlag(sb, flags, 0x0040, "bridge");
                appendFlag(sb, flags, 0x0080, "varargs");
                appendFlag(sb, flags, 0x0100, "native");
                appendFlag(sb, flags, 0x0400, "abstract");
                appendFlag(sb, flags, 0x0800, "strict");
                appendFlag(sb, flags, 0x1000, "synthetic");
                break;
            case "InnerClass":
                appendFlag(sb, flags, 0x0001, "public");
                appendFlag(sb, flags, 0x0002, "private");
                appendFlag(sb, flags, 0x0004, "protected");
                appendFlag(sb, flags, 0x0008, "static");
                appendFlag(sb, flags, 0x0010, "final");
                appendFlag(sb, flags, 0x0200, "interface");
                appendFlag(sb, flags, 0x0400, "abstract");
                appendFlag(sb, flags, 0x1000, "synthetic");
                appendFlag(sb, flags, 0x2000, "annotation");
                appendFlag(sb, flags, 0x4000, "enum");
                break;
            default:
                break;
        }
        return sb.toString().trim();
    }

    private static void appendFlag(StringBuilder sb, int flags, int mask, String name) {
        if ((flags & mask) != 0) {
            sb.append(name).append(' ');
        }
    }

    // ---- Handle / LDC helpers ----

    private static final String[] METHOD_HANDLE_NAMES = {
        null,
        "REF_getField", "REF_getStatic", "REF_putField", "REF_putStatic",
        "REF_invokeVirtual", "REF_invokeStatic", "REF_invokeSpecial",
        "REF_newInvokeSpecial", "REF_invokeInterface"
    };

    static String handleToString(Handle h) {
        int tag = h.getTag();
        String refKind = (tag >= 1 && tag <= 9) ? METHOD_HANDLE_NAMES[tag] : "REF_" + tag;
        return refKind + " " + h.getOwner() + " " + h.getName() + " " + h.getDesc();
    }

    static String ldcArgToString(Object value) {
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double) {
            return value.toString();
        }
        if (value instanceof Type) {
            Type t = (Type) value;
            return t.getSort() == Type.METHOD ? t.getDescriptor() : t.getInternalName();
        }
        if (value instanceof Handle) {
            return handleToString((Handle) value);
        }
        return value.toString();
    }

    // ---- newarray type names ----

    static final String[] NEWARRAY_TYPES = {
        null, null, null, null,
        "boolean", "char", "float", "double", "byte", "short", "int", "long"
    };

    // ---- Opcode names ----

    static final String[] OPCODE_NAMES = new String[256];

    static {
        OPCODE_NAMES[0]   = "nop";
        OPCODE_NAMES[1]   = "aconst_null";
        OPCODE_NAMES[2]   = "iconst_m1";
        OPCODE_NAMES[3]   = "iconst_0";
        OPCODE_NAMES[4]   = "iconst_1";
        OPCODE_NAMES[5]   = "iconst_2";
        OPCODE_NAMES[6]   = "iconst_3";
        OPCODE_NAMES[7]   = "iconst_4";
        OPCODE_NAMES[8]   = "iconst_5";
        OPCODE_NAMES[9]   = "lconst_0";
        OPCODE_NAMES[10]  = "lconst_1";
        OPCODE_NAMES[11]  = "fconst_0";
        OPCODE_NAMES[12]  = "fconst_1";
        OPCODE_NAMES[13]  = "fconst_2";
        OPCODE_NAMES[14]  = "dconst_0";
        OPCODE_NAMES[15]  = "dconst_1";
        OPCODE_NAMES[16]  = "bipush";
        OPCODE_NAMES[17]  = "sipush";
        OPCODE_NAMES[18]  = "ldc";
        OPCODE_NAMES[19]  = "ldc_w";
        OPCODE_NAMES[20]  = "ldc2_w";
        OPCODE_NAMES[21]  = "iload";
        OPCODE_NAMES[22]  = "lload";
        OPCODE_NAMES[23]  = "fload";
        OPCODE_NAMES[24]  = "dload";
        OPCODE_NAMES[25]  = "aload";
        OPCODE_NAMES[26]  = "iload_0";
        OPCODE_NAMES[27]  = "iload_1";
        OPCODE_NAMES[28]  = "iload_2";
        OPCODE_NAMES[29]  = "iload_3";
        OPCODE_NAMES[30]  = "lload_0";
        OPCODE_NAMES[31]  = "lload_1";
        OPCODE_NAMES[32]  = "lload_2";
        OPCODE_NAMES[33]  = "lload_3";
        OPCODE_NAMES[34]  = "fload_0";
        OPCODE_NAMES[35]  = "fload_1";
        OPCODE_NAMES[36]  = "fload_2";
        OPCODE_NAMES[37]  = "fload_3";
        OPCODE_NAMES[38]  = "dload_0";
        OPCODE_NAMES[39]  = "dload_1";
        OPCODE_NAMES[40]  = "dload_2";
        OPCODE_NAMES[41]  = "dload_3";
        OPCODE_NAMES[42]  = "aload_0";
        OPCODE_NAMES[43]  = "aload_1";
        OPCODE_NAMES[44]  = "aload_2";
        OPCODE_NAMES[45]  = "aload_3";
        OPCODE_NAMES[46]  = "iaload";
        OPCODE_NAMES[47]  = "laload";
        OPCODE_NAMES[48]  = "faload";
        OPCODE_NAMES[49]  = "daload";
        OPCODE_NAMES[50]  = "aaload";
        OPCODE_NAMES[51]  = "baload";
        OPCODE_NAMES[52]  = "caload";
        OPCODE_NAMES[53]  = "saload";
        OPCODE_NAMES[54]  = "istore";
        OPCODE_NAMES[55]  = "lstore";
        OPCODE_NAMES[56]  = "fstore";
        OPCODE_NAMES[57]  = "dstore";
        OPCODE_NAMES[58]  = "astore";
        OPCODE_NAMES[59]  = "istore_0";
        OPCODE_NAMES[60]  = "istore_1";
        OPCODE_NAMES[61]  = "istore_2";
        OPCODE_NAMES[62]  = "istore_3";
        OPCODE_NAMES[63]  = "lstore_0";
        OPCODE_NAMES[64]  = "lstore_1";
        OPCODE_NAMES[65]  = "lstore_2";
        OPCODE_NAMES[66]  = "lstore_3";
        OPCODE_NAMES[67]  = "fstore_0";
        OPCODE_NAMES[68]  = "fstore_1";
        OPCODE_NAMES[69]  = "fstore_2";
        OPCODE_NAMES[70]  = "fstore_3";
        OPCODE_NAMES[71]  = "dstore_0";
        OPCODE_NAMES[72]  = "dstore_1";
        OPCODE_NAMES[73]  = "dstore_2";
        OPCODE_NAMES[74]  = "dstore_3";
        OPCODE_NAMES[75]  = "astore_0";
        OPCODE_NAMES[76]  = "astore_1";
        OPCODE_NAMES[77]  = "astore_2";
        OPCODE_NAMES[78]  = "astore_3";
        OPCODE_NAMES[79]  = "iastore";
        OPCODE_NAMES[80]  = "lastore";
        OPCODE_NAMES[81]  = "fastore";
        OPCODE_NAMES[82]  = "dastore";
        OPCODE_NAMES[83]  = "aastore";
        OPCODE_NAMES[84]  = "bastore";
        OPCODE_NAMES[85]  = "castore";
        OPCODE_NAMES[86]  = "sastore";
        OPCODE_NAMES[87]  = "pop";
        OPCODE_NAMES[88]  = "pop2";
        OPCODE_NAMES[89]  = "dup";
        OPCODE_NAMES[90]  = "dup_x1";
        OPCODE_NAMES[91]  = "dup_x2";
        OPCODE_NAMES[92]  = "dup2";
        OPCODE_NAMES[93]  = "dup2_x1";
        OPCODE_NAMES[94]  = "dup2_x2";
        OPCODE_NAMES[95]  = "swap";
        OPCODE_NAMES[96]  = "iadd";
        OPCODE_NAMES[97]  = "ladd";
        OPCODE_NAMES[98]  = "fadd";
        OPCODE_NAMES[99]  = "dadd";
        OPCODE_NAMES[100] = "isub";
        OPCODE_NAMES[101] = "lsub";
        OPCODE_NAMES[102] = "fsub";
        OPCODE_NAMES[103] = "dsub";
        OPCODE_NAMES[104] = "imul";
        OPCODE_NAMES[105] = "lmul";
        OPCODE_NAMES[106] = "fmul";
        OPCODE_NAMES[107] = "dmul";
        OPCODE_NAMES[108] = "idiv";
        OPCODE_NAMES[109] = "ldiv";
        OPCODE_NAMES[110] = "fdiv";
        OPCODE_NAMES[111] = "ddiv";
        OPCODE_NAMES[112] = "irem";
        OPCODE_NAMES[113] = "lrem";
        OPCODE_NAMES[114] = "frem";
        OPCODE_NAMES[115] = "drem";
        OPCODE_NAMES[116] = "ineg";
        OPCODE_NAMES[117] = "lneg";
        OPCODE_NAMES[118] = "fneg";
        OPCODE_NAMES[119] = "dneg";
        OPCODE_NAMES[120] = "ishl";
        OPCODE_NAMES[121] = "lshl";
        OPCODE_NAMES[122] = "ishr";
        OPCODE_NAMES[123] = "lshr";
        OPCODE_NAMES[124] = "iushr";
        OPCODE_NAMES[125] = "lushr";
        OPCODE_NAMES[126] = "iand";
        OPCODE_NAMES[127] = "land";
        OPCODE_NAMES[128] = "ior";
        OPCODE_NAMES[129] = "lor";
        OPCODE_NAMES[130] = "ixor";
        OPCODE_NAMES[131] = "lxor";
        OPCODE_NAMES[132] = "iinc";
        OPCODE_NAMES[133] = "i2l";
        OPCODE_NAMES[134] = "i2f";
        OPCODE_NAMES[135] = "i2d";
        OPCODE_NAMES[136] = "l2i";
        OPCODE_NAMES[137] = "l2f";
        OPCODE_NAMES[138] = "l2d";
        OPCODE_NAMES[139] = "f2i";
        OPCODE_NAMES[140] = "f2l";
        OPCODE_NAMES[141] = "f2d";
        OPCODE_NAMES[142] = "d2i";
        OPCODE_NAMES[143] = "d2l";
        OPCODE_NAMES[144] = "d2f";
        OPCODE_NAMES[145] = "i2b";
        OPCODE_NAMES[146] = "i2c";
        OPCODE_NAMES[147] = "i2s";
        OPCODE_NAMES[148] = "lcmp";
        OPCODE_NAMES[149] = "fcmpl";
        OPCODE_NAMES[150] = "fcmpg";
        OPCODE_NAMES[151] = "dcmpl";
        OPCODE_NAMES[152] = "dcmpg";
        OPCODE_NAMES[153] = "ifeq";
        OPCODE_NAMES[154] = "ifne";
        OPCODE_NAMES[155] = "iflt";
        OPCODE_NAMES[156] = "ifge";
        OPCODE_NAMES[157] = "ifgt";
        OPCODE_NAMES[158] = "ifle";
        OPCODE_NAMES[159] = "if_icmpeq";
        OPCODE_NAMES[160] = "if_icmpne";
        OPCODE_NAMES[161] = "if_icmplt";
        OPCODE_NAMES[162] = "if_icmpge";
        OPCODE_NAMES[163] = "if_icmpgt";
        OPCODE_NAMES[164] = "if_icmple";
        OPCODE_NAMES[165] = "if_acmpeq";
        OPCODE_NAMES[166] = "if_acmpne";
        OPCODE_NAMES[167] = "goto";
        OPCODE_NAMES[168] = "jsr";
        OPCODE_NAMES[169] = "ret";
        OPCODE_NAMES[170] = "tableswitch";
        OPCODE_NAMES[171] = "lookupswitch";
        OPCODE_NAMES[172] = "ireturn";
        OPCODE_NAMES[173] = "lreturn";
        OPCODE_NAMES[174] = "freturn";
        OPCODE_NAMES[175] = "dreturn";
        OPCODE_NAMES[176] = "areturn";
        OPCODE_NAMES[177] = "return";
        OPCODE_NAMES[178] = "getstatic";
        OPCODE_NAMES[179] = "putstatic";
        OPCODE_NAMES[180] = "getfield";
        OPCODE_NAMES[181] = "putfield";
        OPCODE_NAMES[182] = "invokevirtual";
        OPCODE_NAMES[183] = "invokespecial";
        OPCODE_NAMES[184] = "invokestatic";
        OPCODE_NAMES[185] = "invokeinterface";
        OPCODE_NAMES[186] = "invokedynamic";
        OPCODE_NAMES[187] = "new";
        OPCODE_NAMES[188] = "newarray";
        OPCODE_NAMES[189] = "anewarray";
        OPCODE_NAMES[190] = "arraylength";
        OPCODE_NAMES[191] = "athrow";
        OPCODE_NAMES[192] = "checkcast";
        OPCODE_NAMES[193] = "instanceof";
        OPCODE_NAMES[194] = "monitorenter";
        OPCODE_NAMES[195] = "monitorexit";
        OPCODE_NAMES[196] = "wide";
        OPCODE_NAMES[197] = "multianewarray";
        OPCODE_NAMES[198] = "ifnull";
        OPCODE_NAMES[199] = "ifnonnull";
        OPCODE_NAMES[200] = "goto_w";
        OPCODE_NAMES[201] = "jsr_w";
    }

    // ---- Safe label offset helper ----

    static int labelOffset(Label label) {
        try {
            return label.getOffset();
        } catch (IllegalStateException e) {
            // Label not yet resolved (e.g. forward reference during EXPAND_FRAMES)
            return -1;
        }
    }

    static Element simpleInsn(int opcode) {
        String name = (opcode >= 0 && opcode < OPCODE_NAMES.length
                       && OPCODE_NAMES[opcode] != null)
                      ? OPCODE_NAMES[opcode] : "opcode_" + opcode;
        Element e = new Element(name);
        e.trimToSize();
        return e;
    }

    // ---- StackMapTable verification type helpers ----

    static int verificationTypeTag(Object vtype) {
        if (vtype instanceof Integer) {
            return (Integer) vtype;
        }
        if (vtype instanceof String) {
            return 7; // ITEM_Object
        }
        if (vtype instanceof Label) {
            return 8; // ITEM_Uninitialized
        }
        return 0;
    }

    static Element verificationTypeElement(Object vtype) {
        if (vtype instanceof Integer) {
            int tag = (Integer) vtype;
            switch (tag) {
                case 0: return new Element("ITEM_Top");
                case 1: return new Element("ITEM_Integer");
                case 2: return new Element("ITEM_Float");
                case 3: return new Element("ITEM_Double");
                case 4: return new Element("ITEM_Long");
                case 5: return new Element("ITEM_Null");
                case 6: return new Element("ITEM_UnitializedtThis"); // typo preserved from original
                default: return new Element("Unknown");
            }
        }
        if (vtype instanceof String) {
            Element e = new Element("ITEM_Object");
            e.setAttr("class", (String) vtype);
            return e;
        }
        if (vtype instanceof Label) {
            Element e = new Element("ITEM_Uninitialized");
            e.setAttr("offset", "" + labelOffset((Label) vtype));
            return e;
        }
        return new Element("Unknown");
    }
}
