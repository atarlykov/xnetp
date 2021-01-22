package xnetp.poc.net;

import xnetp.poc.sockets.RawSocket;
import picocli.CommandLine;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@CommandLine.Command(name = "sender", usageHelpWidth = 120,
        description = "Produces ipv6 packet load")
public class PacketSender implements Callable<Integer> {

    //  --packets.types=10:10:20:10 --packets 1000 -is=1 -ia=10 FE::00
    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @CommandLine.Parameters(
            index = "0",
            paramLabel = "destination",
            description = "destination address")
    private InetAddress host;

    @CommandLine.Option(names = {"--packets.types"},
            split = ":",
            paramLabel = "D",
            defaultValue = "100:0:0:0",
            description = "packets' types distribution A:B:C:D, sum to 100")
    private int[] distribution;

    @CommandLine.Option(names = "--packets",
            defaultValue = "-1",
            description = "number of packets to send")
    private long packets;

    @CommandLine.Option(names = "--pps",
            defaultValue = "-1",
            description = "try to produce near fixed pps"
    )
    private int pps;

    @CommandLine.Option(names = "--threads",
            defaultValue = "1",
            description = "threads/sockets to be used for sending")
    private int threads;

    @CommandLine.Option(names = "--mmsg",
            defaultValue = "1",
            description = "number of messages to send via one system call")
    private int mmsgs;

    @CommandLine.Option(names = {"-uv", "--uni.volumes"},
            defaultValue = "1",
            paramLabel = "<volumes>",
            description = "uni (ipv6) addresses' volumes at VV position: FC::VV00:0000")
    private int uniVolumes;

    @CommandLine.Option(names = {"-us", "--uni.segments"},
            defaultValue = "1",
            paramLabel = "<segments>",
            description = "uni (ipv6) addresses' segments at SS position: FC::VVSS:0000")
    private int unipSegments;

    @CommandLine.Option(names = {"-ua", "--uni.addresses"},
            defaultValue = "1",
            paramLabel = "<addresses>",
            description = "uni (ipv6) addresses' en each segment, 2 last bytes: FC::VVSS:AAAA")
    private int uniAddresses;

    @CommandLine.Option(names = "--dump",
            description = "dump mode, packets only dumped to console")
    private boolean dump = false;

    /**
     * maximum amount in buffer any packet could take
     * including header
     */
    private final static int BUF_PACKET_BYTES_MAX   = 1024;
    /**
     * maximum number of packets that could be passed via system call
     * and stores in buffer (as a result)
     */
    private final static int BUF_PACKETS_MAX        = 16;

    /**
     * sum of all distribution values to be checked against,
     * or be normalized to this value.
     * used in process of different packets' generation
     */
    private final static int DISTRIBUTIONS_TOTAL    = 100;

    /**
     * xnetp protocol
     */
    private final static int XNETP_PROTOCOL_TYPE    = 253;


    /**
     * entry point
     * @param args arguments
     */
    public static void main(String[] args) {
        int result = new CommandLine(PacketSender.class).execute(args);
        System.exit(result);
    }

