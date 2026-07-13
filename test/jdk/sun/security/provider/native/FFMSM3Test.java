/*
 * Copyright (C) 2026, Tencent. All rights reserved.
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
 */

/*
 * @test
 * @summary SM3 FFM engine correctness tests
 * @library /test/lib /test/jdk/openssl
 * @run junit/othervm -Djdk.sun.enableFFMCrypto=true
 *                     --enable-native-access=java.base
 *                     FFMSM3Test
 */

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@RequireFFMCrypto
public class FFMSM3Test {

    private static final String[] KAT_MESSAGES = {
        "",
        "abc",
        "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
    };
    private static final String[] KAT_DIGESTS = {
        "1ab21d8355cfa17f8e61194831e81a8f22bec8c728fefb747ed035eb5082aa2b",
        "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0",
        "debe9ff92275b8a138604889c18e5a4d6fdb70e5387e5765293dcba39c0c5732"
    };

    /**
     * Verify the FFM engine is active in this test run.
     */
    @BeforeEach
    public void checkEngine() throws Exception {
        boolean ffmAvailable = false;
        try {
            Class<?> cls = Class.forName(
                    "sun.security.provider.SM3EngineFFM");
            ffmAvailable = (boolean) cls.getDeclaredMethod("isAvailable")
                    .invoke(null);
        } catch (Exception ignored) {
        }
        Assertions.assertTrue(ffmAvailable,
                "FFM SM3 engine should be available during this test");
    }

    /**
     * Known-answer tests from GB/T 32905-2016.
     */
    @Test
    public void testKatVectors() throws Exception {
        for (int i = 0; i < KAT_MESSAGES.length; i++) {
            MessageDigest md = MessageDigest.getInstance("SM3");
            byte[] digest = md.digest(
                    KAT_MESSAGES[i].getBytes("UTF-8"));
            String hex = bytesToHex(digest);
            Assertions.assertEquals(KAT_DIGESTS[i], hex,
                    "KAT failed for message: '" + KAT_MESSAGES[i] + "'");
        }
    }

    /**
     * Single-byte incremental update: each byte fed via update(byte),
     * result must match single-shot digest.
     */
    @Test
    public void testByteByByteUpdate() throws Exception {
        byte[] message = new byte[1024];
        for (int i = 0; i < message.length; i++) {
            message[i] = (byte) (i & 0xFF);
        }

        MessageDigest mdOneShot = MessageDigest.getInstance("SM3");
        byte[] expected = mdOneShot.digest(message);

        MessageDigest mdByte = MessageDigest.getInstance("SM3");
        for (byte b : message) {
            mdByte.update(b);
        }
        byte[] actual = mdByte.digest();

        Assertions.assertArrayEquals(expected, actual,
                "Byte-by-byte update mismatch");
    }

    /**
     * Chunked update: feed in 64-byte blocks, result must match
     * single-shot digest.
     */
    @Test
    public void testChunkedUpdate() throws Exception {
        byte[] message = new byte[1024];
        for (int i = 0; i < message.length; i++) {
            message[i] = (byte) (i & 0xFF);
        }

        MessageDigest mdOneShot = MessageDigest.getInstance("SM3");
        byte[] expected = mdOneShot.digest(message);

        MessageDigest mdChunk = MessageDigest.getInstance("SM3");
        for (int off = 0; off < message.length; off += 64) {
            int len = Math.min(64, message.length - off);
            mdChunk.update(message, off, len);
        }
        byte[] actual = mdChunk.digest();

        Assertions.assertArrayEquals(expected, actual,
                "Chunked update mismatch");
    }

    /**
     * Clone correctness: cloned digest produces same result as original.
     */
    @Test
    public void testClone() throws Exception {
        byte[] message = new byte[256];
        for (int i = 0; i < message.length; i++) {
            message[i] = (byte) (i & 0xFF);
        }

        MessageDigest md = MessageDigest.getInstance("SM3");
        md.update(message, 0, 128);

        MessageDigest mdClone = (MessageDigest) md.clone();
        md.update(message, 128, 128);
        mdClone.update(message, 128, 128);

        byte[] d1 = md.digest();
        byte[] d2 = mdClone.digest();

        Assertions.assertArrayEquals(d1, d2,
                "Clone digest mismatch");
    }

    /**
     * Reset/reuse: after digest(), the engine is reset and ready for
     * the next message.
     */
    @Test
    public void testReuse() throws Exception {
        MessageDigest md = MessageDigest.getInstance("SM3");
        byte[] msg1 = "hello".getBytes("UTF-8");
        byte[] msg2 = "world".getBytes("UTF-8");

        byte[] d1a = md.digest(msg1);
        byte[] d1b = md.digest(msg1);
        Assertions.assertArrayEquals(d1a, d1b,
                "Reuse same message mismatch");

        byte[] d2a = md.digest(msg2);
        byte[] d2b = md.digest(msg2);
        Assertions.assertArrayEquals(d2a, d2b,
                "Reuse different message mismatch");
    }

    /**
     * Explicit reset mid-stream: after reset, previous updates are
     * discarded.
     */
    @Test
    public void testReset() throws Exception {
        byte[] message = new byte[512];
        new java.util.Random(42).nextBytes(message);

        MessageDigest md = MessageDigest.getInstance("SM3");
        byte[] expected = md.digest(message);

        md.update(message, 0, 256);
        md.reset();
        md.update(message);
        byte[] actual = md.digest();

        Assertions.assertArrayEquals(expected, actual,
                "Reset mismatch");
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
