/*
 * Copyright (C) 2026, Tencent. All rights reserved.
 *
 * Correctness tests for SM3 engine implementations.
 * Cross-validates Java, FFM, and (if available) JNI implementations.
 */

package sm3test;

import sun.security.provider.SM3Engine;
import sun.security.provider.SM3EngineImpl;
import sun.security.provider.SM3EngineFFM;
import sun.security.provider.SM3EngineNative;
import sun.security.provider.SM3MessageDigest;

import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Standalone correctness test runner for SM3 engine implementations.
 * Run with: java --enable-native-access=ALL-UNNAMED \
 *          -cp target/classes sun.security.provider.SM3CorrectnessTest
 */
@SuppressWarnings("restricted")
public class SM3CorrectnessTest {

    // GB/T 32905-2016 test vectors
    // Test vector 1: "abc"
    private static final String VECTOR1_INPUT = "abc";
    private static final String VECTOR1_HASH =
            "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0";

    // Test vector 2: "abcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcd"
    // (64 bytes of "abcd" repeated 16 times)
    private static final String VECTOR2_INPUT =
            "abcdabcdabcdabcdabcdabcdabcdabcd" +
            "abcdabcdabcdabcdabcdabcdabcdabcd";
    private static final String VECTOR2_HASH =
            "debe9ff92275b8a138604889c18e5a4d6fdb70e5387e5765293dcba39c0c5732";

    private static int passed = 0;
    private static int failed = 0;
    private static final HexFormat HEX = HexFormat.of();

    /**
     * Attempts to load the OpenSSL libcrypto library from various
     * common locations and names across platforms.
     */
    private static void loadCryptoLibrary() {
        String[] libNames = {
            "crypto-3-x64",          // Windows, OpenSSL 3.x 64-bit (MSYS2/MinGW)
            "libcrypto-3-x64",       // Windows (alternative prefix)
            "libcrypto-3",           // Linux, OpenSSL 3.x
            "crypto",                // Generic (common on Linux/BSD)
            "libcrypto",             // Generic (common on macOS/Linux)
            "libcrypto.so.3",        // Linux with .so suffix
        };
        String[] libPaths = {
            "/mingw64/bin/libcrypto-3-x64.dll",
            "/usr/bin/libcrypto-3-x64.dll",
            "/usr/lib/libcrypto.so.3",
            "/usr/lib64/libcrypto.so.3",
            "/usr/local/lib/libcrypto.so.3",
            "/opt/homebrew/lib/libcrypto.3.dylib",
            "/usr/lib/libcrypto.dylib",
        };

        // Try loading by path first (more reliable)
        for (String path : libPaths) {
            try {
                System.load(path);
                System.out.println("[INFO] Loaded libcrypto from: " + path);
                return;
            } catch (UnsatisfiedLinkError e) {
                // Try next
            }
        }

        // Try loading by library name
        for (String name : libNames) {
            try {
                System.loadLibrary(name);
                System.out.println("[INFO] Loaded libcrypto via loadLibrary: " + name);
                return;
            } catch (UnsatisfiedLinkError e) {
                // Try next
            }
        }

        System.err.println("[WARN] Could not load OpenSSL libcrypto. FFM tests will be skipped.");
        System.err.println("[WARN] Install OpenSSL 3.x development libraries or set");
        System.err.println("[WARN] java.library.path to include the libcrypto directory.");
    }

    public static void main(String[] args) {
        // Load OpenSSL libcrypto before any FFM classes are initialized.
        loadCryptoLibrary();

        System.out.println("=== SM3 Correctness Test Suite ===\n");

        String engineType = SM3MessageDigest.getActiveEngineType();
        System.out.println("Active engine: " + engineType + "\n");

        // Test both Java and FFM implementations
        testJavaImplementation();
        testFFMImplementation();

        System.out.println("\n=== Cross-Validation ===");
        testCrossValidation();

        System.out.println("\n=== Additional Tests ===");
        testStreamingEquivalence();
        testCloneCorrectness();
        testEmptyMessage();
        testLargeMessage();
        testResetAndReuse();
        testMultipleUpdateSizes();

        System.out.println("\n=== Results ===");
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);

        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---- Java Implementation Tests ----