    /**
     * performs some post validation of parameters
     */
    private void validate() {
        if (packets < -1) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(), "packets should be positive");
        }
        if (!(host instanceof Inet6Address)) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(), "only ipv6 addresses are supported");
        }
        if ((mmsgs < 1) || (mmsgs > 16)) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(), "mmsgs must be in 1.." + BUF_PACKETS_MAX + " diapason");
        }
        if (distribution.length < 4) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(), "packets.types must provide 4 values");
        }
        if (Arrays.stream(distribution).sum() != DISTRIBUTIONS_TOTAL) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(), "packets.types must add up to " + DISTRIBUTIONS_TOTAL);
        }
        if (16 < threads) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(), "more 16 threads aren't supported");
        }
        if ((uniVolumes < 1) || (256 < uniVolumes)) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(), "ip.volumes must be 1..256");
        }
        if ((unipSegments < 1) || (256 < unipSegments)) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(), "ip.segments must be 1..256");
        }
        if ((uniAddresses < 1) || (0xFFFF < uniAddresses)) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(), "ip.segments must be 1..65535");
        }
    }

    /**
     * dumps parameters to screen to be sure that everything is fine
     */
    private void dumpParameters() {
        System.out.println("sender parameters:");
        System.out.println("  destination address:" + host.getHostAddress());
        System.out.println();
        System.out.println("  packets' types:");
        int index = 0;
        for (PacketType pType : PacketType.values()) {
            System.out.println(String.format("    %3d%%\t%s", distribution[index], pType.toString()));
            index++;
        }
        System.out.println();
        System.out.println("  xnetp nodes address template: FC00::VV:SS:AAAA");
        System.out.println("        VV: 0" + ((uniVolumes > 1) ? " .. " + (uniVolumes - 1) : ""));
        System.out.println("        SS: 0" + ((unipSegments > 1) ? " .. " + (unipSegments - 1) : ""));
        System.out.println("      AAAA: 1 .. " + uniAddresses);
        System.out.println();
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
     * @return nothing
     * @throws Exception if any
     */
    public Integer _call() throws Exception {
        // perform post validation
        validate();
        // print parsed parameters
        dumpParameters();

        // trigger library load to get possible exceptions
        RawSocket.load();

        // service
        ExecutorService executor = Executors.newFixedThreadPool(16);

        // errors
        AtomicInteger[] txErrors = new AtomicInteger[] {
                new AtomicInteger(-1), new AtomicInteger(-1), new AtomicInteger(-1), new AtomicInteger(-1),
                new AtomicInteger(-1), new AtomicInteger(-1), new AtomicInteger(-1), new AtomicInteger(-1),
                new AtomicInteger(-1), new AtomicInteger(-1), new AtomicInteger(-1), new AtomicInteger(-1),
                new AtomicInteger(-1), new AtomicInteger(-1), new AtomicInteger(-1), new AtomicInteger(-1)
        };

        // counters, false sharing is here yet
        AtomicLong[] txCounters = new AtomicLong[] {
                new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong(),
                new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong(),
                new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong(),
                new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong()
        };

        // tracks running threads
        Future futures[] = new Future[threads];

        // run requested number of sending threads
        System.out.println("starting packet's sending threads:");
        for (int i = 0; i < threads; i++) {
            long txPps = (pps == -1) ? pps : pps / threads;
            long txPackets = packets;
            if (packets != -1) {
                if (i < threads - 1) {
                    txPackets = packets / threads;
                } else {
                    txPackets = packets - (threads - 1)*(packets / threads);
                }
            }
            futures[i] = executor.submit(new TxThread("tx#" + i, txCounters[i], txErrors[i], txPackets, txPps, dump));
        }


        // delay till all threads start (just simple sync)
        Thread.sleep(500);
        System.out.println("\nsending statistics");

        // main statistics cycle
        long tStart = System.currentTimeMillis();
        while (true) {
            long tEnd = System.currentTimeMillis();
            if (5000 < tEnd - tStart) {
                // collect threads' statistics
                long txPackets = 0;
                long[] txCountersCopy = new long[threads];
                int[] txErrorCopy = new int[threads];
                for (int i = 0; i < threads; i++) {
                    txCountersCopy[i] = txCounters[i].getAndSet(0);
                    txErrorCopy[i] = txErrors[i].getAndSet(-1);
                    txPackets += txCountersCopy[i];
                }
                // track if there are still running tasks
                boolean aliveTasks = false;
                // provide information

                System.out.println(String.format("  total pps: %,10d", txPackets * 1000 / (tEnd - tStart)));
                for (int i = 0; i < threads; i++) {
                    if (futures[i].isDone()) {
                        System.out.println(String.format("      t#%2d   done", i));
                    } else {
                        aliveTasks = true;
                        System.out.print(String.format("       t#%2d: %,10d", i, txCountersCopy[i] * 1000 / (tEnd - tStart)));
                        if (txErrorCopy[i] == -1) {
                            System.out.print("    last errno: none");
                        } else {
                            System.out.print("    last errno: " + txErrorCopy[i]);
                        }
                        System.out.println();
                    }
                }
                System.out.println();
                tStart = tEnd;

                // exit main cycle if there no more active tasks
                if (!aliveTasks) {
                    break;
                }
            }
            Thread.sleep(100);
        }

        // clear pooled threads
        executor.shutdown();

        return 0;
    }

    /**
     * Main packet sending threads
     */
    public class TxThread implements Runnable {
        // thread name for info displaying
        String name;
        // external counter to be updated to track tx operations
        AtomicLong extCounter;
        // service indicator
        AtomicInteger extError;

        // per thread threads to be sent
        long txPackets;
        // per thread pps to support
        long txPps;
        // dump mode switch
        boolean dump;

        /**
         * allowed constructor
         */
        TxThread(
                String _name,
                AtomicLong _extCounter,
                AtomicInteger _extError,
                long _tPackets,
                long _tPps,
                boolean _dump)
        {
            name = _name;
            extCounter = _extCounter;
            extError = _extError;
            txPps = _tPps;
            txPackets = _tPackets;
            dump = _dump;
        }

        @Override
        public void run() {
            try {
                // extract and cache address
                byte[] address = host.getAddress();

                PacketBuffer buffer = PacketBuffer.allocate(BUF_PACKETS_MAX, BUF_PACKET_BYTES_MAX);

                // populate templates for xnetp packets
                for (int i = 0; i < mmsgs; i++) {
                    // destination addresses
                    buffer.populateSlotAddress(i, address);
                    // headers with fixed values
                    buffer.populatePacketExtHeaderTemplate(i);
                    // test payload
                    buffer.populatePacketPayloadTemplate(i, BUF_PACKET_BYTES_MAX);
                }

                // partial sums of distributions
                int[] proportions = distribution.clone();
                for (int i = 1; i < proportions.length; i++) {
                    proportions[i] += proportions[i - 1];
                }

                // log thread parameters
                System.out.println("  thread: " + name +
                        "    packets: " + txPackets +
                        "    pps: " + txPps +
                        "    p: " + Arrays.toString(proportions));

                // some delay to remove dump interference in dump mode
                Thread.sleep(100);

                // open socket and prepare to send data
                RawSocket.RawSocket6 rSocket = (RawSocket.RawSocket6) RawSocket.open(RawSocket.AF_INET6, XNETP_PROTOCOL_TYPE);

                // local packet counter to exit when finished
                long pCounter = 0;
                // iteration start time used to track pps
                long tSendStart = 0;
                // indicator to track pps in case of send errors
                boolean generatePackets = true;
                // service indicator
                //long queueOverflow = 0;

                while (true) {
                    // prepare data if there were no error on
                    // the previous turn, otherwise reuse packets
                    if (generatePackets) {
                        // mark iteration start to support pps,
                        // will not be updated in case of errors
                        tSendStart = System.nanoTime();

                        populatePackets(buffer, mmsgs, proportions);
                        generatePackets = false;
                    }

                    // this will be number of messages sent
                    int result = -1;
                    if (dump) {
                        // dump packets to console
                        synchronized (TxThread.class) {
                            buffer.dump(mmsgs);
                        }
                        Thread.sleep(500);
                        // indicate we've sent 1 packet
                        result = mmsgs;
                    } else {
                        // send packets
                        result = rSocket.sendmmsg(buffer.buffer, mmsgs, BUF_PACKET_BYTES_MAX);
                        if (result == -1) {
                            // this could be tx queue overload
                            extError.set(rSocket.errno());
                            Thread.yield();
                            continue;
                        }
                    }

                    // packets we sent successfully, indicate to generate new ones
                    generatePackets = true;

                    pCounter += result;
                    extCounter.addAndGet(result);

                    // exit if have sent all packets for this thread
                    if ((txPackets != -1) && (txPackets <= pCounter)) {
                        break;
                    }

                    // try to support necessary pps if specified
                    if (txPps != -1) {
                        long tSendEnd = System.nanoTime();
                        long tSleepUntil = tSendEnd + (long) (1E9 / txPps * mmsgs) - (tSendEnd - tSendStart);
                        while (System.nanoTime() < tSleepUntil) {
                            //Thread.onSpinWait();
                        }
                    }
                }
                rSocket.close();
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * populates a number of packets in the buffer using specified distribution
     * to select type of packets to generate
     * @param buffer buffer to populate into
     * @param packets number of packet to generate
     * @param proportions partial sum of distributions of packet types
     */
    private void populatePackets(
            PacketBuffer buffer,
            int packets,
            int[] proportions)
    {
        assert proportions[proportions.length - 1] == DISTRIBUTIONS_TOTAL  : "strange packets' types' distribution";

        ThreadLocalRandom random = ThreadLocalRandom.current();
        PacketType[] pTypes = PacketType.values();

        for (int i = 0; i < packets; i++) {
            int rnd = random.nextInt(DISTRIBUTIONS_TOTAL);
            int pTypeIndex = 0;

            // special check for 00:XX:YY:ZZ proportions
            while ((proportions[pTypeIndex] == 0) || (proportions[pTypeIndex] < rnd)) {
                pTypeIndex++;
            }

            boolean packetCorrect = true;
            PacketType pType = pTypes[pTypeIndex];
            if (pType == PacketType.ERROR) {
                // indicate error
                packetCorrect = false;

                // select new package type
                if (proportions[pTypes.length - 2] == 0) {
                    // seems we have only error packages to be sent,

                    // select any type
                    //pType = pTypes[random.nextInt(pTypes.length - 1)];

                    // select minimal type
                    pType = PacketType.MINIMAL;
                } else {
                    // select error type proportionally to specified distribution

                    // this will give random for correct types only,
                    // 0:70:0:30 will sum to 0:70:70:100 and give [0..70) diapason
                    rnd = random.nextInt(proportions[pTypes.length - 2]);
                    pTypeIndex = 0;
                    while ((proportions[pTypeIndex] == 0) || (proportions[pTypeIndex] < rnd)) {
                        pTypeIndex++;
                    }
                    pType = pTypes[pTypeIndex];
                }
            }

            // populate
            buffer.populatePacketData(i, pType, uniVolumes, unipSegments, uniAddresses, packetCorrect);
        }

        buffer.packets = packets;
    }

}
