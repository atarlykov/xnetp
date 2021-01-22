package xnetp.poc.info;

import xnetp.poc.affinity.Affinity;

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ArrayBlockingQueue in multi consumers mode
 */
public class ABSCQueuesTest {


    public static class Producer extends Thread {
        // will track iterations
        public AtomicLong rxIterationCounter = new AtomicLong();

         // indicates there are no empty buffers to receive data,
        // processing takes too much time
        public AtomicLong rxNoFreeBuffers = new AtomicLong(0);

        // indicates all buffers are used,
        // processing takes too much time
        public AtomicLong rxUsedBuffersOverflow = new AtomicLong(0);

        // here we'll get free buffers to receive packets to
        private Queue<Object>[] queueFreeBuffers;
        // there we'll send buffers with packets for processing
        private Queue<Object>[] queueUsedBuffers;

        // threads' affinity, cput index
        private int affinity;

        public Producer(
                int _affinity,
                Queue<Object>[] _queueFreeBuffers,
                Queue<Object>[] _queueUsedBuffers) {
            affinity = _affinity;
            queueFreeBuffers = _queueFreeBuffers;
            queueUsedBuffers = _queueUsedBuffers;
        }

        @Override
        public void run() {
            // set threads' affinity if requested
            if (affinity > 0) {
                Affinity.setAffinity(affinity);
            }

            int queues = queueFreeBuffers.length;
            int indexQueueFree = queues - 1;
            int indexQueueUsed = queues - 1;
            while (true) {

                // try 1st queue at first
                Object buffer = queueFreeBuffers[indexQueueFree].poll();
                if (buffer == null) {
                    rxNoFreeBuffers.incrementAndGet();
                    indexQueueFree = (indexQueueFree == 0) ? queues - 1 : indexQueueFree - 1;
                    while ((buffer = queueFreeBuffers[indexQueueFree].poll()) == null) {
                        indexQueueFree = (indexQueueFree == 0) ? queues - 1 : indexQueueFree - 1;
                        Thread.onSpinWait();
                    }
                }

                rxIterationCounter.addAndGet(1);

                // use indexQueueUsed <- indexQueueFree ???

                while (!queueUsedBuffers[indexQueueUsed].offer(buffer)) {
                    // remember error
                    rxUsedBuffersOverflow.incrementAndGet();
                    indexQueueUsed = (indexQueueUsed == 0) ? queues - 1 : indexQueueUsed - 1;
                    Thread.onSpinWait();
                }

                // use indexQueueFree <- next???
                // use indexQueueUsed <- indexQueueFree???
            }        }
    }

    public static class Consumer extends Thread {


        // will track received buffers
        public AtomicLong rxIterationsCounter = new AtomicLong();

        // indicates there are no used buffers to receive data,
        // packets' receiving takes too much time
        public AtomicLong rxNoUsedBuffers = new AtomicLong(0);

        // indicates there are no used buffers to receive data, for long time
        public AtomicLong rxNoUsedBuffersLong = new AtomicLong(0);

        // indicates all buffers are used,
        // packets' receiving takes too much time
        public AtomicLong rxFreeBuffersOverflow = new AtomicLong(0);


        // here we'll get free buffers to receive packets to
        private Queue<Object> queueFreeBuffers;
        // there we'll send buffers with packets for processing
        private Queue<Object> queueUsedBuffers;


        // threads' affinity, cpu index
        public final int affinity;


        /**
         *
         */
        public Consumer(
                int _affinity,
                Queue<Object> _queueFreeBuffers,
                Queue<Object> _queueUsedBuffers)
        {
            affinity = _affinity;
            queueFreeBuffers = _queueFreeBuffers;
            queueUsedBuffers = _queueUsedBuffers;
        }

        @Override
        public void run() {

            // set threads' affinity if requested
            if (affinity > 0) {
                Affinity.setAffinity(affinity);
            }


            while (true) {
                Object buffer = queueUsedBuffers.poll();
                if (buffer == null) {
                    // no ready buffers
                    rxNoUsedBuffers.incrementAndGet();

                    // run very quick waits
                    int counter = 100;
                    while (((buffer = queueUsedBuffers.poll()) == null) && (0 < --counter)) {
                        Thread.onSpinWait();
                    }
                    // back off and run long waits till data is available
                    if ((counter == 0) && (buffer == null)) {
                        // remember long waiting
                        rxNoUsedBuffersLong.incrementAndGet();

                        while ((buffer = queueUsedBuffers.poll()) == null) {
                            try {
                                Thread.sleep(1);
                            } catch (InterruptedException e) {
                            }
                        }
                    }
                }

                // count received buffers
                rxIterationsCounter.incrementAndGet();


                // return buffer to receiver, MUST cycle to not loose buffer
                while (!queueFreeBuffers.offer(buffer)) {
                    // that is strange, there always should be room there
                    rxFreeBuffersOverflow.incrementAndGet();
                    Thread.onSpinWait();
                }
            }
        }
    }

