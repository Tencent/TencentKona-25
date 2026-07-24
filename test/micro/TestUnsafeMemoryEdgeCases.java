import jdk.internal.misc.Unsafe;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Edge case tests for UnsafeMemoryOperationEvent JFR event.
 * Tests zero-byte alloc, null-free, disabled events, OOM, concurrency.
 */
public class TestUnsafeMemoryEdgeCases {

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

    static int passed = 0;
    static int failed = 0;

    static void check(String name, boolean condition) {
        if (condition) {
            System.out.println("  PASS: " + name);
            passed++;
        } else {
            System.out.println("  FAIL: " + name);
            failed++;
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Edge Case Tests for UnsafeMemoryOperationEvent ===\n");

        testZeroByteAllocation();
        testFreeNull();
        testEventDisabled();
        testReallocateWithZeroAddress();
        testConcurrentAllocations();
        testEventFieldsConsistency();

        System.out.printf("\n=== Results: %d passed, %d failed ===%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // Test 1: Zero-byte allocation should not fire an event
    static void testZeroByteAllocation() throws Exception {
        System.out.println("Test: zeroByteAllocation");
        Path dumpPath = Files.createTempFile("rec-zero", ".jfr");
        try (Recording r = new Recording()) {
            r.enable("jdk.UnsafeMemoryOperation");
            r.start();
            long addr = unsafe.allocateMemory(0); // returns 0, no event
            unsafe.freeMemory(addr); // free(0), no event
            r.stop();
            r.dump(dumpPath);
        }
        List<RecordedEvent> events = RecordingFile.readAllEvents(dumpPath);
        Files.deleteIfExists(dumpPath);
        check("zero-byte alloc/free fires 0 events", events.size() == 0);
    }

    // Test 2: free(0) should not fire an event
    static void testFreeNull() throws Exception {
        System.out.println("Test: freeNull");
        Path dumpPath = Files.createTempFile("rec-freenull", ".jfr");
        try (Recording r = new Recording()) {
            r.enable("jdk.UnsafeMemoryOperation");
            r.start();
            unsafe.freeMemory(0);
            r.stop();
            r.dump(dumpPath);
        }
        List<RecordedEvent> events = RecordingFile.readAllEvents(dumpPath);
        Files.deleteIfExists(dumpPath);
        check("free(0) fires 0 events", events.size() == 0);
    }

    // Test 3: Event disabled → no events captured
    static void testEventDisabled() throws Exception {
        System.out.println("Test: eventDisabled");
        Path dumpPath = Files.createTempFile("rec-disabled", ".jfr");
        try (Recording r = new Recording()) {
            // Don't enable UnsafeMemoryOperation
            r.start();
            long addr = unsafe.allocateMemory(1024);
            unsafe.freeMemory(addr);
            r.stop();
            r.dump(dumpPath);
        }
        List<RecordedEvent> events = RecordingFile.readAllEvents(dumpPath);
        Files.deleteIfExists(dumpPath);
        check("disabled event fires 0 events", events.size() == 0);
    }

    // Test 4: reallocate(0, 1KB) should go through allocateMemory0, emit Reallocate event
    static void testReallocateWithZeroAddress() throws Exception {
        System.out.println("Test: reallocateWithZeroAddress");
        Path dumpPath = Files.createTempFile("rec-realloc", ".jfr");
        try (Recording r = new Recording()) {
            r.enable("jdk.UnsafeMemoryOperation");
            r.start();
            long addr = unsafe.reallocateMemory(0, 1024); // old addr = 0
            r.stop();
            r.dump(dumpPath);
            unsafe.freeMemory(addr);
        }
        List<RecordedEvent> events = RecordingFile.readAllEvents(dumpPath);
        Files.deleteIfExists(dumpPath);
        check("reallocate(0, 1024) fires 1 event", events.size() == 1);
        if (events.size() == 1) {
            RecordedEvent e = events.get(0);
            check("operation is 'Reallocate'", "Reallocate".equals(e.getString("operation")));
            check("size is 1024", e.getLong("size") == 1024);
            check("address is non-zero", e.getLong("address") != 0);
            check("oldAddress is 0", e.getLong("oldAddress") == 0);
        }
    }

    // Test 5: Concurrent allocations - thread-local JFR buffers should have no race
    static void testConcurrentAllocations() throws Exception {
        System.out.println("Test: concurrentAllocations");
        int threads = 4;
        int allocsPerThread = 500;
        var exec = Executors.newFixedThreadPool(threads);

        Path dumpPath = Files.createTempFile("rec-concurrent", ".jfr");
        try (Recording r = new Recording()) {
            r.enable("jdk.UnsafeMemoryOperation");
            r.start();

            var futures = new java.util.concurrent.Future<?>[threads];
            for (int t = 0; t < threads; t++) {
                futures[t] = exec.submit(() -> {
                    for (int i = 0; i < allocsPerThread; i++) {
                        long addr = unsafe.allocateMemory(64);
                        unsafe.freeMemory(addr);
                    }
                });
            }
            for (var f : futures) f.get();
            r.stop();
            r.dump(dumpPath);
        }
        exec.shutdown();

        List<RecordedEvent> events = RecordingFile.readAllEvents(dumpPath);
        Files.deleteIfExists(dumpPath);
        int expected = threads * allocsPerThread * 2; // alloc + free per iteration
        check("concurrent events count: got " + events.size() + " expected " + expected,
              events.size() == expected);
    }

    // Test 6: Event field consistency for Allocate/Free sequence
    static void testEventFieldsConsistency() throws Exception {
        System.out.println("Test: eventFieldsConsistency");
        Path dumpPath = Files.createTempFile("rec-fields", ".jfr");
        try (Recording r = new Recording()) {
            r.enable("jdk.UnsafeMemoryOperation");
            r.start();

            long addr = unsafe.allocateMemory(256);
            long addr2 = unsafe.reallocateMemory(addr, 512);
            unsafe.freeMemory(addr2);

            r.stop();
            r.dump(dumpPath);
        }
        List<RecordedEvent> events = RecordingFile.readAllEvents(dumpPath);
        Files.deleteIfExists(dumpPath);

        check("3 events captured", events.size() == 3);
        if (events.size() == 3) {
            // Allocate event
            RecordedEvent alloc = events.get(0);
            check("event[0] operation = Allocate",
                  "Allocate".equals(alloc.getString("operation")));
            check("event[0] size = 256", alloc.getLong("size") == 256);
            check("event[0] oldAddress = 0", alloc.getLong("oldAddress") == 0);

            // Reallocate event
            RecordedEvent realloc = events.get(1);
            check("event[1] operation = Reallocate",
                  "Reallocate".equals(realloc.getString("operation")));
            check("event[1] size = 512", realloc.getLong("size") == 512);
            check("event[1] oldAddress matches alloc address",
                  realloc.getLong("oldAddress") == alloc.getLong("address"));

            // Free event
            RecordedEvent free = events.get(2);
            check("event[2] operation = Free",
                  "Free".equals(free.getString("operation")));
            check("event[2] size = -1", free.getLong("size") == -1);
            check("event[2] address matches realloc address",
                  free.getLong("address") == realloc.getLong("address"));
        }
    }
}
