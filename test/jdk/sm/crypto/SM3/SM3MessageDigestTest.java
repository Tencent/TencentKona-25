/*
 * Copyright (C) 2022, 2024, Tencent. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2 as
 * published by the Free Software Foundation. Tencent designates
 * this particular file as subject to the "Classpath" exception as provided
 * in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License version 2 for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

/*
 * @test
 * @summary Test SM3 message digest.
 * @compile ../../Utils.java
 * @run junit SM3MessageDigestTest
 */

import java.nio.ByteBuffer;
import java.security.DigestException;
import java.security.MessageDigest;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class SM3MessageDigestTest {

    private static final byte[] MESSAGE_SHORT = Utils.hexToBytes("616263");
    private static final byte[] DIGEST_SHORT = Utils.hexToBytes(
            "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0");

    private static final byte[] MESSAGE_LONG = Utils.hexToBytes(
            "61626364616263646162636461626364616263646162636461626364616263646162636461626364616263646162636461626364616263646162636461626364");
    private static final byte[] DIGEST_LONG = Utils.hexToBytes(
            "debe9ff92275b8a138604889c18e5a4d6fdb70e5387e5765293dcba39c0c5732");

    private static final int[] LENGTH_MATRIX = new int[] {
            0, 1, 3, 55, 56, 63, 64, 65, 127, 128, 129, 1024, 16384
    };

    @Test
    public void testKAT() throws Exception {
        checkDigest(MESSAGE_SHORT, DIGEST_SHORT);
        checkDigest(MESSAGE_LONG, DIGEST_LONG);
    }

    private static void checkDigest(byte[] message, byte[] expectedDigest)
            throws Exception {
        MessageDigest md = MessageDigest.getInstance("SM3");
        byte[] digest = md.digest(message);
        assertArrayEquals(expectedDigest, digest);
    }

    @Test
    public void testGetDigestLength() throws Exception {
        MessageDigest md = MessageDigest.getInstance("SM3");
        int digestLength = md.getDigestLength();
        assertEquals(32, digestLength);
    }

    @Test
    public void testUpdateByte() throws Exception {
        MessageDigest md = MessageDigest.getInstance("SM3");

        for (byte b : MESSAGE_SHORT) {
            md.update(b);
        }
        byte[] digest= md.digest();

        assertArrayEquals(DIGEST_SHORT, digest);
    }

    @Test
    public void testUpdateBytes() throws Exception {
        MessageDigest md = MessageDigest.getInstance("SM3");

        md.update(MESSAGE_SHORT, 0, MESSAGE_SHORT.length / 2);
        md.update(MESSAGE_SHORT, MESSAGE_SHORT.length / 2,
                MESSAGE_SHORT.length - MESSAGE_SHORT.length / 2);
        byte[] digest = md.digest();

        assertArrayEquals(DIGEST_SHORT, digest);
    }

    @Test
    public void testOutputBuf() throws Exception {
        MessageDigest md = MessageDigest.getInstance("SM3");
        md.update(MESSAGE_SHORT);
        byte[] out = new byte[32];
        int length = md.digest(out, 0, out.length);

        assertEquals(32, length);
        assertArrayEquals(DIGEST_SHORT, out);
    }

    @Test
    public void testNullBytes() throws Exception {
        MessageDigest md = MessageDigest.getInstance("SM3");
        assertThrows(
                NullPointerException.class,
                () -> md.update((byte[]) null));
    }

    @Test
    public void testByteBuffer() throws Exception {
        MessageDigest md = MessageDigest.getInstance("SM3");
        md.update(ByteBuffer.wrap(MESSAGE_SHORT));
        byte[] digest = md.digest();
        assertArrayEquals(DIGEST_SHORT, digest);
    }

    @Test
    public void testNullByteBuffer() throws Exception {
        MessageDigest md = MessageDigest.getInstance("SM3");
        assertThrows(
                NullPointerException.class,
                () -> md.update((ByteBuffer) null));
    }

    @Test
    public void testEmptyInput() throws Exception {
        MessageDigest md1 = MessageDigest.getInstance("SM3");
        md1.update(new byte[0]);
        byte[] digest1 = md1.digest();

        MessageDigest md2 = MessageDigest.getInstance("SM3");
        md2.update(ByteBuffer.wrap(new byte[0]));
        byte[] digest2 = md2.digest();

        assertArrayEquals(digest1, digest2);
    }

    @Test
    public void testNoInput() throws Exception {
        MessageDigest md1 = MessageDigest.getInstance("SM3");
        byte[] digest1 = md1.digest();

        MessageDigest md2 = MessageDigest.getInstance("SM3");
        md2.update(new byte[0]);
        byte[] digest2 = md2.digest();

        assertArrayEquals(digest1, digest2);
    }

    @Test
    public void testReuse() throws Exception {
        MessageDigest md = MessageDigest.getInstance("SM3");

        byte[] digestShort = md.digest(MESSAGE_SHORT);
        assertArrayEquals(DIGEST_SHORT, digestShort);

        byte[] digestLong = md.digest(MESSAGE_LONG);
        assertArrayEquals(DIGEST_LONG, digestLong);
    }

    @Test
    public void testReset() throws Exception {
        MessageDigest md = MessageDigest.getInstance("SM3");

        md.update(MESSAGE_SHORT, 0, MESSAGE_SHORT.length / 2);
        md.reset();
        md.update(MESSAGE_SHORT, 0, MESSAGE_SHORT.length / 2);
        md.update(MESSAGE_SHORT, MESSAGE_SHORT.length / 2,
                MESSAGE_SHORT.length - MESSAGE_SHORT.length / 2);
        byte[] digest = md.digest();

        assertArrayEquals(DIGEST_SHORT, digest);
    }

    @Test
    public void testOutOfBoundsOnUpdate() throws Exception {
        outOfBoundsOnUpdate(16, 0, 32);
        outOfBoundsOnUpdate(7, 0, 32);
        outOfBoundsOnUpdate(16, -8, 16);
        outOfBoundsOnUpdate(16, 8, -8);
        outOfBoundsOnUpdate(16, Integer.MAX_VALUE, 8);
    }

    private static void outOfBoundsOnUpdate(int inputSize, int ofs, int len)
            throws Exception {
        MessageDigest md = MessageDigest.getInstance("SM3");

        try {
            md.update(new byte[inputSize], ofs, len);
            throw new Exception("invalid call succeeded");
        } catch (ArrayIndexOutOfBoundsException | IllegalArgumentException e) {
            System.out.println("Expected: " + e);
        }
    }

    @Test
    public void testOutOfBoundsOnOutBuf() throws Exception {
        outOfBoundsOnOutBuf(16, 0, 32);
        outOfBoundsOnOutBuf(32, 0, 31);
        outOfBoundsOnOutBuf(32, -1, 32);
        outOfBoundsOnOutBuf(32, Integer.MAX_VALUE, 32);
    }

    private static void outOfBoundsOnOutBuf(int outSize, int ofs, int len)
            throws Exception {
        MessageDigest md = MessageDigest.getInstance("SM3");
        md.update(MESSAGE_SHORT);

        try {
            md.digest(new byte[outSize], ofs, len);
            throw new Exception("invalid call succeeded");
        } catch (IllegalArgumentException | DigestException
                 | ArrayIndexOutOfBoundsException e) {
            System.out.println("Expected: " + e);
        }
    }

    @Test
    public void testOffset() throws Exception {
        offset(0, 64, 0, 128);
        offset(0, 64, 0, 129);
        offset(1, 32, 1, 129);
    }

    private static void offset(
            int minOffset, int maxOffset, int minLen, int maxLen)
            throws Exception {
        MessageDigest md = MessageDigest.getInstance("SM3");

        Random random = new Random();
        for (int len = minLen; len <= maxLen; len++) {
            byte[] data = new byte[len];
            random.nextBytes(data);
            byte[] digest = null;
            for (int offset = minOffset; offset <= maxOffset; offset++) {
                byte[] offsetData = new byte[len + maxOffset];
                random.nextBytes(offsetData);
                System.arraycopy(data, 0, offsetData, offset, len);
                md.update(offsetData, offset, len);
                byte[] offsetDigest = md.digest();
                if (digest == null) {
                    digest = offsetDigest;
                } else {
                    assertArrayEquals(digest, offsetDigest);
                }
            }
        }
    }

    @Test
    public void testClone() throws Exception {
        MessageDigest md = MessageDigest.getInstance("SM3");
        md.update(MESSAGE_LONG, 0, MESSAGE_LONG.length / 3);
        md.update(MESSAGE_LONG[MESSAGE_LONG.length / 3]);

        MessageDigest clone = (MessageDigest) md.clone();
        clone.update(MESSAGE_LONG, MESSAGE_LONG.length / 3 + 1,
                MESSAGE_LONG.length - MESSAGE_LONG.length / 3 - 2);
        clone.update(MESSAGE_LONG[MESSAGE_LONG.length - 1]);
        byte[] digest = clone.digest();

        assertArrayEquals(DIGEST_LONG, digest);
    }

    /**
     * Cover one-shot, byte-by-byte, 64-byte chunked update and a
     * heap {@link ByteBuffer} for every length in this matrix. The
     * FFM-backed and JNI-backed native engines must yield the same
     * 32-byte digest as the pure Java implementation.
     */
    @Test
    public void testUpdateMatrix() throws Exception {
        Random random = new Random(1234567890L);
        for (int length : LENGTH_MATRIX) {
            byte[] data = new byte[length];
            random.nextBytes(data);
            byte[] expected = MessageDigest.getInstance("SM3").digest(data);

            assertArrayEquals(expected, digestOneShot(data),
                    "one-shot mismatch at length " + length);
            assertArrayEquals(expected, digestByteByByte(data),
                    "byte-by-byte mismatch at length " + length);
            assertArrayEquals(expected, digestByChunks(data, 64),
                    "64-byte chunked mismatch at length " + length);
            assertArrayEquals(expected, digestByteBuffer(data),
                    "ByteBuffer mismatch at length " + length);
        }
    }

    /**
     * Verifies that updating the digest from a clone in the middle
     * of a 64-byte block produces the same digest as a single-shot
     * update on the original data.
     */
    @Test
    public void testCloneWithinBlock() throws Exception {
        Random random = new Random(0xC0FFEE);
        for (int length : new int[] { 0, 1, 31, 32, 63, 64, 65, 127 }) {
            byte[] data = new byte[length];
            random.nextBytes(data);
            int split = length == 0 ? 0 : length / 3;

            MessageDigest original = MessageDigest.getInstance("SM3");
            original.update(data, 0, split);
            MessageDigest clone = (MessageDigest) original.clone();
            clone.update(data, split, length - split);
            byte[] cloneDigest = clone.digest();

            assertArrayEquals(MessageDigest.getInstance("SM3").digest(data),
                    cloneDigest,
                    "clone-within-block mismatch at length " + length);
        }
    }

    private static byte[] digestOneShot(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SM3");
        return md.digest(data);
    }

    private static byte[] digestByteByByte(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SM3");
        for (byte b : data) {
            md.update(b);
        }
        return md.digest();
    }

    private static byte[] digestByChunks(byte[] data, int chunk) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SM3");
        for (int i = 0; i < data.length; i += chunk) {
            md.update(data, i, Math.min(chunk, data.length - i));
        }
        return md.digest();
    }

    private static byte[] digestByteBuffer(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SM3");
        md.update(ByteBuffer.wrap(data));
        return md.digest();
    }
}
