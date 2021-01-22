package xnetp.poc.net;

import xnetp.poc.affinity.Affinity;
import xnetp.poc.disk.IStateStore;
import xnetp.poc.sockets.RawSocket;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static xnetp.poc.net.PacketReceiver.BUF_PACKETS_MAX;
import static xnetp.poc.net.PacketReceiver.BUF_PACKET_BYTES_MAX;

/**
 * Main packet handler thread,
 * processes received buffers
 */
public class RxThreadPacketHandler extends Thread {

    // normalized diapason for percent parameters,
    // just to not use doubles
    private static final int PERCENT_LONG_NORMALIZED   = 1000;


    // will track received buffers
    public AtomicLong rxIterationCounter = new AtomicLong();

    // indicates there are no used buffers to receive data,
    // packets' receiving takes too much time
    public AtomicLong rxNoUsedBuffers = new AtomicLong(0);

    // indicates there are no used buffers to receive data, for long time
    public AtomicLong rxNoUsedBuffersLong = new AtomicLong(0);

    // indicates all buffers are used,
    // packets' receiving takes too much time
    public AtomicLong rxFreeBuffersOverflow = new AtomicLong();


    /**
     * various counters available externally
     */
    public AtomicLong rxPacketErrors = new AtomicLong();
    public AtomicLong rxPacketReplies = new AtomicLong();
    public AtomicLong rxPacketStores = new AtomicLong();
    public AtomicLong rxPacketWorkload = new AtomicLong();
    public AtomicLong txErrors = new AtomicLong();
    public AtomicInteger txErrno = new AtomicInteger();

    /**
     * accumulator to make sure workload is really calculated
     */
    private AtomicLong __workloadAccumulator = new AtomicLong();

    // here we'll get free buffers to receive packets to
    private Queue<PacketBuffer> queueFreeBuffers;
    // there we'll send buffers with packets for processing
    private Queue<PacketBuffer> queueUsedBuffers;

    // percent of packets to store
    private long percentToStore;
    // percent of packets to reply vie network
    private long percentToReply;
    // workload iteration
    private long workloadIterations;


    // pre-allocated buffer for reply data
    private PacketBuffer sendBuffer;

    // reference to network api
    private RawSocket.RawSocket6 socket;

    // instance to save state with
    public IStateStore store;

    // threads' affinity, cpu index
    public final int affinity;


    /**
     *
     * @param _queueFreeBuffers
     * @param _queueUsedBuffers
     * @param _percentToStore  percent [0..100]
     * @param _percentToReply percent [0..100]
     * @param _workloadIterations
     */
    public RxThreadPacketHandler(
            int _affinity,
            Queue<PacketBuffer> _queueFreeBuffers,
            Queue<PacketBuffer> _queueUsedBuffers,
            float _percentToStore,
            float _percentToReply,
            long _workloadIterations,
            byte[] _replyAddress,
            RawSocket.RawSocket6 _rSocket,
            IStateStore _store)
    {
        affinity = _affinity;
        queueFreeBuffers = _queueFreeBuffers;
        queueUsedBuffers = _queueUsedBuffers;

        // normalize percents to use with fixed point
        percentToStore = (long) (PERCENT_LONG_NORMALIZED * _percentToStore / 100f);
        percentToReply = (long) (PERCENT_LONG_NORMALIZED * _percentToReply / 100f);

        workloadIterations = _workloadIterations;

        socket = _rSocket;
        store = _store;

        // preallocate send buffer and populate it
        sendBuffer = PacketBuffer.allocate(BUF_PACKETS_MAX, BUF_PACKET_BYTES_MAX);
        for (int i = 0; i < BUF_PACKETS_MAX; i++) {
            // destination addresses
            sendBuffer.populateSlotAddress(i, _replyAddress);
            // headers with fixed values
            sendBuffer.populatePacketExtHeaderTemplate(i);
            // test payload
            sendBuffer.populatePacketPayloadTemplate(i, BUF_PACKET_BYTES_MAX);
        }

    }

