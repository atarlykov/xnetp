package xnetp.poc.net;

import xnetp.poc.disk.StateStoreParameters;
import picocli.CommandLine;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Set;

/**
 * container for packet receiver's command line parameters
 */
public class PacketReceiverParameters {

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;


    @CommandLine.Option(names = "--mmsg",
            defaultValue = "1",
            description = "number of messages to receive via one system call")
    public int mmsgs;

    @CommandLine.Option(names = {"-uv", "--uni.volumes"},
            defaultValue = "1",
            paramLabel = "<volumes>",
            description = "uni (ipv6) addresses' volumes at VV position: FC::VV00:0000")
    public int uniVolumes;

    @CommandLine.Option(names = {"-us", "--uni.segments"},
            defaultValue = "1",
            paramLabel = "<segments>",
            description = "uni (ipv6) addresses' segments at SS position: FC::VVSS:0000")
    public int uniSegments;

    @CommandLine.Option(names = {"-ua", "--uni.addresses"},
            defaultValue = "1",
            paramLabel = "<addresses>",
            description = "uni (ipv6) addresses' en each segment, 2 last bytes: FC::VVSS:AAAA")
    public int uniAddresses;

    @CommandLine.Option(names = "--dump",
            description = "packets dumped to console")
    public boolean dump = false;

    @CommandLine.Option(names = "--stm",
            description = "demo mode, packets only dumped to console")
    public boolean singleThreadMode = false;

    @CommandLine.Option(names = {"-spm", "--stat.period.ms"},
            description = "period in ms to print receive statistics")
    public long statisticsPeriodMs = 5_000;


    @CommandLine.Option(names = {"-th", "--thread.handlers"},
            defaultValue = "2",
            description = "packets' handler threads number")
    public int handlerThreadsNum;

    @CommandLine.Option(names = {"-hs", "--handlers.store"},
            defaultValue = "0",
            description = "packets' handlers: percent of packets to store to disk")
    public float handlerPercentToStore;

    @CommandLine.Option(names = {"-hr", "--handlers.reply"},
            defaultValue = "0",
            description = "packets' handlers: percent of packets to send network reply")
    public float handlerPercentToReply;

    @CommandLine.Option(names = {"-hra", "--handlers.reply.address"},
            description = "reply address")
    public InetAddress replyAddress;

    @CommandLine.Option(names = {"-hw", "--handlers.workload"},
            defaultValue = "0",
            description = "packets' handlers: number of accumulator iterations to emulate processing")
    public long handlerWorkloadIterations;




    @CommandLine.Option(names = {"-tra", "--thread.receiver.affinity"},
            defaultValue = "0",
            description = "packets' receiver affinity (cpu core index)")
    public int receiverAffinity;


    @CommandLine.Option(names = {"-tha", "--thread.handlers.affinity"},
            split = ",",
            description = "packets' handlers affinity (cpu core indices, like \"0,1,2,3\")")
    public int[] handlersAffinity = new int[0];


    /*
     *
     * Parameters for integration with store subsystem
     *
     */
    @CommandLine.Option(
            names = {"-sr", "--store.root"},
            description = "Root folder for data store")
    public String storeRoot = "/tmp/xnetp";


    @CommandLine.Option(
            names = {"-sf", "--store.file"},
            description = "name of the file to emulate the whole store (single file mode) or name of a file for each node (tree mode)")
    public String storeFile = "cim.dat";


    // these are uniSegments/uniAddresses
    //@CommandLine.Option(names = {"--segments"}, description = "Segment count, default 4")
    //@CommandLine.Option(names = {"--sfiles"}, description = "Files per segment, default 25")


    //Size of data file, default 4k
    @CommandLine.Option(
            names = {"-sss", "--store.state.size"},
            description = "node state size in bytes to store to disk, default is 4k")
    public long storeStateSize = 1024 * 4;

    //"rw", "rwd", "rws" allowed
    @CommandLine.Option(
            names = {"-sfm", "--store.file.mode"},
            description = "write mode for file(s) with state [rw|rwd|rws], default is rw")
    public StateStoreParameters.StoreWriteMode storeWriteMode = StateStoreParameters.StoreWriteMode.rw;


    //store modes, "tree", "single", "tslog" allowed
    @CommandLine.Option(
            names = {"-sm", "--store.mode"},
            description = "global store mode [single|tree|tslog], default is single.")
    public String storeMode = "single";

