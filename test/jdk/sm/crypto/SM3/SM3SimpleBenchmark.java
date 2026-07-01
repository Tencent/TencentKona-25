/*
 * Copyright (C) 2026, Tencent. All rights reserved.
 *
 * Standalone SM3 performance benchmark (no JMH dependency).
 * Compares Java, FFM implementations across multiple message sizes.
 *
 * Run:
 *   java --enable-native-access=ALL-UNNAMED \
 *        --add-exports java.base/sun.security.provider=ALL-UNNAMED \
 *        --patch-module java.base=target/classes \
 *        -cp target/classes:target/test-classes \
 *        sm3test.SM3SimpleBenchmark
 */

package sm3test;

import sun.security.provider.SM3Engine;
import sun.security.provider.SM3EngineImpl;
import sun.security.provider.SM3EngineFFM;

import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

@SuppressWarnings("restricted")
public class SM3SimpleBenchmark {

    // Message sizes to benchmark (bytes)
    private static final int[] DATA_SIZES = {
            64, 256, 1024, 4096, 16384, 65536, 262144, 1048576
    };

    // Number of iterations per benchmark (higher = more stable results)
    private static final int WARMUP_ITERS = 50;
    private static final int MEASURE_ITERS = 200;

    public static void main(String[] args) {
        System.out.println("=== SM3 Performance Benchmark ===");
        System.out.println("Warmup iterations: " + WARMUP_ITERS);
        System.out.println("Measurement iterations: " + MEASURE_ITERS);
        System.out.println();

        // Print header
        System.out.printf("%-10s | %-15s | %-15s | %-15s | %-10s%n",
                "Size", "Java (MB/s)", "FFM (MB/s)", "FFM streaming64", "FFM/Java");
        System.out.println("-".repeat(80));

        for (int size : DATA_SIZES) {
            byte[] data = new byte[size];
            ThreadLocalRandom.current().nextBytes(data);

            // Java benchmark
            double javaThroughput = benchmarkJava(data);

            // FFM benchmark (one-shot)
            double ffmThroughput = benchmarkFFM(data, false);

            // FFM benchmark (streaming, 64-byte chunks)
            double ffmStreaming64 = benchmarkFFM(data, true);

            double speedup = ffmThroughput / javaThroughput;
            String sizeStr = formatSize(size);

            System.out.printf("%-10s | %13.2f MB/s | %13.2f MB/s | %13.2f MB/s | %8.2fx%n",
                    sizeStr, javaThroughput, ffmThroughput, ffmStreaming64, speedup);
        }

        System.out.println();
        System.out.println("=== Small Update Latency (100,000 single-byte updates) ===");

        benchmarkSingleByteUpdates();

        System.out.println();
        System.out.println("=== Clone Performance ===");
        benchmarkClone();

        System.out.println();
        System.out.println("Benchmark complete.");
    }

    /**
     * Benchmark Java SM3EngineImpl one-shot digest throughput.
     */
    private static double benchmarkJava(byte[] data) {
        SM3EngineImpl engine = new SM3EngineImpl();

        // Warmup
        for (int i = 0; i < WARMUP_ITERS; i++) {
            engine.reset();
            engine.update(data);
            engine.doFinal();
        }

        // Measure
        long start = System.nanoTime();
        for (int i = 0; i < MEASURE_ITERS; i++) {
            engine.reset();
            engine.update(data);
            engine.doFinal();
        }
        long elapsed = System.nanoTime() - start;

        double totalMB = (double) data.length * MEASURE_ITERS / (1024 * 1024);
        double seconds = elapsed / 1_000_000_000.0;
        return totalMB / seconds;
    }

    /**
     * Benchmark FFM SM3EngineFFM digest throughput.
     * @param streaming if true, feed data in 64-byte chunks
     */
    private static double benchmarkFFM(byte[] data, boolean streaming) {
        SM3EngineFFM engine;
        try {
            engine = new SM3EngineFFM();
        } catch (Throwable t) {
            return 0.0; // FFM not available
        }

        // Warmup
        for (int i = 0; i < WARMUP_ITERS; i++) {
            engine.reset();
            if (streaming) {
                updateStreaming(engine, data, 64);
            } else {
                engine.update(data);
            }
            engine.doFinal();
        }

        // Measure
        long start = System.nanoTime();
        for (int i = 0; i < MEASURE_ITERS; i++) {
            engine.reset();
            if (streaming) {
                updateStreaming(engine, data, 64);
            } else {
                engine.update(data);
            }
            engine.doFinal();
        }
        long elapsed = System.nanoTime() - start;

        double totalMB = (double) data.length * MEASURE_ITERS / (1024 * 1024);
        double seconds = elapsed / 1_000_000_000.0;
        return totalMB / seconds;
    }

