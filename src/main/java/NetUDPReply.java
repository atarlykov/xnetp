import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;

import java.net.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * results
 *   - 100Mbit ->  187kps with 560 bit packets (udp with 28 bytes payload)
 *   -   1Gbit -> 1917kps with 560 bit packets
 *
 *   - .255, server with nvidia, i5-4670 @3.4GHz, /proc/sys/net/core/wmem_default = 212992,
 *          Intel Corporation Ethernet Connection I217-V (rev 04), 1Gbit
 *   - .138, server kev, i5-4670 @3.4GHz,
 *          Intel Corporation Ethernet Connection I217-V (rev 04), 1Gbit
 *
 *   1. sendmmsg * 1024, payload 28b, .255 -> sut -> .138
 *       send pps  ~ 1_027 kpps
 *       recv pps
 *                 ~   580 kpps with recvfrom in c
 *                 ~ 1_02x kps with java nio channel (#1 thread really works)
 *                 ~ 1_02x kps with java sockets (#1 thread really works)
 *                 ~   500 kps with netty nio
 *                 ~   440 kps with netty epoll
 *
 */
public class NetUDPReply {


    public static void main(String[] args) throws Exception {
        //rxSimple();
        //rxNioWithThreads();
        //rxNetty();
        rxSimpleWithThreads();
    }

    public static void rxSimple() throws Exception {
        // receive part
        DatagramSocket rSocket = new DatagramSocket(4321);
        rSocket.setReceiveBufferSize(4*1024*1024);
        //rSocket.setReuseAddress(true);
        //rSocket.setOption(StandardSocketOptions.SO_REUSEPORT, true);

        byte[] rData = new byte[4096];
        DatagramPacket rPacket = new DatagramPacket(rData, rData.length);


        // exchange point
        ArrayBlockingQueue<SocketAddress> queue = new ArrayBlockingQueue<>(64, true);

        // send part
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            try {
                // send part
                DatagramSocket sSocket = new DatagramSocket();
                sSocket.setSendBufferSize(4*1024*1024);
                byte[] sData = new byte[28];
                DatagramPacket sPacket = new DatagramPacket(
                        sData, sData.length);

                long tStart = System.currentTimeMillis();
                long counter = 0;
                while (true) {
                    SocketAddress sAddress = queue.take();
                    sPacket.setSocketAddress(sAddress);
                    sSocket.send(sPacket);
                    counter++;

                    long tEnd = System.currentTimeMillis();
                    if (5000 < tEnd - tStart) {
                        System.out.println("send pps: " + counter  * 1000 / (tEnd - tStart));
                        counter = 0;
                        tStart = tEnd;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });


        long tStart = System.currentTimeMillis();
        long counter = 0;
        long queueOverflow = 0;
        while (true) {
            rSocket.receive(rPacket);
            if (!queue.offer(rPacket.getSocketAddress())) {
                queueOverflow++;
            }
            counter++;

            long tEnd = System.currentTimeMillis();
            if (5000 < tEnd - tStart) {
                System.out.println("rcv  pps: " + counter  * 1000 / (tEnd - tStart) + "   queue overflow: " + queueOverflow);
                queueOverflow = 0;
                counter = 0;
                tStart = tEnd;
            }
        }

        //sSocket.close();
        //rSocket.close();
    }


    static class RxNIOThread implements Runnable {
        BlockingQueue<SocketAddress> queue;
        AtomicLong counter;
        AtomicBoolean overflow;

        public RxNIOThread(BlockingQueue<SocketAddress> _queue, AtomicLong _counter, AtomicBoolean _overflow) {
            queue = _queue;
            counter = _counter;
            overflow = _overflow;
        }

        @Override
        public void run() {
            try {
                // receive part
                java.nio.channels.DatagramChannel channel = java.nio.channels.DatagramChannel.open(StandardProtocolFamily.INET);
                channel.setOption(StandardSocketOptions.SO_REUSEPORT, true);
                channel.setOption(StandardSocketOptions.SO_RCVBUF, 4 * 1024 * 1024);
                channel.bind(new InetSocketAddress(4321));

                ByteBuffer rData = ByteBuffer.allocateDirect(256);

                while (true) {
                    SocketAddress sa = channel.receive(rData);
                    counter.incrementAndGet();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
    }

    public static void rxNioWithThreads() throws Exception {
        // service
        ExecutorService executor = Executors.newFixedThreadPool(16);

        final int RX_THREADS = 2;

        // exchange point
        ArrayBlockingQueue<SocketAddress> queue = new ArrayBlockingQueue<>(64, true);
        // errors
        AtomicBoolean overflow = new AtomicBoolean();
        // counters
        AtomicLong[] rxCounters = new AtomicLong[] {
                new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong(),
                new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong()
        };

        for (int i = 0; i < RX_THREADS; i++) {
            executor.submit(new RxNIOThread(queue, rxCounters[i], overflow));
        }

        // main cycle
        long tStart = System.currentTimeMillis();
        while (true) {
            long tEnd = System.currentTimeMillis();
            if (5000 < tEnd - tStart) {
                long rxPackets = 0;
                long[] rxCountersCopy = new long[RX_THREADS];
                for (int i = 0; i < RX_THREADS; i++) {
                    rxCountersCopy[i] = rxCounters[i].getAndSet(0);
                    rxPackets += rxCountersCopy[i];
                }
                System.out.println("rcv total pps: " + rxPackets * 1000 / (tEnd - tStart) + "   queue overflow: " + overflow.get());
                for (int i = 0; i < RX_THREADS; i++) {
                    System.out.println("rcv   t#" + i + " pps: " + rxCountersCopy[i] * 1000 / (tEnd - tStart));
                }
                overflow.set(false);
                tStart = tEnd;
            }
            Thread.sleep(100);
        }
    }


    public static void rxNetty() throws Exception {

        AtomicLong counter = new AtomicLong(0);
        AtomicLong tStart = new AtomicLong(System.currentTimeMillis());

        int threads = 1;


        //ServerBootstrap sb = new ServerBootstrap();
        //sb.group(null, null);

        Bootstrap bootstrap = new Bootstrap();
        bootstrap
                //.group(new EpollEventLoopGroup(threads))
                //.channel(EpollDatagramChannel.class)
                .group(new NioEventLoopGroup(threads))
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_RCVBUF, 4*1024*1024)
                .option(ChannelOption.SO_SNDBUF, 4*1024*1024)
                //.option(ChannelOption.SO_REUSEADDR, true)
                //.option(EpollChannelOption.SO_REUSEADDR, true)
                //.option(UnixChannelOption.SO_REUSEPORT, true)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(28))
                .handler( new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel channel)
                            throws Exception {
                        ChannelPipeline pipeline = channel.pipeline();
                        pipeline.addLast(new ChannelInboundHandlerAdapter() {
                            
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                io.netty.channel.socket.DatagramPacket p = (io.netty.channel.socket.DatagramPacket) msg;

                                //ctx.write("DA");
                                long value = counter.incrementAndGet();

                                long tNow = System.currentTimeMillis();
                                long tStartCopy = tStart.get();
                                if (1000 < tNow - tStartCopy) {
                                    if (tStart.compareAndSet(tStartCopy, tNow)) {
                                        counter.set(0);
                                        System.out.println("rcv  pps: " + value  * 1000 / (tNow - tStartCopy));
                                    }
                                }

                                p.release();
                            }
                        });
                    }
                });

        ChannelFuture future;
        for(int i = 0; i < threads; ++i) {
            System.out.println("binding: " + i);
            future = bootstrap.bind(4321).sync();
        }

    }



    static class RxThread implements Runnable {
        BlockingQueue<SocketAddress> queue;
        AtomicLong counter;
        AtomicBoolean overflow;

        public RxThread(BlockingQueue<SocketAddress> _queue, AtomicLong _counter, AtomicBoolean _overflow) {
            queue = _queue;
            counter = _counter;
            overflow = _overflow;
        }

        @Override
        public void run() {
            try {
                // receive part
                DatagramSocket rSocket = new DatagramSocket(null);
                rSocket.setReceiveBufferSize(4*1024*1024);
                rSocket.setOption(StandardSocketOptions.SO_REUSEPORT, true);
                rSocket.bind(new InetSocketAddress(4321));


                byte[] rData = new byte[256];
                DatagramPacket rPacket = new DatagramPacket(rData, rData.length);

                long queueOverflow = 0;
                while (true) {
                    rSocket.receive(rPacket);
                    //if (!queue.offer(rPacket.getSocketAddress())) {
                    //    queueOverflow++;
                    //}
                    counter.incrementAndGet();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    static class TxThread implements Runnable {
        BlockingQueue<SocketAddress> queue;
        AtomicLong counter;

        public TxThread(BlockingQueue<SocketAddress> _queue, AtomicLong _counter) {
            queue = _queue;
            counter = _counter;
        }

        @Override
        public void run() {
            try {
                // send part
                DatagramSocket sSocket = new DatagramSocket();
                sSocket.setSendBufferSize(4*1024*1024);
                byte[] sData = new byte[28];
                DatagramPacket sPacket = new DatagramPacket(
                        sData, sData.length);

                long tStart = System.currentTimeMillis();
                long counter = 0;
                while (true) {
                    SocketAddress sAddress = queue.take();
                    sPacket.setSocketAddress(sAddress);
                    sSocket.send(sPacket);
                    counter++;

                    long tEnd = System.currentTimeMillis();
                    if (5000 < tEnd - tStart) {
                        System.out.println("send pps: " + counter  * 1000 / (tEnd - tStart));
                        counter = 0;
                        tStart = tEnd;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void rxSimpleWithThreads() throws Exception {
        // service
        ExecutorService executor = Executors.newFixedThreadPool(16);

        final int RX_THREADS = 4;

        // exchange point
        ArrayBlockingQueue<SocketAddress> queue = new ArrayBlockingQueue<>(64, true);
        // errors
        AtomicBoolean overflow = new AtomicBoolean();
        // counters
        AtomicLong txCounter = new AtomicLong();
        AtomicLong[] rxCounters = new AtomicLong[] {
                new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong(),
                new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong()
        };
        //AtomicLong rxCounter = new AtomicLong();

        for (int i = 0; i < RX_THREADS; i++) {
            executor.submit(new RxThread(queue, rxCounters[i], overflow));
        }

        //executor.submit(new TxThread(queue, txCounter));

        // main cycle
        long tStart = System.currentTimeMillis();
        while (true) {
            long tEnd = System.currentTimeMillis();
            if (5000 < tEnd - tStart) {
                long rxPackets = 0;
                long[] rxCountersCopy = new long[RX_THREADS];
                for (int i = 0; i < RX_THREADS; i++) {
                    rxCountersCopy[i] = rxCounters[i].getAndSet(0);
                    rxPackets += rxCountersCopy[i];
                }
                System.out.println("rcv total pps: " + rxPackets * 1000 / (tEnd - tStart) + "   queue overflow: " + overflow.get());
                for (int i = 0; i < RX_THREADS; i++) {
                    System.out.println("rcv   t#" + i + " pps: " + rxCountersCopy[i] * 1000 / (tEnd - tStart));
                }
                overflow.set(false);
                tStart = tEnd;
            }
            Thread.sleep(100);
        }

    }

}