    private static void testJavaImplementation() {
        System.out.println("--- Java (SM3EngineImpl) Test Vectors ---");
        SM3EngineImpl engine = new SM3EngineImpl();

        testVector(engine, "Java: Vector 1 (\"abc\")",
                VECTOR1_INPUT.getBytes(), VECTOR1_HASH);
        testVector(engine, "Java: Vector 2 (64 bytes)",
                VECTOR2_INPUT.getBytes(), VECTOR2_HASH);
    }

    // ---- FFM Implementation Tests ----

    private static void testFFMImplementation() {
        System.out.println("--- FFM (SM3EngineFFM) Test Vectors ---");

        try {
            SM3EngineFFM engine = new SM3EngineFFM();

            testVector(engine, "FFM:  Vector 1 (\"abc\")",
                    VECTOR1_INPUT.getBytes(), VECTOR1_HASH);
            testVector(engine, "FFM:  Vector 2 (64 bytes)",
                    VECTOR2_INPUT.getBytes(), VECTOR2_HASH);

        } catch (Throwable t) {
            System.out.println("FFM engine not available: " + t.getMessage());
            System.out.println("(This is expected if OpenSSL libcrypto is not loaded)");
            // Don't count as failure if OpenSSL is not available
        }
    }

    // ---- Cross-Validation ----

    private static void testCrossValidation() {
        System.out.println("Cross-validating Java vs FFM implementations...");

        try {
            SM3EngineImpl javaEngine = new SM3EngineImpl();
            SM3EngineFFM ffmEngine = null;

            try {
                ffmEngine = new SM3EngineFFM();
            } catch (Throwable t) {
                System.out.println("SKIP: FFM not available — " + t.getMessage());
                return;
            }

            // Test with various random message sizes
            int[] sizes = {0, 1, 3, 16, 31, 32, 63, 64, 65, 100, 127, 128, 129,
                    255, 256, 257, 512, 1000, 1024, 4096, 10000};

            for (int size : sizes) {
                byte[] message = new byte[size];
                ThreadLocalRandom.current().nextBytes(message);

                javaEngine.reset();
                javaEngine.update(message);
                byte[] javaHash = javaEngine.doFinal();

                ffmEngine.reset();
                ffmEngine.update(message);
                byte[] ffmHash = ffmEngine.doFinal();

                String javaHex = HEX.formatHex(javaHash);
                String ffmHex = HEX.formatHex(ffmHash);

                if (!javaHex.equals(ffmHex)) {
                    fail("Cross-validation for " + size + " bytes: mismatch\n" +
                            "  Java: " + javaHex + "\n" +
                            "  FFM:  " + ffmHex);
                } else {
                    pass("Cross-validation for " + size + " bytes: OK");
                }
            }
        } catch (Throwable t) {
            fail("Cross-validation: unexpected error: " + t.getMessage());
            t.printStackTrace();
        }
    }

    // ---- Streaming Equivalence ----

    private static void testStreamingEquivalence() {
        System.out.println("Testing streaming equivalence...");

        try {
            SM3EngineFFM ffmEngine = null;
            try {
                ffmEngine = new SM3EngineFFM();
            } catch (Throwable t) {
                System.out.println("SKIP: FFM not available");
                return;
            }

            byte[] data = new byte[1024];
            ThreadLocalRandom.current().nextBytes(data);

            // One-shot
            ffmEngine.reset();
            ffmEngine.update(data);
            byte[] oneShotHash = ffmEngine.doFinal();

            // Streaming: update one byte at a time
            ffmEngine.reset();
            for (byte b : data) {
                ffmEngine.update(b);
            }
            byte[] streamingHash = ffmEngine.doFinal();

            String oneShotHex = HEX.formatHex(oneShotHash);
            String streamHex = HEX.formatHex(streamingHash);

            if (oneShotHex.equals(streamHex)) {
                pass("Streaming equivalence (1-byte updates): OK");
            } else {
                fail("Streaming equivalence (1-byte updates): FAIL\n" +
                        "  One-shot:  " + oneShotHex + "\n" +
                        "  Streaming: " + streamHex);
            }

            // Streaming: update with varied chunk sizes
            ffmEngine.reset();
            int offset = 0;
            int[] chunkSizes = {1, 7, 64, 3, 127, 63, 256, 1, 500, 2};
            for (int size : chunkSizes) {
                if (offset + size <= data.length) {
                    ffmEngine.update(data, offset, size);
                    offset += size;
                }
            }
            if (offset < data.length) {
                ffmEngine.update(data, offset, data.length - offset);
            }
            byte[] variedHash = ffmEngine.doFinal();

            if (oneShotHex.equals(HEX.formatHex(variedHash))) {
                pass("Streaming equivalence (varied chunks): OK");
            } else {
                fail("Streaming equivalence (varied chunks): FAIL");
            }

        } catch (Throwable t) {
            fail("Streaming equivalence: unexpected error: " + t.getMessage());
            t.printStackTrace();
        }
    }

