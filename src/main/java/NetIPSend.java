
import xnetp.poc.sockets.RawSocket;
import xnetp.poc.sockets.RawSocket.RawSocket4;
import xnetp.poc.sockets.RawSocket.RawSocket6;

import java.net.InetAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;


public class NetIPSend {

    public static void main(String[] args) throws Exception {

        InetAddress w = InetAddress.getByName("fe80::5b3:cfa5:22bb:7a6b"); // windows7
        InetAddress v = InetAddress.getByName("fe80::a00:27ff:fe4e:9c62"); // vbox

        //txMmsg();

        //txSimple();
        //tx6Simple();

        tx();
    }


    public static void txMmsg() {

        final int PACKETS = 4;
        final int PACKET_BLOCK_SIZE = 1024;

        ByteBuffer buffer = ByteBuffer.allocateDirect(16384);
        buffer.order(ByteOrder.nativeOrder());

        for (int packet = 0; packet < PACKETS; packet++) {
            int pBase = packet * PACKET_BLOCK_SIZE;

            buffer.put(pBase + 0, (byte)192);
            buffer.put(pBase + 1, (byte)168);
            buffer.put(pBase + 2, (byte)1);
            buffer.put(pBase + 3, (byte)136);

            buffer.putInt(pBase + 16, 1);      // NATIVE order

//            System.out.println("    java length");
//            System.out.println("        " + buffer.get(pBase + 16 + 0));
//            System.out.println("        " + buffer.get(pBase + 16 + 1));
//            System.out.println("        " + buffer.get(pBase + 16 + 2));
//            System.out.println("        " + buffer.get(pBase + 16 + 3));

            buffer.put(pBase + 32 + 0, (byte)packet);
        }

        RawSocket4 rSocket = (RawSocket4) RawSocket.open(RawSocket.AF_INET, 253);
        int result = rSocket.sendmmsg(buffer, PACKETS, PACKET_BLOCK_SIZE);
        if (result == -1) {
            System.out.println("error: " + rSocket.errno());
        }
        rSocket.close();
        System.out.println("mmsg packets sent");
    }

    public static void txSimple() {
        RawSocket4 rSocket = (RawSocket4) RawSocket.open(RawSocket.AF_INET, 253);

        ByteBuffer buffer = ByteBuffer.allocateDirect(2048);
        buffer.put(0, (byte)192);
        buffer.put(1, (byte)168);
        buffer.put(2, (byte)1);
        buffer.put(3, (byte)136);

        buffer.position(16);
        buffer.put(new byte[] {'H', 'e', 'l', 'l', 'o'});

        rSocket.send(buffer, 5);

        rSocket.close();
        System.out.println("packet sent");
    }

    public static void tx6Simple() throws Exception {
        RawSocket6 rSocket = (RawSocket6) RawSocket.open(RawSocket.AF_INET6, 253);

        ByteBuffer buffer = ByteBuffer.allocateDirect(2048);
        buffer.put(InetAddress.getByName("fe80::5b3:cfa5:22bb:7a6b").getAddress());



        buffer.position(16);
        buffer.put(new byte[] {'H', 'e', 'l', 'l', 'o'});

        rSocket.send(buffer, 5);

        rSocket.close();
        System.out.println("packet sent");
    }


    static class TxThread implements Runnable {
        BlockingQueue<SocketAddress> queue;
        AtomicLong counter;
        AtomicBoolean overflow;

        public TxThread(BlockingQueue<SocketAddress> _queue, AtomicLong _counter, AtomicBoolean _overflow) {
            queue = _queue;
            counter = _counter;
            overflow = _overflow;
        }

