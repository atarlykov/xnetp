import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

/**
 * results
 *   - 100Mbit ->  187kps with 560 bit packets (udp with 28 bytes payload)
 *   -   1Gbit -> 1917kps with 560 bit packets
 *   - mac: ubuntu 18.04 i386, core2, /proc/sys/net/core/wmem_default = 163840,
 *          Marvell Technology Group Ltd. 88E8058 PCI-E Gigabit Ethernet Controller (rev 13)
 *   - win: win 7 pro 64, 1Gb
 *          Intel I217-V 1Gbit
 *   - router RT-N16 1Gb
 *   - .255, server with nvidia, i5-4670 @3.4GHz, /proc/sys/net/core/wmem_default = 212992,
 *          Intel Corporation Ethernet Connection I217-V (rev 04), 1Gbit
 *   - .138, server kev, i5-4670 @3.4GHz,
 *          Intel Corporation Ethernet Connection I217-V (rev 04), 1Gbit
 *
 *   1. sendmmsg * 1024, payload 28b, mac -> router -> win,       ~ 430 kps  [some packets are lost at sender] [1 thread]
 *   2. sendmmsg * 1024, payload 28b, mac -> router,              ~  42 kps  [some packets are lost at sender] [1 thread]
 *   3. sendmmsg * 1024, payload 28b, mac -> router -> fake ip,   ~ 840 kps  [nothing lost at sender] [1 thread]
 *
 *   4. sendto.blocking, payload 28b, mac -> router -> win,       ~ 306 kps  [nothing lost at sender] [1 thread]
 *   5. sendto.blocking, payload 28b, mac -> router,              ~  42 kps  [nothing lost at sender] [1 thread]
 *   6. sendto.blocking, payload 28b, mac -> router -> fake ip,   ~ 596 kps  [nothing lost at sender] [1 thread]
 *
 *   7. simple,          payload 4 bytes, mac -> router -> win,       ~152 kps [1 thread]
 *   8. simpleThreads,   payload 4 bytes, mac -> router -> win,       ~266 kps [2 thread]
 *   9. simpleThreads,   payload 4 bytes, mac -> router -> win,       ~266 kps [4 thread]
 *
 *  10. sendto.blocking, payload 28, .255 -> sut -> .138,         ~ 850 kps [1 thread]
 *  11. sendmmsg * 1024, payload 28, .255 -> sut -> .138,         ~1126 kps [1 thread]
 *
 *  12. simpleThreads,   payload 4 bytes, .255 -> sut -> .128,    ~1457 kps [4 thread]
 *  13. simpleThreads,   payload 4 bytes, .255 -> sut -> .128,    ~ 630 kps [1 thread]
 *  14. simpleThreads,   payload 4 bytes, .255 -> sut -> .128,    ~ 980 kps [2 thread] [up to 1140 kps]
 */
public class NetUDPSender {


    public static void main(String[] args) throws Exception {
        //simple();
        simple6();
        //simpleThreads();
        //simpleTimer();
        //simpleConnect();
        //simpleConnectThreads();
        //channel();
        //channelThreads();
    }



    public static void simple() throws Exception {
        byte[] data = new byte[4];

        int SOCKETS_NUM = 1;

        DatagramSocket[] sockets = new DatagramSocket[SOCKETS_NUM];
        DatagramPacket[] packets = new DatagramPacket[SOCKETS_NUM];


        for (int i = 0; i < sockets.length; i++) {
            sockets[i] = new DatagramSocket(0);
            sockets[i].setSendBufferSize(64*1024);

            packets[i] = new DatagramPacket(
                    data, data.length,
                    //InetAddress.getByAddress(new byte[] {(byte)192, (byte)168, 1, (byte)106}),
                    InetAddress.getByAddress(new byte[] {(byte)192, (byte)168, 1, (byte)106}),
                    4321);
        }

        final int N = 2;
        long tStart = System.nanoTime();
        for (int i = 0; i < N; i++) {
//            data[0] = (byte)(i >> 24);
//            data[1] = (byte)(i >> 16);
//            data[2] = (byte)(i >>  8);
//            data[3] = (byte)(i >>  0);

            int sIndex = i % SOCKETS_NUM;
            sockets[sIndex].send(packets[sIndex]);
        }

        for (int i = 0; i < sockets.length; i++) {
            sockets[i].close();
        }
        long tEnd = System.nanoTime();

        System.out.println((double)N  * 1E9 / (tEnd - tStart) + " pps");
    }

