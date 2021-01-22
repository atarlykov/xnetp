package xnetp.poc.net;

import xnetp.poc.affinity.Affinity;
import xnetp.poc.sockets.RawSocket;

import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static xnetp.poc.net.PacketReceiver.BUF_PACKET_BYTES_MAX;


/**
 * main packet receiver thread, calls socket api, reuses buffers
 * and places buffers to queue for further processing
 */
public class RxThreadPacketReceiver extends Thread {

    // will track iterations
    public AtomicLong rxIterationCounter = new AtomicLong();

    // will track received packets
    public AtomicLong rxPacketCounter = new AtomicLong();

    // will track errors during receive
    public AtomicLong rxErrors = new AtomicLong();

    // last errno during packets' receive
    public AtomicInteger rxErrno = new AtomicInteger();

    // indicates there are no empty buffers to receive data,
    // processing takes too much time
    public AtomicLong rxNoFreeBuffers = new AtomicLong();

    // indicates there are no free buffers to receive data, for long time
    public AtomicLong rxNoFreeBuffersLong = new AtomicLong(0);

    // indicates all buffers are used,
    // processing takes too much time
    public AtomicLong rxUsedBuffersOverflow = new AtomicLong();


    // here we'll get free buffers to receive packets to
    private Queue<PacketBuffer>[] queueFreeBuffers;
    // there we'll send buffers with packets for processing
    private Queue<PacketBuffer>[] queueUsedBuffers;

    // threads' affinity, cput index
    private int affinity;

    // number of packets to receive for one syscall
    private int mmsgs;

    // reference to network api
    private RawSocket.RawSocket6 socket;

    /**
     *
     */
    public RxThreadPacketReceiver(
            int _affinity,
            int _mmsgs,
            Queue<PacketBuffer>[] _queueFreeBuffers,
            Queue<PacketBuffer>[] _queueUsedBuffers,
            RawSocket.RawSocket6 _rSocket)
    {
        if (_queueFreeBuffers.length != _queueUsedBuffers.length) {
            throw new RuntimeException("queues must be of the same length");
        }

        affinity = _affinity;
        mmsgs = _mmsgs;
        queueFreeBuffers = _queueFreeBuffers;
        queueUsedBuffers = _queueUsedBuffers;

        socket = _rSocket;
    }

    @Override
    public void run() {

        // set threads' affinity if requested
        if (affinity > 0) {
            Affinity.setAffinity(affinity);
        }


        int queues = queueFreeBuffers.length;
        int indexQueueFree = queues - 1;
        int indexQueueUsed;// = queues - 1;
        while (true) {
            // switch to the next queue to try on this turn
            // this drops performance as 4x !!! in synthetic test
            //indexQueueFree = (indexQueueFree == 0) ? queues - 1 : indexQueueFree - 1;

            // try 1st queue at first
            PacketBuffer buffer = queueFreeBuffers[indexQueueFree].poll();
            if (buffer == null) {
                rxNoFreeBuffers.incrementAndGet();
                indexQueueFree = (indexQueueFree == 0) ? queues - 1 : indexQueueFree - 1;

                int counter = 1000;
                while (((buffer = queueFreeBuffers[indexQueueFree].poll()) == null) && (0 < --counter)) {
                    indexQueueFree = (indexQueueFree == 0) ? queues - 1 : indexQueueFree - 1;
                    Thread.onSpinWait();
                }
                // back off and run long waits till data is available
                if ((counter == 0) && (buffer == null)) {
                    rxNoFreeBuffersLong.incrementAndGet();
                    while ((buffer = queueFreeBuffers[indexQueueFree].poll()) == null) {
                        indexQueueFree = (indexQueueFree == 0) ? queues - 1 : indexQueueFree - 1;
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException e) {
                        }
                    }
                }


            }

            // get data
            int packets = socket.recvmmsg(buffer.buffer, mmsgs, BUF_PACKET_BYTES_MAX);
            if (packets == -1) {
                // remember last error only
                rxErrors.incrementAndGet();
                //rxErrno.set(rSocket.errno());
                continue;
            }

            // count total received packets
            rxPacketCounter.addAndGet(packets);
            rxIterationCounter.incrementAndGet();

            // save number of packets received
            buffer.packets = packets;

            // we MUST cycle to not loose buffer,
            // use the same queue to return the buffer,
            // only helps in case if we cycled with free buffers queue
            indexQueueUsed = indexQueueFree;
            while (!queueUsedBuffers[indexQueueUsed].offer(buffer)) {
                // remember error
                rxUsedBuffersOverflow.incrementAndGet();
                indexQueueUsed = (indexQueueUsed == 0) ? queues - 1 : indexQueueUsed - 1;
                Thread.onSpinWait();
            }

/*
            long tNow = System.nanoTime();
            if (5e9 < tNow - tStart) {
                // print statistics
                long rxPackets = rxPacketCounter.getAndSet(0);
                System.out.println(String.format("receive pps: %,10d", (int)(1E9d / (tNow - tStart) * rxPackets)));
                // prepare for the next turn
                tStart = tNow;
            }
*/

        }

    }
}
