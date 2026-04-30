/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
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
package jdk.test.lib.security;

import jdk.test.lib.Asserts;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;

/**
 * Utility class for parsing DER-encoded data in tests.
 *
 * <p>DER (Distinguished Encoding Rules) is a subset of BER (Basic Encoding
 * Rules) used in cryptographic standards. This class provides a minimal pure
 * Java DER parser sufficient for test use, replacing internal
 * {@code sun.security.util} APIs.
 */
public class DerUtils {

    // DER tag constants
    private static final int TAG_INTEGER       = 0x02;
    private static final int TAG_OCTET_STRING  = 0x04;
    private static final int TAG_OID           = 0x06;
    private static final int TAG_SEQUENCE      = 0x30;

    /**
     * Represents a single DER-encoded value, providing accessors for the
     * most common types needed in tests.
     */
    public static final class DerNode {
        private final int tag;
        private final byte[] content;

        private DerNode(int tag, byte[] content) {
            this.tag = tag;
            this.content = content;
        }

        /**
         * Returns the raw content bytes of this DER value (without tag/length).
         */
        public byte[] getContent() {
            return Arrays.copyOf(content, content.length);
        }

        /**
         * Interprets the content as an OCTET STRING and returns the raw bytes.
         *
         * @throws IOException if the tag is not OCTET STRING
         */
        public byte[] getOctetString() throws IOException {
            checkTag(TAG_OCTET_STRING);
            return Arrays.copyOf(content, content.length);
        }

        /**
         * Interprets the content as an INTEGER and returns the value.
         *
         * @throws IOException if the tag is not INTEGER
         */
        public int getInteger() throws IOException {
            checkTag(TAG_INTEGER);
            int value = 0;
            for (byte b : content) {
                value = (value << 8) | (b & 0xFF);
            }
            return value;
        }

        /**
         * Interprets the content as an OBJECT IDENTIFIER and returns it in
         * dotted-decimal notation (e.g., {@code "1.2.840.113549.1.7.1"}).
         *
         * @throws IOException if the tag is not OID
         */
        public String getOID() throws IOException {
            checkTag(TAG_OID);
            return decodeOid(content);
        }

        private void checkTag(int expected) throws IOException {
            if (tag != expected) {
                throw new IOException(String.format(
                        "DER tag mismatch: expected 0x%02X, got 0x%02X",
                        expected, tag));
            }
        }
    }

    /**
     * Returns a {@link DerNode} (deep) inside another DER-encoded value.
     *
     * <p>The location of the inner value is expressed as a string where each
     * character describes one navigation step:
     * <ul>
     *   <li>A digit {@code n} (0–9) selects the n-th element (0-based) of the
     *       current SEQUENCE or constructed value.</li>
     *   <li>{@code 'c'} re-parses the content of the current OCTET STRING as
     *       a new DER value.</li>
     * </ul>
     *
     * <p>For example, for a PKCS #12 file structured as:
     * <pre>
     * [] SEQUENCE
     *   [0]  INTEGER 3
     *   [1]  SEQUENCE
     *     [10] OID 1.2.840.113549.1.7.1
     *     [11] cont [0]
     *       [110] OCTET STRING (content is another DER SEQUENCE)
     * </pre>
     * the OID nested inside is reachable via {@code innerDerValue(data, "110c00")}.
     *
     * @param data     the outer DER-encoded byte array
     * @param location navigation string as described above
     * @return the inner {@link DerNode}, or {@code null} if no value exists
     *         at the specified location
     * @throws IOException if an I/O error occurs during parsing
     */
    public static DerNode innerDerValue(byte[] data, String location)
            throws IOException {
        DerNode v = parseDerValue(data, 0);
        for (char step : location.toCharArray()) {
            if (v == null) {
                return null;
            }
            if (step == 'c') {
                byte[] octet = v.getOctetString();
                v = parseDerValue(octet, 0);
            } else {
                int index = step - '0';
                v = getSequenceElement(v.content, index);
            }
        }
        return v;
    }

    /**
     * Ensures that the inner DerNode contains the expected OID string.
     *
     * @param der      the DER-encoded byte array
     * @param location navigation string (see {@link #innerDerValue})
     * @param expected the expected OID in dotted-decimal notation
     */
    public static void checkAlg(byte[] der, String location,
            String expected) throws Exception {
        Asserts.assertEQ(innerDerValue(der, location).getOID(), expected);
    }

