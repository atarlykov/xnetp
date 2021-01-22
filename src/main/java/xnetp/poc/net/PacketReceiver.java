package xnetp.poc.net;

import xnetp.poc.affinity.Affinity;
import xnetp.poc.disk.DiskThreadTLogBufferHandler;
import xnetp.poc.disk.IStateStore;
import xnetp.poc.disk.StateStoreFactory;
import xnetp.poc.disk.StateStoreParameters;
import xnetp.poc.sockets.RawSocket;
import org.jctools.queues.atomic.SpscAtomicArrayQueue;
import picocli.CommandLine;

import java.util.concurrent.*;


@CommandLine.Command(name = "receiver", usageHelpWidth = 120,
        description = "Consumes ipv6 packet load")
public class PacketReceiver implements Callable<Integer> {

    @CommandLine.Mixin
    private PacketReceiverParameters rxParameters = new PacketReceiverParameters();

    /**
     * maximum amount in buffer any packet could take
     * including header
     */
    public final static int BUF_PACKET_BYTES_MAX = 1024;
    /**
     * maximum number of packets that could be passed via system call
     * and stores in buffer (as a result)
     */
    public final static int BUF_PACKETS_MAX = 1024;


    /**
     * xnetp protocol
     */
    public final static int XNETP_PROTOCOL_TYPE = 253;

    /**
     * size of extended ip header in xnetp packets
     */
    private final static int XNETP_EXT_HDR_LENGTH = 40;

    /**
     * number of receive buffers that are used to store
     * packet data and pass it for processing
     */
    private static final int RECEIVE_BUFFERS    = 1024;


    /**
     * entry point
     *
     * @param args arguments
     */
    public static void main(String[] args) {
        int result = new CommandLine(PacketReceiver.class).execute(args);
        System.exit(result);
    }