    public static void simple6() throws Exception {
        byte[] data = new byte[4];

        DatagramSocket socket = new DatagramSocket(0);

        DatagramPacket packet = new DatagramPacket(
                data, data.length,
                InetAddress.getByName("fe80::5b3:cfa5:22bb:7a6b"),
                4321);

        final int N = 10000000;
        long tStart = System.nanoTime();
        for (int i = 0; i < N; i++) {
            data[0] = (byte)(1);
            data[1] = (byte)(2);
            data[2] = (byte)(3);
            data[3] = (byte)(i);

            socket.send(packet);
        }

        socket.close();
        long tEnd = System.nanoTime();
        System.out.println((double)N  * 1E9 / (tEnd - tStart) + " pps");
    }

    public static void simpleTimer() throws Exception {
        byte[] data = new byte[28];

        DatagramSocket socket = new DatagramSocket(0);
        socket.setSendBufferSize(4*1024*1024);

        DatagramPacket packet = new DatagramPacket(
                data, data.length,
                //InetAddress.getByAddress(new byte[] {(byte)172, (byte)16, 76, (byte)138}),
                InetAddress.getByAddress(new byte[] {(byte)192, (byte)168, 1, (byte)136}),
                4321);

        final int ppsDesired = 10_000;
        long tStart = System.currentTimeMillis();
        long counter = 0;
        while (true) {
            long tSendStart = System.nanoTime();
            socket.send(packet);
            counter++;
            long tNow = System.currentTimeMillis();
            if (5000 < tNow - tStart) {
                System.out.println("pps: " + (counter * 1000 / (tNow - tStart)));
                tStart = tNow;
                counter = 0;
            }
            long tSendEnd = System.nanoTime();

            long tSleepFor = tSendEnd + (long)(1E9 / ppsDesired) - (tSendEnd - tSendStart);
            while (System.nanoTime() < tSleepFor) {
            }
        }
        //socket.close();
    }