    @CommandLine.Option(names = "--store.tlog.thread.affinity",
            description = "transaction log buffer thread affinity")
    public int tlogAffinity = 0;

    @CommandLine.Option(names = "--store.tlog.buffer",
            description = "transaction log buffer size")
    public int tlogBufferSize = 64;

    @CommandLine.Option(names = "--store.tlog.timeout",
            description = "transaction log timeout to commit if no requests (in ms)")
    public int tlogTimeout = 10000;

    /**
     * performs some post validation of parameters
     */
    public void validate(int BUF_PACKETS_MAX) {
        if ((mmsgs < 1) || (mmsgs > BUF_PACKETS_MAX)) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(), "mmsgs must be in 1.." + BUF_PACKETS_MAX + " diapason");
        }
        if (handlerThreadsNum <  1) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(), "too little handler threads, should be > 0");
        }
        if (64 < handlerThreadsNum) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(), "too many handler threads");
        }
        if (0 < handlerPercentToReply) {
            if (replyAddress == null) {
                throw new CommandLine.ParameterException(
                        spec.commandLine(), "reply address is required in case if reply percentage is > 0");
            }
        }
        if (!Set.of("single","tree","tslog").contains(storeMode)) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(), "--store.mode is unknown, must be [single|tree|tlog]");
        }
    }

    /**
     * dumps parameters to screen to be sure that everything is fine
     */
    public void dump() {
        System.out.println();
        System.out.println();

        System.out.println("  node address template: FC00::VV:SS:AAAA");
        System.out.println("        VV: 0" + ((uniVolumes > 1) ? " .. " + (uniVolumes - 1) : ""));
        System.out.println("        SS: 0" + ((uniSegments > 1) ? " .. " + (uniSegments - 1) : ""));
        System.out.println("      AAAA: 1 .. " + uniAddresses);
        System.out.println();


        if (singleThreadMode) {
            System.out.println("  threading:  single thread,   cpu# " + receiverAffinity);
        } else {
            System.out.println("  threading:  1 receiver + " + handlerThreadsNum + " handlers");
            System.out.println("   receiver:   cpu: " + receiverAffinity);
            System.out.println("   handlers:   num: " + handlerThreadsNum + "    cpus: " + Arrays.toString(handlersAffinity));
        }
        System.out.println();

        if (singleThreadMode) {
            System.out.println("  receiver:  mode: simplified/single thread");
        } else {
            System.out.println("  receiver:  mode: multiple threads ");
            System.out.println("      mmsg: " + mmsgs + " packets");
            System.out.println( String.format("    w/load: %6.2f%% packets       iterations: %d",
                    100f - handlerPercentToStore - handlerPercentToReply, handlerWorkloadIterations));
            System.out.println( String.format("     store: %6.2f%% packets", handlerPercentToStore));
            System.out.println( String.format("     reply: %6.2f%% packets          send to: %s",
                    handlerPercentToReply, (replyAddress != null) ? replyAddress.toString() : ""));
        }
        System.out.println();

        if (handlerPercentToStore == 0) {
            System.out.println("  store: off");
        } else {
            System.out.println("  store:  " + storeMode);
            System.out.println("    node state size: " + storeStateSize);
            System.out.println("         write mode: " + storeWriteMode);
            System.out.println("               root: " + storeRoot);
            System.out.println("               file: " + storeFile);
            if (storeMode.equals(StateStoreParameters.StoreMode.tslog.name())) {
                System.out.println("      tlog affinity: cpu#" + tlogAffinity);
                System.out.println("   tlog buffer size: " + tlogBufferSize);
                System.out.println("       tlog timeout: " + tlogTimeout);
            }
        }
        System.out.println();
        System.out.println();
        System.out.println();
        System.out.println();
    }

    /**
     * @return store state parameters based on receivers'
     * specific command line parameters
     */
    public StateStoreParameters buildStateStoreParameters() {
        StateStoreParameters ss = new StateStoreParameters();

        ss.storeRoot = storeRoot;
        ss.storeFile = storeFile;
        ss.uniVolumes = uniVolumes;
        ss.uniSegments = uniSegments;
        ss.uniAddresses = uniAddresses;
        ss.storeStateSize = storeStateSize;
        ss.storeWriteMode = storeWriteMode;
        ss.threads      = 1;

        ss.storeMode        = StateStoreParameters.StoreMode.valueOf(storeMode);

        return ss;
    }

}
