import org.openjdk.jmh.annotations.*;
import java.util.concurrent.TimeUnit;
import jdk.internal.misc.Unsafe;

@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {
    "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED"
})
public class UnsafeAllocationBenchmark {

    private Unsafe unsafe;
    private long lastAddress;

    @Param({"128", "4096", "1048576"})
    private int size;

    @Setup
    public void setup() {
        try {
            var f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = (Unsafe) f.get(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @TearDown(Level.Iteration)
    public void cleanup() {
        if (lastAddress != 0) {
            unsafe.freeMemory(lastAddress);
            lastAddress = 0;
        }
    }

    @Benchmark
    public long allocate() {
        lastAddress = unsafe.allocateMemory(size);
        return lastAddress;
    }

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }
}