    public static void simpleThreads() throws Exception {

        ExecutorService service = Executors.newFixedThreadPool(16);

        List<Future> results = new ArrayList<>();
        final int N = 10_000_000;
        final int T = 4;
        long tStart = System.currentTimeMillis();
        for (int t = 0; t < T; t++) {
            final int tCopy = t;
            results.add(service.submit(() -> {
                try {
                    byte[] data = new byte[4];

                    DatagramSocket socket = new DatagramSocket(0);
                    socket.setSendBufferSize(64*1024);

                    DatagramPacket packet = new DatagramPacket(
                            data, data.length,
                            InetAddress.getByAddress(new byte[] {(byte)172, (byte)16, 76, (byte)138}),
                            4321);

                    for (int i = 0; i < N; i++) {
//                        data[0] = (byte)(i >> 24);
//                        data[1] = (byte)(i >> 16);
//                        data[2] = (byte)(i >>  8);
//                        data[3] = (byte)(i >>  0);

                        socket.send(packet);
                    }
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }));
        }
        for (Future f : results) {
            f.get();
        }
        service.shutdown();
        long tEnd = System.currentTimeMillis();
        System.out.println((double)N * T * 1000 / (tEnd - tStart) + " pps");
    }

    public static void simpleConnect() throws Exception {

        byte[] data = new byte[4];

        DatagramSocket socket = new DatagramSocket(4321);
        socket.connect(InetAddress.getByAddress(new byte[] {(byte)192, (byte)168, 1, (byte)1}),  4321);

        socket.setSendBufferSize(4*1024*1024);

        DatagramPacket packet = new DatagramPacket(data, data.length);
        final int N = 100_000;
        long tStart = System.currentTimeMillis();
        for (int i = 0; i < N; i++) {
            data[0] = (byte)(i >> 24);
            data[1] = (byte)(i >> 16);
            data[2] = (byte)(i >>  8);
            data[3] = (byte)(i >>  0);

            socket.send(packet);
        }
        socket.close();
        long tEnd = System.currentTimeMillis();
        System.out.println("time: " + (tEnd - tStart) + "ms");
        System.out.println((double)N  * 1000 / (tEnd - tStart) + " pps");
    }

    public static void simpleConnectThreads() throws Exception {

        ExecutorService service = Executors.newFixedThreadPool(16);

        List<Future> results = new ArrayList<>();
        final int N = 1_000_000;
        final int T = 4;
        long tStart = System.currentTimeMillis();
        for (int t = 0; t < T; t++) {
            final int tCopy = t;
            results.add( service.submit(() -> {
                try {
                    byte[] data = new byte[4];
                    DatagramSocket socket = new DatagramSocket(0);
                    socket.connect(InetAddress.getByAddress(new byte[] {(byte)192, (byte)168, 1, (byte)146}),  4321 + tCopy);

                    socket.setSendBufferSize(4*1024*1024);

                    DatagramPacket packet = new DatagramPacket(data, data.length);
                    for (int i = 0; i < N; i++) {
                        data[0] = (byte)(i >> 24);
                        data[1] = (byte)(i >> 16);
                        data[2] = (byte)(i >>  8);
                        data[3] = (byte)(i >>  0);

                        socket.send(packet);
                    }
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }));
        }
        for (Future f : results) {
            f.get();
        }
        service.shutdown();

        long tEnd = System.currentTimeMillis();
        System.out.println("time: " + (tEnd - tStart) + "ms");
        System.out.println((double)N * T * 1000 / (tEnd - tStart) + " pps");
    }

    public static void channel() throws Exception {
        ByteBuffer data = ByteBuffer.allocate(4);

        DatagramChannel channel = DatagramChannel.open(StandardProtocolFamily.INET);
        InetSocketAddress remote = new InetSocketAddress(
                InetAddress.getByAddress(new byte[]{(byte)192, (byte)168, 1, 1}), 4321);
        channel.setOption(StandardSocketOptions.SO_SNDBUF, 4*1024*1024);
        channel.connect(remote);
        channel.configureBlocking(false);


        final int N = 1;
        long tStart = System.currentTimeMillis();

        for (int i = 0; i < N; i++) {
            data.rewind();
            data.putInt(0, i);
            data.rewind();
            channel.send(data, remote);
        }
        channel.close();
        long tEnd = System.currentTimeMillis();
        System.out.println("time: " + (tEnd - tStart) + "ms");
        System.out.println((double)N  * 1000 / (tEnd - tStart) + " pps");
    }

    public static void channelThreads() throws Exception {

        ExecutorService service = Executors.newFixedThreadPool(16);


        List<Future> results = new ArrayList<>();
        final int N = 100_000;
        final int T = 4;
        long tStart = System.currentTimeMillis();
        for (int t = 0; t < T; t++) {
            results.add( service.submit(() -> {

                try {
                    ByteBuffer data = ByteBuffer.allocate(4);

                    DatagramChannel channel = DatagramChannel.open(StandardProtocolFamily.INET);
                    InetSocketAddress remote = new InetSocketAddress(
                            InetAddress.getByAddress(new byte[]{(byte)192, (byte)168, 1, 1}), 4321);
                    channel.setOption(StandardSocketOptions.SO_SNDBUF, 4*1024*1024);
                    channel.connect(remote);

                    for (int i = 0; i < N; i++) {
                        data.rewind();
                        data.putInt(0, i);
                        data.rewind();
                        channel.send(data, remote);
                    }
                    channel.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }

            }));
        }
        for (Future f : results) {
            f.get();
        }
        service.shutdown();
        
        long tEnd = System.currentTimeMillis();
        System.out.println("time: " + (tEnd - tStart) + "ms");
        System.out.println((double)N * T * 1000 / (tEnd - tStart) + " pps");
    }

}
