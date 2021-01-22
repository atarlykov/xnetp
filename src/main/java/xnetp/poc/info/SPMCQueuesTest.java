package xnetp.poc.info;

import xnetp.poc.affinity.Affinity;
import org.jctools.queues.atomic.MpscAtomicArrayQueue;
import org.jctools.queues.atomic.SpmcAtomicArrayQueue;

import java.util.concurrent.atomic.AtomicLong;

public class SPMCQueuesTest {


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
        private MpscAtomicArrayQueue<Object> queueFreeBuffers;
        // there we'll send buffers with packets for processing
        private SpmcAtomicArrayQueue<Object> queueUsedBuffers;

        // threads' affinity, cput index
        private int affinity;

        public Producer(
                int _affinity,
                MpscAtomicArrayQueue<Object> _queueFreeBuffers,
                SpmcAtomicArrayQueue<Object> _queueUsedBuffers) {
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
                // can return null
                Object buffer = queueFreeBuffers.poll();
                if (buffer == null) {
                    // no free buffers
                    rxNoFreeBuffers.incrementAndGet();
                    while ((buffer = queueFreeBuffers.poll()) == null) {
                        Thread.onSpinWait();
                    }
                }

                rxIterationCounter.addAndGet(1);

                // we MUST cycle to not loose buffer
                while (!queueUsedBuffers.offer(buffer)) {
                    // remember error
                    rxUsedBuffersOverflow.incrementAndGet();
                    Thread.onSpinWait();
                }
            }
        }
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
        private MpscAtomicArrayQueue<Object> queueFreeBuffers;
        // there we'll send buffers with packets for processing
        private SpmcAtomicArrayQueue<Object> queueUsedBuffers;


        // threads' affinity, cpu index
        public final int affinity;


        /**
         *
         */
        public Consumer(
                int _affinity,
                MpscAtomicArrayQueue<Object> _queueFreeBuffers,
                SpmcAtomicArrayQueue<Object> _queueUsedBuffers)
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
//                            Thread.yield(); // this gives 100% cpu load
                            
                            try {
                                Thread.sleep(1);
                            } catch (InterruptedException e) {
                            }

//                            Thread.onSpinWait();
                        }
                    }

                    //Thread.yield(); // moving this to spinWait gives 10mln/sec --> 11.3mln/sec
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
        MpscAtomicArrayQueue<Object> rxQueueFreeBuffers = new MpscAtomicArrayQueue<>(RECEIVE_BUFFERS);
        SpmcAtomicArrayQueue<Object> rxQueueUsedBuffers = new SpmcAtomicArrayQueue<>(RECEIVE_BUFFERS);
        for (int i = 0; i < RECEIVE_BUFFERS; i++) {
            rxQueueFreeBuffers.offer(new Object());
        }

        // allocate threads
        Producer producer = new Producer(0, rxQueueFreeBuffers, rxQueueUsedBuffers);

        Consumer[] consumers = new Consumer[CONSUMERS];
        for (int i = 0; i < CONSUMERS; i++) {
            // get affinity for thread
            int tAffinity = (i < CONSUMERS_AFFINITY.length) ? CONSUMERS_AFFINITY[i] : 0;

            consumers[i] = new Consumer(
                    tAffinity,
                    rxQueueFreeBuffers, rxQueueUsedBuffers);
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