        @Override
        public void run() {
            try {
                System.out.println("starting tx thread ...");

                ByteBuffer buffer = ByteBuffer.allocateDirect(2048);
                buffer.put(0, (byte)172);
                buffer.put(1, (byte)16);
                buffer.put(2, (byte)76);
                buffer.put(3, (byte)138);

                buffer.position(16);
                buffer.put(new byte[] {'H', 'e', 'l', 'l', 'o'});

                RawSocket4 rSocket = (RawSocket4) RawSocket.open(RawSocket.AF_INET, 253);

                long queueOverflow = 0;
                while (true) {

                    int result = rSocket.send(buffer, 5);
                    if (result == -1) {
                        System.out.println("error sending, errno: " + rSocket.errno());
                        break;
                    }

                    //if (!queue.offer(rPacket.getSocketAddress())) {
                    //    queueOverflow++;
                    //}

                    counter.incrementAndGet();
                }

                rSocket.close();
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    static class Tx6Thread implements Runnable {
        BlockingQueue<SocketAddress> queue;
        AtomicLong counter;
        AtomicBoolean overflow;

        public Tx6Thread(BlockingQueue<SocketAddress> _queue, AtomicLong _counter, AtomicBoolean _overflow) {
            queue = _queue;
            counter = _counter;
            overflow = _overflow;
        }

        @Override
        public void run() {
            try {
                System.out.println("starting tx6 thread ...");

                InetAddress destination = InetAddress.getByName("fe80::5b3:cfa5:22bb:7a6b");


                ByteBuffer buffer = ByteBuffer.allocateDirect(2048).order(ByteOrder.nativeOrder());

                buffer.position(8).put(destination.getAddress());
                buffer.position(28).putInt(5);
                buffer.position(32).put(new byte[] {'H', 'e', 'l', 'l', 'o'});

                RawSocket6 rSocket = (RawSocket6) RawSocket.open(RawSocket.AF_INET6, 253);

                long queueOverflow = 0;
                while (true) {

                    int result = rSocket.send(buffer, 5);
                    if (result == -1) {
                        //System.out.println("error sending, errno: " + rSocket.errno());
                        //break;
                        Thread.yield();
                    }

                    //if (!queue.offer(rPacket.getSocketAddress())) {
                    //    queueOverflow++;
                    //}

                    counter.incrementAndGet();
                }

                //rSocket.close();
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    static class TxMmsgThread implements Runnable {
        BlockingQueue<SocketAddress> queue;
        AtomicLong counter;
        AtomicBoolean overflow;

        public TxMmsgThread(BlockingQueue<SocketAddress> _queue, AtomicLong _counter, AtomicBoolean _overflow) {
            queue = _queue;
            counter = _counter;
            overflow = _overflow;
        }

        @Override
        public void run() {
            try {
                final int PACKETS = 4;
                final int PACKET_BLOCK_SIZE = 1024;

                System.out.println("starting tx mmsg thread, packets:  " + PACKETS + "...");

                ByteBuffer buffer = ByteBuffer.allocateDirect(16384);
                buffer.order(ByteOrder.nativeOrder());

                for (int packet = 0; packet < PACKETS; packet++) {
                    int pBase = packet * PACKET_BLOCK_SIZE;

                    buffer.put(pBase + 0, (byte)172);
                    buffer.put(pBase + 1, (byte)16);
                    buffer.put(pBase + 2, (byte)76);
                    buffer.put(pBase + 3, (byte)138);

                    buffer.putInt(pBase + 16, 128);      // NATIVE order

                    buffer.put(pBase + 32 + 0, (byte)packet);
                }


                RawSocket4 rSocket = (RawSocket4) RawSocket.open(RawSocket.AF_INET, 253);

                long queueOverflow = 0;
                while (true) {

                    int result = rSocket.sendmmsg(buffer, PACKETS, PACKET_BLOCK_SIZE);
                    if (result == -1) {
                        //System.out.println("error sending, errno: " + rSocket.errno());
                        //break;
                        overflow.set(true);
                    } else {
                        counter.addAndGet(result);
                    }

                    //if (!queue.offer(rPacket.getSocketAddress())) {
                    //    queueOverflow++;
                    //}

                    //counter.incrementAndGet();
                }

                //rSocket.close();
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    public static void tx() throws Exception {
        // service
        ExecutorService executor = Executors.newFixedThreadPool(16);

        final int TX_THREADS = 1;

        // exchange point
        ArrayBlockingQueue<SocketAddress> queue = new ArrayBlockingQueue<>(64, true);
        // errors
        AtomicBoolean overflow = new AtomicBoolean();

        // counters
        AtomicLong[] txCounters = new AtomicLong[] {
                new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong(),
                new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong()
        };
        //AtomicLong rxCounter = new AtomicLong();

        for (int i = 0; i < TX_THREADS; i++) {
            //executor.submit(new TxThread(queue, txCounters[i], overflow));
            executor.submit(new Tx6Thread(queue, txCounters[i], overflow));
        }

        // main cycle
        long tStart = System.currentTimeMillis();
        while (true) {
            long tEnd = System.currentTimeMillis();
            if (5000 < tEnd - tStart) {
                long txPackets = 0;
                long[] txCountersCopy = new long[TX_THREADS];
                for (int i = 0; i < TX_THREADS; i++) {
                    txCountersCopy[i] = txCounters[i].getAndSet(0);
                    txPackets += txCountersCopy[i];
                }
                System.out.println("send total pps: " + txPackets * 1000 / (tEnd - tStart) + "   queue overflow: " + overflow.get());
                for (int i = 0; i < TX_THREADS; i++) {
                    System.out.println("  send t#" + i + " pps: " + txCountersCopy[i] * 1000 / (tEnd - tStart));
                }
                overflow.set(false);
                tStart = tEnd;
            }
            Thread.sleep(100);
        }
    }

}
