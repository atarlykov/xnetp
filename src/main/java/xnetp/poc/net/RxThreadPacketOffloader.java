package xnetp.poc.net;

import org.jctools.queues.atomic.SpscAtomicArrayQueue;

/**
 * processes received buffers with packets and offloads
 * them for further processing
 */
public class RxThreadPacketOffloader implements Runnable {

    public RxThreadPacketOffloader(
            SpscAtomicArrayQueue _queueFreeBuffers,
            SpscAtomicArrayQueue _queueUsedBuffers)
    {

    }

    @Override
    public void run() {

    }
}
