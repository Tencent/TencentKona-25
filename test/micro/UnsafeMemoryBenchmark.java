import jdk.internal.misc.Unsafe;
import jdk.jfr.Recording;
import java.time.Duration;

/**
 * Microbenchmark for JFR Unsafe memory operation event overhead.
 *
 * Configurations:
 *   Baseline       - No JFR recording (event disabled(), ~1-2ns check)
 *   JFR Disabled   - JFR initialized but event not enabled
 *   JFR Enabled    - Event enabled, every call recorded
 *   JFR Throttled  - Event enabled with 1ms threshold
 */
public class UnsafeMemoryBenchmark {

    static Unsafe unsafe;
    static {
        try {
            var f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = (Unsafe) f.get(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static final int WARMUP = 20_000;
    static final int ITERATIONS = 100_000;
    static final int MEASUREMENT_ITERATIONS = 5;

    public static void main(String[] args) throws Exception {
        System.out.println("JFR Unsafe Memory Operation - Performance Benchmark");
        System.out.println("==================================================");
        System.out.printf("Warmup: %d, Measurement: %d x %d iterations%n%n", WARMUP, MEASUREMENT_ITERATIONS, ITERATIONS);
        System.out.println("Size\tConfig\t\t\tThroughput (ops/s)\tRelative");
        System.out.println("----\t------\t\t\t------------------\t--------");

        int[] sizes = {128, 4096, 1048576};

        for (int size : sizes) {
            double baseThroughput = measureThroughput(size, null, false);
            Thread.sleep(500);

            double jfrDisabled = measureThroughput(size, null, true);
            Thread.sleep(500);

            double enabledThroughput = measureThroughput(size, "jdk.UnsafeMemoryOperation", false);
            Thread.sleep(500);

            double throttledThroughput = measureThroughput(size, "jdk.UnsafeMemoryOperation", true);
            Thread.sleep(500);

            System.out.printf("%d\tBaseline\t\t\t%,.0f\t\t100%%%n", size, baseThroughput);
            System.out.printf("%d\tJFR Init (no events)\t\t%,.0f\t\t%.0f%%%n",
                size, jfrDisabled, (jfrDisabled / baseThroughput) * 100);
            System.out.printf("%d\tJFR Enabled (threshold=0)\t%,.0f\t\t%.0f%%%n",
                size, enabledThroughput, (enabledThroughput / baseThroughput) * 100);
            System.out.printf("%d\tJFR Throttled (threshold=1ms)\t%,.0f\t\t%.0f%%%n",
                size, throttledThroughput, (throttledThroughput / baseThroughput) * 100);
            System.out.println();
        }
    }

    static double measureThroughput(int size, String eventName, boolean useThreshold) throws Exception {
        for (int i = 0; i < WARMUP; i++) {
            long addr = unsafe.allocateMemory(size);
            unsafe.freeMemory(addr);
        }

        long totalOps = 0;
        long totalTime = 0;

        for (int m = 0; m < MEASUREMENT_ITERATIONS; m++) {
            Recording recording = null;
            if (eventName != null || useThreshold) {
                recording = new Recording();
                if (eventName != null) {
                    recording.enable(eventName);
                    if (useThreshold) {
                        recording.enable(eventName).withThreshold(Duration.ofMillis(1));
                    }
                }
                recording.start();
            }

            long start = System.nanoTime();
            for (int i = 0; i < ITERATIONS; i++) {
                long addr = unsafe.allocateMemory(size);
                unsafe.freeMemory(addr);
            }
            long end = System.nanoTime();

            if (recording != null) {
                recording.stop();
                recording.close();
            }

            totalOps += ITERATIONS;
            totalTime += (end - start);
        }

        double avgTimePerOp = (double) totalTime / totalOps;
        return 1_000_000_000.0 / avgTimePerOp;
    }
}
