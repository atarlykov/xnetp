package xnetp.poc.info;

/**
 * separate workload test for functional tests
 */
public class WorkloadTest {

    // workload iteration
    private long workloadIterations;

    public static void main(String[] args) {
        long iterations = 1000;
        //System.out.println(Arrays.toString(args));
        if (args.length > 0) {
            iterations = Integer.parseInt(args[0]);
        }
        System.out.println("iterations: " + iterations);
        (new WorkloadTest()).test(iterations);
    }


    public void test(long interations) {
        // setup test
        workloadIterations = interations;

        long tmp = 0;
        // warm up
        tmp += testCycle();
        tmp += testCycle();
        tmp += testCycle();

        long tStart = System.nanoTime();
        tmp += testCycle();
        long tEnd = System.nanoTime();
        System.out.println(((double)(tEnd - tStart))/(10_000_000));
        System.out.println(tmp);

    }


    public long testCycle() {
        long result = 0;
        for (int i = 0; i < 1_000_000; i++) {
            result += workload();
            result += workload();
            result += workload();
            result += workload();
            result += workload();
            result += workload();
            result += workload();
            result += workload();
            result += workload();
            result += workload();
        }
        return result;
    }


    /**
     * emulates workload
     */
    private long workload() {
        long accumulator = 0;
        final long limit = workloadIterations;
        for (int i = 0; i < limit; i++) {
            accumulator += i;
        }
        return accumulator;
    }

}