    private static final int RECEIVE_BUFFERS    = 4096;
    private static final int CONSUMERS          = 8;
    private static final int[] CONSUMERS_AFFINITY    = {0, 0, 0 , 0 };


    public static void main(String[] args) {

        // setup buffers/queues
        Queue<Object>[] rxQueueFreeBuffers = new ArrayBlockingQueue[CONSUMERS];
        Queue<Object>[] rxQueueUsedBuffers = new ArrayBlockingQueue[CONSUMERS];
        for (int i = 0; i < CONSUMERS; i++) {
            rxQueueFreeBuffers[i] = new ArrayBlockingQueue<Object>(RECEIVE_BUFFERS);
            rxQueueUsedBuffers[i] = new ArrayBlockingQueue<Object>(RECEIVE_BUFFERS);

            for (int e = 0; e < RECEIVE_BUFFERS; e++) {
                rxQueueFreeBuffers[i].offer(new Object());
            }
        }


        // allocate threads
        Producer producer = new Producer(0, rxQueueFreeBuffers, rxQueueUsedBuffers);

        Consumer[] consumers = new Consumer[CONSUMERS];
        for (int i = 0; i < CONSUMERS; i++) {
            // get affinity for thread
            int tAffinity = (i < CONSUMERS_AFFINITY.length) ? CONSUMERS_AFFINITY[i] : 0;

            consumers[i] = new Consumer(
                    tAffinity,
                    rxQueueFreeBuffers[i], rxQueueUsedBuffers[i]);
        }


        // run the processing
        for (int i = 0; i < CONSUMERS; i++) {
            consumers[i].start();
        }
        producer.start();


        // -----------------------------------------------

        // period in nanoseconds
        final long period = 1_000_000_000L;
        final long msPeriod = period / 1_000_000;

        // collect and print statistics
        long tStart = System.nanoTime();
        while (true) {
            long tNow = System.nanoTime();
            if (period < tNow - tStart) {
                // scale factor for per sec outouts
                double factor = 1E9 / (tNow - tStart);

                long pIterations = producer.rxIterationCounter.getAndSet(0);
                long pNoFreeBuffers = producer.rxNoFreeBuffers.getAndSet(0);
                long pUsedBuffersOverflow = producer.rxUsedBuffersOverflow.getAndSet(0);


                System.out.print("---- [iteration: ");
                System.out.print(msPeriod);
                System.out.println(" ms] ----------------------------------------------------------------------------------------------------------------------------------------");

                // print producer statistics
                System.out.println("     [receive]               iterations/s      iterations  no free buffers       nfb%  used buffers o/flow");
                System.out.println(String.format("                               %10d      %10d       %10d     %6.2f           %10d",
                        (int)(factor * pIterations), pIterations, pNoFreeBuffers, 100d * pNoFreeBuffers / pIterations, pUsedBuffersOverflow));


                // print processing statistics
                System.out.println("     [handlers]");
                System.out.println("  ----------------------------------------------------------------------------------------------------------------------------------------------------------------");
                System.out.println("    cpu  iterations/s    iterations         nub      nub%        nubL     nubL%         fbo");
                for (int i = 0; i < CONSUMERS; i++) {
                    Consumer consumer = consumers[i];
                    long cIterations            = consumer.rxIterationsCounter.getAndSet(0);
                    long cNoUsedBuffers         = consumer.rxNoUsedBuffers.getAndSet(0);
                    long cNoUsedBuffersLong     = consumer.rxNoUsedBuffersLong.getAndSet(0);
                    long cFreeBuffersOverflow   = consumer.rxFreeBuffersOverflow.getAndSet(0);

                    String line = String.format(
                            "     %2d    %10d    %10d  %10d    %6.2f  %10d    %6.2f  %10d",
                            consumers[i].affinity, (int)(factor * cIterations), cIterations,
                            cNoUsedBuffers, 100d * cNoUsedBuffers / cIterations,
                            cNoUsedBuffersLong, 100d * cNoUsedBuffersLong / cNoUsedBuffers,
                            cFreeBuffersOverflow);
                    System.out.println(line);
                }
                System.out.println();


                System.out.println("\n\n\n");

                // prepare for the next turn
                tStart = tNow;
            }

            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
            }
        }


    }
}