    @Override
    public void run() {

        // set threads' affinity if requested
        if (affinity > 0) {
            Affinity.setAffinity(affinity);
        }


        while (true) {
            PacketBuffer buffer = queueUsedBuffers.poll();
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

                    counter = 10;
                    while (((buffer = queueUsedBuffers.poll()) == null) && (0 < --counter)) {
                        LockSupport.parkNanos(1);
                    }

                    // still no buffer, sleep to release the cpu
                    if ((counter == 0) && (buffer == null)) {
                        while ((buffer = queueUsedBuffers.poll()) == null) {
                            try {
                                Thread.sleep(1);
                            } catch (InterruptedException e) {
                            }
                        }
                    }
                }
            }

            //
            //rxIterationCounter.incrementAndGet();

            // count received buffers
            rxIterationCounter.incrementAndGet();

            process(buffer);


            // return buffer to receiver, MUST cycle to not loose buffer
            while (!queueFreeBuffers.offer(buffer)) {
                // that is strange, there always should be room there
                rxFreeBuffersOverflow.incrementAndGet();
                Thread.onSpinWait();
            }
        }

    }

    /**
     * processes packets' data, imitates xnetp
     * @param buffer buffer with packets
     */
    private void process(PacketBuffer buffer) {

        ThreadLocalRandom random = ThreadLocalRandom.current();

        // reset number of replies
        sendBuffer.packets = 0;

        // precess received packets
        for (int i = 0; i < buffer.packets; i++) {

            boolean valid = buffer.validate(i);

            // skip error packets, but count them
            if (!valid) {
                rxPacketErrors.incrementAndGet();
                continue;
            }

            boolean processed = false;
            if (random.nextInt(PERCENT_LONG_NORMALIZED) < percentToStore) {
                store(buffer, i);
                rxPacketStores.incrementAndGet();
                processed = true;
            }

            if (random.nextInt(PERCENT_LONG_NORMALIZED) < percentToReply) {
                accumulateReply(buffer, i);
                rxPacketReplies.incrementAndGet();
                processed = true;

                // simulate workload for all replies
                if (0 < workloadIterations) {
                    __workloadAccumulator.set(workload());
                    rxPacketWorkload.incrementAndGet();
                }
            }

//            if (!processed) {
//                __workloadAccumulator.set(workload());
//                rxPacketWorkload.incrementAndGet();
//            }
        }

        // packets number updated by accumulateReply
        if (sendBuffer.packets > 0) {
            sendReplies();
        }
    }

    /**
     * send store request to disk layer for the received
     * node UNI
     * @param rxBuffer buffer with received packets
     * @param rxSlot slot in rx buffer
     */
    private void store(PacketBuffer rxBuffer, int rxSlot) {
        byte[] uni = rxBuffer.getUNI(rxSlot);
        try {
            store.save(uni);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * NOTE: incoming packet MUST be of a correct type
     * @param rxBuffer buffer with received packets
     * @param rxSlot slot in rx buffer
     */
    private void accumulateReply(PacketBuffer rxBuffer, int rxSlot) {
        byte pType = rxBuffer.getPacketTypeAsByte(rxSlot);

        sendBuffer.setPacketType(sendBuffer.packets, pType);
        sendBuffer.setPacketLength(sendBuffer.packets, PacketType.byType(pType).length);
        sendBuffer.packets++;
    }

    /**
     * sends accumulated replies to the specified address
     */
    private void sendReplies() {
        int result = socket.sendmmsg(sendBuffer.buffer, sendBuffer.packets, sendBuffer.slotSize);
        if (result == -1) {
            txErrno.set(socket.errno());
            txErrors.incrementAndGet();
        }
    }

    /**
     * emulates workload
     */
    private long workload() {
        long accumulator = 0;
        for (int i = 0; i < workloadIterations; i++) {
            accumulator += i;
        }
        return accumulator;
    }

}
