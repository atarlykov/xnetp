package xnetp.poc.info;


import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Function;

public class FalseSharingTest {

    // parallel counters
    private final static int THREADS        = 4;

    // alignment of counters in array, pseudo cache line
    private final static int LINE           = 256;

    // increment iteration
    private final static int ITERATIONS     = 100_000_000;


    final static ExecutorService executor = Executors.newFixedThreadPool(THREADS);

    public static void main(String[] args) throws Exception {

        for (int i = 0; i < 3; i++) {
            runner("t1: local variable              ", FalseSharingTest::t1);
            runner("t2: shared array                ", FalseSharingTest::t2);
            runner("t2: shared array       aligned  ", FalseSharingTest::t21);
            runner("t3: volatile shared array       ", FalseSharingTest::t3);
            runner("t4: AtomicLongArray             ", FalseSharingTest::t4);   // <-- real comparison
            runner("t4: AtomicLongArray    aligned  ", FalseSharingTest::t41);  // <-- is here
            runner("t5: AtomicLong[]                ", FalseSharingTest::t5);
            runner("t5: AtomicLong[]       aligned  ", FalseSharingTest::t51);
            System.out.println("\n");
        }
        executor.shutdown();
    }

    static void runner(String name, Function<Integer, Long> task) throws Exception {
        long tmp = 0;
        Future<Long> futures[] = new Future[THREADS];

        long tStart = System.nanoTime();
        for (int i = 0; i < THREADS; i++) {
            final int iCopy = i;
            futures[i] = executor.submit(() -> task.apply(iCopy));
        }
        for (int i = 0; i < THREADS; i++) {
            tmp += futures[i].get();
        }
        long tTotal = System.nanoTime() - tStart;

        System.out.println(name + "  time: " + String.format("%16d", tTotal) + "   x:" + tmp);
    }

    static long t1(Integer thread) {
        long a = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            a ++;
        }
        return a;
    }

    static long[] t2counters = new long[THREADS];
    static long t2(Integer thread) {
        int t = thread;
        t2counters[t] = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            t2counters[t]++;
        }
        return t2counters[t];
    }



    static long[] t21counters = new long[THREADS * LINE];
    static long t21(Integer thread) {
        int t = thread * LINE;
        t21counters[t] = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            t21counters[t]++;
        }
        return t21counters[t];
    }




    static volatile long[] t3counters = new long[THREADS];
    static long t3(Integer thread) {
        int t = thread;
        t3counters[t] = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            t3counters[t]++;
        }
        return t3counters[t];
    }


    static AtomicLongArray t4counters = new AtomicLongArray(THREADS);
    static long t4(Integer thread) {
        int t = thread;
        t4counters.set(t, 0);
        for (int i = 0; i < ITERATIONS; i++) {
            t4counters.incrementAndGet(t);
        }
        return t4counters.get(t);
    }



    static AtomicLongArray t41counters = new AtomicLongArray(THREADS * LINE);
    static long t41(Integer thread) {
        int t = thread * LINE;
        t41counters.set(t, 0);
        for (int i = 0; i < ITERATIONS; i++) {
            t41counters.incrementAndGet(t);
        }
        return t41counters.get(t);
    }



    static AtomicLong[] t5counters = new AtomicLong[THREADS];
    static {
        for (int i = 0; i < t5counters.length; i++) {
            t5counters[i] = new AtomicLong();
        }
    }
    static long t5(Integer thread) {
        int t = thread;
        t5counters[t].set(0);
        for (int i = 0; i < ITERATIONS; i++) {
            t5counters[t].incrementAndGet();
        }
        return t5counters[t].get();
    }



    static AtomicLong[] t51counters = new AtomicLong[THREADS * LINE];
    static {
        for (int i = 0; i < t51counters.length; i++) {
            t51counters[i] = new AtomicLong();
        }
    }
    static long t51(Integer thread) {
        int t = thread * LINE;
        t51counters[t].set(0);
        for (int i = 0; i < ITERATIONS; i++) {
            t51counters[t].incrementAndGet();
        }
        return t51counters[t].get();
    }

}