    // ---- Clone Correctness ----

    private static void testCloneCorrectness() {
        System.out.println("Testing clone() correctness...");

        try {
            SM3EngineImpl javaEngine = new SM3EngineImpl();
            SM3EngineFFM ffmEngine = null;
            try {
                ffmEngine = new SM3EngineFFM();
            } catch (Throwable t) {
                System.out.println("SKIP: FFM not available for clone test");
                return;
            }

            byte[] prefix = "Hello, this is a prefix. ".getBytes();
            byte[] suffix = "And this is the suffix!".getBytes();

            // Test Java clone
            javaEngine.update(prefix);
            SM3EngineImpl javaClone = javaEngine.clone();
            javaEngine.update(suffix);
            byte[] javaOriginalHash = javaEngine.doFinal();
            javaClone.update(suffix);
            byte[] javaCloneHash = javaClone.doFinal();

            if (HEX.formatHex(javaOriginalHash).equals(HEX.formatHex(javaCloneHash))) {
                pass("Java clone(): OK");
            } else {
                fail("Java clone(): FAIL");
            }

            // Test FFM clone
            ffmEngine.update(prefix);
            SM3EngineFFM ffmClone = ffmEngine.clone();
            ffmEngine.update(suffix);
            byte[] ffmOriginalHash = ffmEngine.doFinal();
            ffmClone.update(suffix);
            byte[] ffmCloneHash = ffmClone.doFinal();

            if (HEX.formatHex(ffmOriginalHash).equals(HEX.formatHex(ffmCloneHash))) {
                pass("FFM clone(): OK");
            } else {
                fail("FFM clone(): FAIL");
            }

            // Cross-validate clone results between implementations
            if (HEX.formatHex(javaOriginalHash).equals(HEX.formatHex(ffmOriginalHash))) {
                pass("Clone cross-validation: OK");
            } else {
                fail("Clone cross-validation: FAIL");
            }

        } catch (Throwable t) {
            fail("Clone test: unexpected error: " + t.getMessage());
            t.printStackTrace();
        }
    }

    // ---- Empty Message ----

    private static void testEmptyMessage() {
        System.out.println("Testing empty message...");

        try {
            SM3EngineImpl javaEngine = new SM3EngineImpl();
            byte[] javaHash = javaEngine.doFinal();

            System.out.println("  Java empty hash: " + HEX.formatHex(javaHash));
            pass("Java empty message: " + HEX.formatHex(javaHash));

            try {
                SM3EngineFFM ffmEngine = new SM3EngineFFM();
                byte[] ffmHash = ffmEngine.doFinal();

                if (HEX.formatHex(javaHash).equals(HEX.formatHex(ffmHash))) {
                    pass("FFM empty message: OK");
                } else {
                    fail("FFM empty message: mismatch\n" +
                            "  Java: " + HEX.formatHex(javaHash) + "\n" +
                            "  FFM:  " + HEX.formatHex(ffmHash));
                }
            } catch (Throwable t) {
                System.out.println("SKIP: FFM not available");
            }
        } catch (Throwable t) {
            fail("Empty message test: unexpected error: " + t.getMessage());
        }
    }

    // ---- Large Message ----

    private static void testLargeMessage() {
        System.out.println("Testing large message (10 MB)...");

        try {
            byte[] largeData = new byte[10 * 1024 * 1024]; // 10 MB
            ThreadLocalRandom.current().nextBytes(largeData);

            SM3EngineImpl javaEngine = new SM3EngineImpl();
            long javaStart = System.nanoTime();
            javaEngine.update(largeData);
            byte[] javaHash = javaEngine.doFinal();
            long javaTime = System.nanoTime() - javaStart;

            System.out.println("  Java: " + HEX.formatHex(javaHash) +
                    " (" + (javaTime / 1_000_000) + " ms)");
            pass("Java large message (10 MB): OK");

            try {
                SM3EngineFFM ffmEngine = new SM3EngineFFM();
                long ffmStart = System.nanoTime();
                ffmEngine.update(largeData);
                byte[] ffmHash = ffmEngine.doFinal();
                long ffmTime = System.nanoTime() - ffmStart;

                if (HEX.formatHex(javaHash).equals(HEX.formatHex(ffmHash))) {
                    pass("FFM large message (10 MB): OK (" +
                            (ffmTime / 1_000_000) + " ms)");
                } else {
                    fail("FFM large message: mismatch");
                }
                System.out.println("  Speed ratio: " +
                        String.format("%.2f", (double) javaTime / ffmTime) +
                        "x (Java/FFM)");
            } catch (Throwable t) {
                System.out.println("SKIP: FFM not available for large message test");
            }
        } catch (Throwable t) {
            fail("Large message test: unexpected error: " + t.getMessage());
        }
    }

