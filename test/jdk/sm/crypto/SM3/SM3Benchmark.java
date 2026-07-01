/*
 * Copyright (C) 2026, Tencent. All rights reserved.
 *
 * JMH Benchmark comparing SM3 engine implementations:
 *   Java (SM3EngineImpl), FFM (SM3EngineFFM)
 *
 * Build and run (requires JMH):
 *   mvn clean package
 *   java --enable-native-access=ALL-UNNAMED -jar target/benchmarks.jar
 *
 * Or compile manually:
 *   javac -cp jmh-core.jar:jmh-generator-annprocess.jar benchmarks/SM3Benchmark.java
 *   java -cp .:jmh-core.jar:jmh-generator-annprocess.jar \
 *        --enable-native-access=ALL-UNNAMED \
 *        SM3Benchmark
 */

package benchmarks;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import sun.security.provider.SM3Engine;
import sun.security.provider.SM3EngineImpl;
import sun.security.provider.SM3EngineFFM;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgs = {
        "--enable-native-access=ALL-UNNAMED",
        "--add-exports", "java.base/sun.security.provider=ALL-UNNAMED",
        "--patch-module", "java.base=target/classes"
})
@SuppressWarnings("restricted")
public class SM3Benchmark {

    @Param({"64", "256", "1024", "4096", "16384", "65536", "1048576"})
    public int dataSize;

    @Param({"java", "ffm"})
    public String backend;

    private byte[] data;
    private SM3Engine engine;

    @Setup
    public void setup() {
        data = new byte[dataSize];
        ThreadLocalRandom.current().nextBytes(data);

        engine = switch (backend) {
            case "ffm" -> {
                try {
                    yield new SM3EngineFFM();
                } catch (Throwable t) {
                    System.err.println("FFM not available, falling back to Java: " + t.getMessage());
                    yield new SM3EngineImpl();
                }
            }
            default -> new SM3EngineImpl();
        };
    }

    // ---- One-shot digest ----

    @Benchmark
    public byte[] digest() {
        engine.reset();
        engine.update(data);
        return engine.doFinal();
    }

    // ---- Streaming: 64-byte chunks ----

    @Benchmark
    public byte[] streaming64() {
        engine.reset();
        int offset = 0;
        while (offset < data.length) {
            int chunk = Math.min(64, data.length - offset);
            engine.update(data, offset, chunk);
            offset += chunk;
        }
        return engine.doFinal();
    }

    // ---- Streaming: 1-byte updates (worst case for native overhead) ----

    @Benchmark
    public byte[] streaming1() {
        engine.reset();
        for (byte b : data) {
            engine.update(b);
        }
        return engine.doFinal();
    }

    // ---- Clone benchmark ----

    @Benchmark
    public byte[] cloneDigest() {
        engine.reset();
        int half = data.length / 2;
        engine.update(data, 0, half);

        // Clone mid-stream
        SM3Engine clone;
        try {
            clone = engine.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }

        engine.update(data, half, data.length - half);
        byte[] originalHash = engine.doFinal();

        clone.update(data, half, data.length - half);
        @SuppressWarnings("unused")
        byte[] cloneHash = clone.doFinal();

        return originalHash;
    }

    // ---- Main entry point ----

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(SM3Benchmark.class.getSimpleName())
                .forks(1)
                .warmupIterations(3)
                .measurementIterations(3)
                .build();

        new Runner(opt).run();
    }
}
