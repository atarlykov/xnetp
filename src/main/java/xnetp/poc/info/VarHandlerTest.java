package xnetp.poc.info;


import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.function.Function;

public class VarHandlerTest {

    // parallel counters
    private final static int THREADS = 4;

    // increment iteration
    private final static int ITERATIONS = 100_000_000;


    final static ExecutorService executor = Executors.newFixedThreadPool(THREADS);


    public static void main(String[] args) throws Exception {
        (new VarHandlerTest()).test();
    }

    void test() throws Exception {

        for (int i = 0; i < 3; i++) {
            runner2("t1: atomic long counter                  ", this::t1);
            runner2("t2: vh atomic counter (as volatile)      ", this::t2);
            runner2("t3: AFU                                  ", this::t3);
            System.out.println("\n");
        }

        // http://gee.cs.oswego.edu/dl/html/j9mm.html
        // volatile vs opaque
        // "opaque"s are ordered with itself in the thread and
        // eventually (sometime) WILL be available in other threads,
        // no any ordering with plain accesses to itself/other variables
        for (int i = 0; i < 5; i++) {
            runner2("t40: local set                  ", this::t40m); // strange/use JMH
            runner2("t4 : volatile set               ", this::t4m);
            runner2("t5 : opaque set                 ", this::t5m);
            System.out.println("");
        }



        executor.shutdown();
    }

    void runner(String name, Function<Integer, Long> task) throws Exception {
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

    void runner2(String name, Function<Integer, Long> task) throws Exception {
        long tStart = System.nanoTime();
        task.apply(1);
        long tTotal = System.nanoTime() - tStart;
        System.out.println(name + "  time: " + String.format("%16d", tTotal));
    }



    final AtomicLong t1Counter = new AtomicLong(0);
    long t1(Integer thread) {
        for (int i = 0; i < ITERATIONS; i++) {
            t1Counter.addAndGet(i);
        }
        return t1Counter.get();
    }

    volatile long t2Counter;
    static final VarHandle t2vh;
    static {
        try {
            t2vh = MethodHandles.lookup().findVarHandle(VarHandlerTest.class, "t2Counter", Long.TYPE);
        } catch (Exception e) {
            throw new Error(e);
        }
    }

    long t2(Integer thread) {
        for (int i = 0; i < ITERATIONS; i++) {
            t2vh.getAndAdd(this, i);
            //t2vh.getAndAddAcquire(this, i);
        }
        return t2Counter;
    }

    volatile long t3Counter = 0;
    final static AtomicLongFieldUpdater<VarHandlerTest> t3Up = AtomicLongFieldUpdater.newUpdater(VarHandlerTest.class, "t3Counter");

    long t3(Integer thread) {
        for (int i = 0; i < ITERATIONS; i++) {
            //t3Up.lazySet(this, i);
            t3Up.addAndGet(this, i);
        }
        return t3Counter;
    }


    // ----------

    long t40;
    long t40m(Integer thread) {
        for (long i = 0; i < ITERATIONS; i++) {
            t40 = i;
        }
        return t4;
    }


    volatile long t4;
    long t4m(Integer thread) {
        for (long i = 0; i < ITERATIONS; i++) {
            t4 = i;
        }
        return t4;
    }


    volatile long t5;
    static final VarHandle T5;
    static {
        try {
            T5 = MethodHandles.lookup().findVarHandle(VarHandlerTest.class, "t5", Long.TYPE);
        } catch (Exception e) {
            throw new Error(e);
        }
    }
    long t5m(Integer thread) {
        for (long i = 0; i < ITERATIONS; i++) {
            T5.setOpaque(this, i);
        }
        //VarHandle.releaseFence();
        return (long)T5.getOpaque(this);
    }


}