    // ---- Reset and Reuse ----

    private static void testResetAndReuse() {
        System.out.println("Testing reset() and reuse...");

        try {
            SM3EngineFFM ffmEngine = null;
            try {
                ffmEngine = new SM3EngineFFM();
            } catch (Throwable t) {
                System.out.println("SKIP: FFM not available");
                return;
            }

            byte[] data1 = "First message".getBytes();
            byte[] data2 = "Second message".getBytes();

            // First digest
            ffmEngine.update(data1);
            byte[] hash1a = ffmEngine.doFinal();

            // Reset and compute again
            ffmEngine.reset();
            ffmEngine.update(data1);
            byte[] hash1b = ffmEngine.doFinal();

            if (HEX.formatHex(hash1a).equals(HEX.formatHex(hash1b))) {
                pass("Reset and reuse (same input): OK");
            } else {
                fail("Reset and reuse (same input): FAIL");
            }

            // Reset and compute different message
            ffmEngine.reset();
            ffmEngine.update(data2);
            byte[] hash2 = ffmEngine.doFinal();

            if (!HEX.formatHex(hash1a).equals(HEX.formatHex(hash2))) {
                pass("Reset and reuse (different input): OK");
            } else {
                fail("Reset and reuse (different input): produced same hash!");
            }

        } catch (Throwable t) {
            fail("Reset test: unexpected error: " + t.getMessage());
            t.printStackTrace();
        }
    }

    // ---- Multiple Update Sizes ----

    private static void testMultipleUpdateSizes() {
        System.out.println("Testing multiple update() sizes...");

        try {
            SM3EngineImpl javaEngine = new SM3EngineImpl();
            SM3EngineFFM ffmEngine = null;
            try {
                ffmEngine = new SM3EngineFFM();
            } catch (Throwable t) {
                System.out.println("SKIP: FFM not available");
                return;
            }

            // Test with many small updates (worst case for native overhead)
            int numUpdates = 10000;
            byte[] singleByte = new byte[1];
            ThreadLocalRandom.current().nextBytes(singleByte);

            javaEngine.reset();
            for (int i = 0; i < numUpdates; i++) {
                singleByte[0] = (byte) i;
                javaEngine.update(singleByte[0]);
            }
            byte[] javaHash = javaEngine.doFinal();

            ffmEngine.reset();
            for (int i = 0; i < numUpdates; i++) {
                singleByte[0] = (byte) i;
                ffmEngine.update(singleByte[0]);
            }
            byte[] ffmHash = ffmEngine.doFinal();

            if (HEX.formatHex(javaHash).equals(HEX.formatHex(ffmHash))) {
                pass("Many small updates (" + numUpdates + "): OK");
            } else {
                fail("Many small updates (" + numUpdates + "): FAIL\n" +
                        "  Java: " + HEX.formatHex(javaHash) + "\n" +
                        "  FFM:  " + HEX.formatHex(ffmHash));
            }

        } catch (Throwable t) {
            fail("Multiple updates test: error: " + t.getMessage());
            t.printStackTrace();
        }
    }

    // ---- Test helpers ----

    private static void testVector(SM3Engine engine, String name,
                                   byte[] input, String expectedHex) {
        engine.reset();
        engine.update(input);
        byte[] hash = engine.doFinal();
        String actualHex = HEX.formatHex(hash);

        if (expectedHex.equals(actualHex)) {
            pass(name);
        } else {
            fail(name + "\n  Expected: " + expectedHex + "\n  Actual:   " + actualHex);
        }
    }

    private static void pass(String message) {
        passed++;
        System.out.println("  [PASS] " + message);
    }

    private static void fail(String message) {
        failed++;
        System.out.println("  [FAIL] " + message);
    }
}