    private static void updateStreaming(SM3Engine engine, byte[] data, int chunkSize) {
        int offset = 0;
        while (offset < data.length) {
            int len = Math.min(chunkSize, data.length - offset);
            engine.update(data, offset, len);
            offset += len;
        }
    }

    /**
     * Benchmark single-byte update latency.
     */
    private static void benchmarkSingleByteUpdates() {
        SM3EngineImpl javaEngine = new SM3EngineImpl();
        SM3EngineFFM ffmEngine = null;
        try {
            ffmEngine = new SM3EngineFFM();
        } catch (Throwable t) {
            System.out.println("FFM not available for single-byte benchmark");
            return;
        }

        int count = 100_000;
        byte[] data = new byte[count];
        ThreadLocalRandom.current().nextBytes(data);

        // Java
        long javaStart = System.nanoTime();
        for (byte b : data) {
            javaEngine.update(b);
        }
        javaEngine.doFinal();
        long javaTime = System.nanoTime() - javaStart;
        double javaNsPerUpdate = (double) javaTime / count;

        // FFM
        long ffmStart = System.nanoTime();
        for (byte b : data) {
            ffmEngine.update(b);
        }
        ffmEngine.doFinal();
        long ffmTime = System.nanoTime() - ffmStart;
        double ffmNsPerUpdate = (double) ffmTime / count;

        System.out.printf("  Java single-byte update: %.1f ns/update (%d total ms)%n",
                javaNsPerUpdate, javaTime / 1_000_000);
        System.out.printf("  FFM  single-byte update: %.1f ns/update (%d total ms)%n",
                ffmNsPerUpdate, ffmTime / 1_000_000);
        System.out.printf("  Ratio: %.2fx%n", javaNsPerUpdate / ffmNsPerUpdate);
    }

    /**
     * Benchmark clone performance.
     */
    private static void benchmarkClone() {
        SM3EngineImpl javaEngine = new SM3EngineImpl();
        SM3EngineFFM ffmEngine = null;
        try {
            ffmEngine = new SM3EngineFFM();
        } catch (Throwable t) {
            System.out.println("FFM not available for clone benchmark");
            return;
        }

        byte[] prefix = new byte[4096];
        byte[] suffix = new byte[4096];
        ThreadLocalRandom.current().nextBytes(prefix);
        ThreadLocalRandom.current().nextBytes(suffix);

        int iters = 10000;

        // Java clone
        long javaStart = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            javaEngine.reset();
            javaEngine.update(prefix);
            try {
                SM3EngineImpl clone = javaEngine.clone();
                clone.update(suffix);
                clone.doFinal();
            } catch (CloneNotSupportedException e) {
                throw new RuntimeException(e);
            }
            javaEngine.update(suffix);
            javaEngine.doFinal();
        }
        long javaTime = System.nanoTime() - javaStart;

        // FFM clone
        long ffmStart = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            ffmEngine.reset();
            ffmEngine.update(prefix);
            try {
                SM3EngineFFM clone = ffmEngine.clone();
                clone.update(suffix);
                clone.doFinal();
            } catch (CloneNotSupportedException e) {
                throw new RuntimeException(e);
            }
            ffmEngine.update(suffix);
            ffmEngine.doFinal();
        }
        long ffmTime = System.nanoTime() - ffmStart;

        System.out.printf("  Java clone: %.0f ns/op%n", (double) javaTime / iters);
        System.out.printf("  FFM  clone: %.0f ns/op%n", (double) ffmTime / iters);
        System.out.printf("  Ratio: %.2fx%n", (double) javaTime / ffmTime);
    }

    private static String formatSize(int bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return (bytes / (1024 * 1024)) + " MB";
    }
}