    @Override
    public Integer call() {
        try {
            // simple wrapper to catch and dump exception
            // instead of picocli
            return _call();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return -1;
    }


    /**
     * application logic
     *
     * @return nothing
     * @throws Exception if any
     */
    private Integer _call() throws Exception {

        // perform post validation and dump
        rxParameters.validate(BUF_PACKETS_MAX);
        rxParameters.dump();

        // receive socket
        RawSocket.RawSocket6 socket = (RawSocket.RawSocket6) RawSocket.open(RawSocket.AF_INET6, XNETP_PROTOCOL_TYPE);

        /*
         * single thread mode has it's own strange path,
         * so we have separate method
         */
        if (rxParameters.singleThreadMode) {
            singleThreadLogic(socket);
            return 0;
        }


        // setup receive buffers and interchange queues
        SpscAtomicArrayQueue<PacketBuffer>[] rxQueueFreeBuffers = new SpscAtomicArrayQueue[rxParameters.handlerThreadsNum];
        SpscAtomicArrayQueue<PacketBuffer>[] rxQueueUsedBuffers = new SpscAtomicArrayQueue[rxParameters.handlerThreadsNum];

        for (int i = 0; i < rxParameters.handlerThreadsNum; i++) {
            rxQueueFreeBuffers[i] = new SpscAtomicArrayQueue<>(RECEIVE_BUFFERS);
            rxQueueUsedBuffers[i] = new SpscAtomicArrayQueue<>(RECEIVE_BUFFERS);

            for (int e = 0; e < RECEIVE_BUFFERS; e++) {
                PacketBuffer buffer = PacketBuffer.allocate(BUF_PACKETS_MAX, BUF_PACKET_BYTES_MAX);
                rxQueueFreeBuffers[i].offer(buffer);
            }
        }


        // initialize store for packets' storing
        StateStoreFactory ssFactory = null;
        if (0 < rxParameters.handlerPercentToStore) {
            StateStoreParameters ssParameters = rxParameters.buildStateStoreParameters();

            if (ssParameters.storeMode != StateStoreParameters.StoreMode.tslog) {
                ssFactory = new StateStoreFactory(ssParameters);
            } else {
                // build our own custom factory for tlog mode
                ssFactory = new StateStoreFactory(ssParameters) {
                    DiskThreadTLogBufferHandler writer = null;
                    {
                        writer = new DiskThreadTLogBufferHandler(
                                rxParameters.tlogAffinity, rxParameters.tlogBufferSize,
                                rxParameters.tlogTimeout, ssParameters);
                        writer.init();
                        writer.start();
                    }
                    @Override
                    public IStateStore getStateStore() {
                        return writer;
                    }
                };
            }
        }

        // allocate threads
        RxThreadPacketReceiver receiver = new RxThreadPacketReceiver(
                rxParameters.receiverAffinity, rxParameters.mmsgs, rxQueueFreeBuffers, rxQueueUsedBuffers, socket);

        RxThreadPacketHandler[] handlers = new RxThreadPacketHandler[rxParameters.handlerThreadsNum];
        for (int i = 0; i < rxParameters.handlerThreadsNum; i++) {
            // get affinity for thread
            int tAffinity = (i < rxParameters.handlersAffinity.length) ? rxParameters.handlersAffinity[i] : 0;
            // get store instance for thread
            IStateStore store = (ssFactory != null) ? ssFactory.getStateStore() : null;

            // allocate separate tx socket and switch of it's rx buffer
            RawSocket.RawSocket6 txSocket = null;
            if (0 < rxParameters.handlerPercentToReply) {
                // we don't initialize tx socket if replies are not necessary,
                // just one opened socket leads to "100k+" pps drop and increased cpu load
                txSocket = (RawSocket.RawSocket6) RawSocket.open(RawSocket.AF_INET6, XNETP_PROTOCOL_TYPE);
                txSocket.setReceivedBufferSize(0);
            }


            handlers[i] = new RxThreadPacketHandler(
                    tAffinity,
                    rxQueueFreeBuffers[i], rxQueueUsedBuffers[i],
                    rxParameters.handlerPercentToStore,
                    rxParameters.handlerPercentToReply,
                    rxParameters.handlerWorkloadIterations,
                    rxParameters.replyAddress.getAddress(),
                    txSocket, store);
        }

        // run the processing
        for (int i = 0; i < rxParameters.handlerThreadsNum; i++) {
            handlers[i].start();
        }
        receiver.start();

        // run metrics' cycle
        collectMetrics(receiver, handlers);

        return 0;
    }

    /**
     * collects metrics from threads and renders them to console
     * @param receiver receiver thread
     * @param handlers handler threads
     * @throws InterruptedException if any
     */
    private void collectMetrics(RxThreadPacketReceiver receiver, RxThreadPacketHandler[] handlers)
            throws InterruptedException
    {
        // period in nanoseconds
        final long msPeriod = rxParameters.statisticsPeriodMs;
        final long period = msPeriod * 1_000_000L;

        // collect and print statistics
        long tStart = System.nanoTime();
        while (true) {
            long tNow = System.nanoTime();
            if (period < tNow - tStart) {
                // scale factor for per sec outouts
                double factor = 1E9 / (tNow - tStart);

                long rxIterations       = receiver.rxIterationCounter.getAndSet(0);
                long rxPackets          = receiver.rxPacketCounter.getAndSet(0);
                long rxErrors           = receiver.rxErrors.getAndSet(0);
                int  rxErrno            = receiver.rxErrno.getAndSet(0);
                long rxNoFreeBuffers    = receiver.rxNoFreeBuffers.getAndSet(0);
                long rxNoFreeBuffersLong   = receiver.rxNoFreeBuffersLong.getAndSet(0);
                long rxUsedBuffersOverflow = receiver.rxUsedBuffersOverflow.getAndSet(0);

/*
---- [iteration: 5000 ms] ----------------------------------------------------------------------------------------------------------------------------------------
     [receive]                  packets/s            iterations        no free buffers   used buffers o/flow     errors       errno
                                    12345                 23456                  false                 false          0

     [handlers:  per sec]                                         |   [per iteration]
  ----------------------------------------------------------------|-----------------------------------------------------------------------------------------------
     th    err pkts    w/loads     stores    replies   r/errors   |    iters    perrors    w/loads     stores    replies   r/errors    errno      nub        fbo
      0           0          0          0          0          0   |        0          0          0          0          0          0        0     true      false
      0           0          0          0          0          0   |        0          0          0          0          0          0        0     true      false
      0           0          0          0          0          0   |        0          0          0          0          0          0        0     true      false

     [transaction log]  b/rate     stores      waits     errors   |              stores     writes     errors
  ----------------------------------------------------------------|-----------------------------------------------------------------------------------------------
                      8.33 M/s          0          0          0   |                   0          0          0
*/
                System.out.print("---- [iteration: ");
                System.out.print(msPeriod);
                System.out.println(" ms] ----------------------------------------------------------------------------------------------------------------------------------------");

                // print receiver statistics
                System.out.println("     [receive]                  packets/s            iterations         no free buffers        nfbL  used buffers o/flow     errors       errno");
                System.out.println(String.format("                               %10d            %10d              %10d  %10d           %10d   %8d      %6d\n",
                        (int)(factor * rxPackets), rxIterations, rxNoFreeBuffers, rxNoFreeBuffersLong, rxUsedBuffersOverflow, rxErrors, rxErrno));


                // print processing statistics
                System.out.println("     [handlers:  per sec]                                         |   [per iteration]");
                System.out.println("  ----------------------------------------------------------------|-----------------------------------------------------------------------------------------------");
                System.out.println("    cpu    err pkts    w/loads     stores    replies  tx/errors   |    iters    perrors    w/loads     stores    replies   r/errors    errno      nub        fbo");
                for (int i = 0; i < rxParameters.handlerThreadsNum; i++) {
                    RxThreadPacketHandler handler = handlers[i];
                    long hIterations         = handler.rxIterationCounter.getAndSet(0);
                    long hNoUsedBuffers      = handler.rxNoUsedBuffers.getAndSet(0);
                    long hFreeBuffersOverflow = handler.rxFreeBuffersOverflow.getAndSet(0);
                    long hPacketErrors       = handler.rxPacketErrors.getAndSet(0);
                    long hPacketReplies      = handler.rxPacketReplies.getAndSet(0);
                    long hPacketStores       = handler.rxPacketStores.getAndSet(0);
                    long hPacketWorkload     = handler.rxPacketWorkload.getAndSet(0);
                    long hErrors             = handler.txErrors.getAndSet(0);
                    int  hErrno              = handler.txErrno.getAndSet(0);

                    String line = String.format(
                            "     %2d  %10d %10d %10d %10d %10d   |%9d %10d %10d %10d %10d %10d %8d %8d   %8d",
                            handlers[i].affinity, (int)(factor*hPacketErrors), (int)(factor*hPacketWorkload),
                            (int)(factor*hPacketStores), (int)(factor*hPacketReplies), (int)(factor*hErrors),
                            hIterations, hPacketErrors, hPacketWorkload, hPacketStores, hPacketReplies, hErrors, hErrno,
                            hNoUsedBuffers, hFreeBuffersOverflow);
                    System.out.println(line);
                }
                System.out.println();

                // transaction log, this is hack to get real writer
                if (handlers[0].store instanceof DiskThreadTLogBufferHandler) {
                    DiskThreadTLogBufferHandler writer = (DiskThreadTLogBufferHandler) handlers[0].store;

                    long tlErrors = writer.tlErrors.getAndSet(0);
                    long tlStores = writer.tlStores.getAndSet(0);
                    long tlWaits = writer.tlWaits.getAndSet(0);
                    double writeRate = factor * tlStores * rxParameters.storeStateSize * rxParameters.tlogBufferSize / (1024*1024);

                    System.out.println("     [transaction log]  b/rate     stores      waits     errors   |              stores      waits     errors");
                    System.out.println("  ----------------------------------------------------------------|-----------------------------------------------------------------------------------------------");
                    String line = String.format("                   %7.2f M/s %10d %10d %10d   |          %10d %10d %10d",
                            writeRate,
                            (int)(factor * tlStores), (int)(factor * tlWaits), (int)(factor * tlErrors),
                            tlStores, tlWaits, tlErrors);
                    System.out.println(line);
                }

                System.out.println("\n\n\n");

                // prepare for the next turn
                tStart = tNow;
            }

            Thread.sleep(100);
        }
    }

    /**
     * implements logic of simplified single thread mode,
     * only subset of parameters are included
     */
    private void singleThreadLogic(RawSocket.RawSocket6 socket) {

        PacketBuffer buffer = PacketBuffer.allocate(BUF_PACKETS_MAX, BUF_PACKET_BYTES_MAX);

        // packets' counter
        long pCounter = 0;
        // errors' counter
        long eCounter = 0;

        long tStart = System.nanoTime();
        int errno = 0;


        // bind this thread to cpu if requested
        if (rxParameters.receiverAffinity != 0) {
            Affinity.setAffinity(rxParameters.receiverAffinity);
        }

        // calc statistics period in ns
        long statPeriod = rxParameters.statisticsPeriodMs * 1_000_000;

        // main cycle
        while (true) {
            int packets = socket.recvmmsg(buffer.buffer, rxParameters.mmsgs, BUF_PACKET_BYTES_MAX);
            if (packets == -1) {
                // remember last error only
                errno = socket.errno();
                eCounter++;
                continue;
            }

            if (rxParameters.dump) {
                buffer.dump(packets);
            }

            pCounter += packets;

            long tNow = System.nanoTime();
            if (statPeriod < tNow - tStart) {
                // print statistics
                System.out.print(String.format("receive pps: %,10d   errors: %6d", (int)(1E9d / (tNow - tStart) * pCounter), eCounter));
                if (eCounter > 0) {
                    System.out.print("    last errno: " + errno);
                }
                System.out.println();

                // prepare for the next turn
                pCounter = 0;
                eCounter = 0;
                tStart = tNow;
            }
        }

        //rSocket.close();
    }

}
