
import xnetp.poc.affinity.Affinity;
import xnetp.poc.sockets.RawSocket;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;


public class NetIPReceive {

    public static void main(String[] args) throws Exception {
        rx();
        //rxmmsg();
        //rxmmsg6();
    }

    static class RxThread implements Runnable {
        public AtomicLong counter = new AtomicLong();
        int cpu;

        public RxThread(int _cpu) {
            cpu = _cpu;
        }

        @Override
        public void run() {
            try {
                if (cpu != 0) {
                    Affinity.setAffinity(cpu);
                }
                System.out.println("starting rx thread ...   cpu# " + cpu);

                RawSocket.RawSocket6 rSocket = (RawSocket.RawSocket6) RawSocket.open(RawSocket.AF_INET6, 253);

                ByteBuffer buffer = ByteBuffer.allocateDirect(2048);

                while (true) {
                    int bytes = rSocket.receive(buffer);
                    counter.incrementAndGet();
                    System.out.println("cpu#" + cpu + "  received: " + bytes);
                }
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    public static void rx() throws Exception {
        // service
        ExecutorService executor = Executors.newFixedThreadPool(16);

        final int RX_THREADS = 2;

        // errors
        AtomicBoolean overflow = new AtomicBoolean();

        RxThread[] threads = new RxThread[RX_THREADS];

        for (int i = 0; i < RX_THREADS; i++) {
            threads[i] =  new RxThread(4 + i);
            executor.submit(threads[i]);
        }


        // main cycle
//        long tStart = System.currentTimeMillis();
//        while (true) {
//            long tEnd = System.currentTimeMillis();
//            if (5000 < tEnd - tStart) {
//                long rxPackets = 0;
//                long[] rxCountersCopy = new long[RX_THREADS];
//                for (int i = 0; i < RX_THREADS; i++) {
//                    rxCountersCopy[i] = threads[i].counter.getAndSet(0);
//                    rxPackets += rxCountersCopy[i];
//                }
//                System.out.println("rcv total pps: " + rxPackets * 1000 / (tEnd - tStart) + "   queue overflow: " + overflow.get());
//                for (int i = 0; i < RX_THREADS; i++) {
//                    System.out.println("rcv   t#" + i + " pps: " + rxCountersCopy[i] * 1000 / (tEnd - tStart));
//                }
//                overflow.set(false);
//                tStart = tEnd;
//            }
//            Thread.sleep(100);
//        }
    }

    static void p(ByteBuffer b, int from, int len) {
        int to = from + len;
        for (int i = from; i < to; i++) {
            int d = b.get(i);
            d &= 0x000000FF;
            System.out.print(String.format("%02X:", d));
        }
        System.out.println();
    }

    public static void rxmmsg6() throws Exception {

        RawSocket.RawSocket6 rSocket = (RawSocket.RawSocket6) RawSocket.open(RawSocket.AF_INET6, 17);
        ByteBuffer buffer = ByteBuffer.allocateDirect(16384);
        buffer.order(ByteOrder.nativeOrder());
        System.out.println(buffer.order().toString());

        int packets = rSocket.recvmmsg(buffer, 16, 1024);
        System.out.println("received: " + packets);
        if (packets > 0) {
            for (int i = 0; i < packets; i++) {
                int base = i*1024;
                p(buffer, base, 44);
                System.out.println("    len: " + buffer.getInt(i*1024 + 28));
            }
        }

        rSocket.close();
    }
}