    /**
     * Ensures that the inner DerNode contains the expected integer value.
     *
     * @param der      the DER-encoded byte array
     * @param location navigation string (see {@link #innerDerValue})
     * @param expected the expected integer value
     */
    public static void checkInt(byte[] der, String location, int expected)
            throws Exception {
        Asserts.assertEQ(innerDerValue(der, location).getInteger(), expected);
    }

    /**
     * Ensures that there is no inner DerNode at the specified location.
     *
     * @param der      the DER-encoded byte array
     * @param location navigation string (see {@link #innerDerValue})
     */
    public static void shouldNotExist(byte[] der, String location)
            throws Exception {
        Asserts.assertTrue(innerDerValue(der, location) == null);
    }

    // -------------------------------------------------------------------------
    // Internal parsing helpers
    // -------------------------------------------------------------------------

    /**
     * Parses one DER TLV (tag-length-value) record starting at {@code offset}
     * within {@code data}.
     *
     * @return the parsed {@link DerNode}
     * @throws IOException on malformed data or buffer overrun
     */
    private static DerNode parseDerValue(byte[] data, int offset)
            throws IOException {
        if (offset >= data.length) {
            throw new IOException("DER data exhausted at offset " + offset);
        }
        int tag = data[offset++] & 0xFF;

        // Decode length
        int lengthByte = data[offset++] & 0xFF;
        int length;
        if ((lengthByte & 0x80) == 0) {
            length = lengthByte;
        } else {
            int numBytes = lengthByte & 0x7F;
            if (numBytes == 0 || numBytes > 4) {
                throw new IOException("Unsupported DER length encoding");
            }
            length = 0;
            for (int i = 0; i < numBytes; i++) {
                length = (length << 8) | (data[offset++] & 0xFF);
            }
        }

        if (offset + length > data.length) {
            throw new IOException("DER value extends beyond data buffer");
        }
        byte[] content = Arrays.copyOfRange(data, offset, offset + length);
        return new DerNode(tag, content);
    }

    /**
     * Returns the n-th element (0-based) from the contents of a constructed
     * DER value (e.g., a SEQUENCE), or {@code null} if there are fewer than
     * {@code n+1} elements.
     *
     * @param sequenceContent the raw content bytes of the constructed value
     * @param index           0-based index of the desired element
     * @return the element at {@code index}, or {@code null} if out of range
     * @throws IOException on malformed data
     */
    private static DerNode getSequenceElement(byte[] sequenceContent, int index)
            throws IOException {
        int pos = 0;
        int count = 0;
        while (pos < sequenceContent.length) {
            int startPos = pos;
            int tag = sequenceContent[pos++] & 0xFF;

            // Decode length
            int lengthByte = sequenceContent[pos++] & 0xFF;
            int length;
            if ((lengthByte & 0x80) == 0) {
                length = lengthByte;
            } else {
                int numBytes = lengthByte & 0x7F;
                if (numBytes == 0 || numBytes > 4) {
                    throw new IOException("Unsupported DER length encoding");
                }
                length = 0;
                for (int i = 0; i < numBytes; i++) {
                    length = (length << 8) | (sequenceContent[pos++] & 0xFF);
                }
            }

            if (count == index) {
                byte[] content = Arrays.copyOfRange(sequenceContent, pos, pos + length);
                return new DerNode(tag, content);
            }
            pos += length;
            count++;
        }
        return null;
    }

    /**
     * Decodes a DER-encoded OID content (without the tag/length prefix) into
     * its dotted-decimal string representation.
     *
     * @param oidContent raw content bytes of the OID value
     * @return dotted-decimal OID string (e.g., {@code "1.2.840.113549.1.7.1"})
     * @throws IOException on malformed OID data
     */
    private static String decodeOid(byte[] oidContent) throws IOException {
        if (oidContent.length == 0) {
            throw new IOException("Empty OID content");
        }
        StringBuilder sb = new StringBuilder();
        int firstByte = oidContent[0] & 0xFF;
        sb.append(firstByte / 40).append('.').append(firstByte % 40);

        int i = 1;
        while (i < oidContent.length) {
            long value = 0;
            int b;
            do {
                if (i >= oidContent.length) {
                    throw new IOException("Truncated OID component");
                }
                b = oidContent[i++] & 0xFF;
                value = (value << 7) | (b & 0x7F);
            } while ((b & 0x80) != 0);
            sb.append('.').append(value);
        }
        return sb.toString();
    }
}
