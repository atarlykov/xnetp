package xnetp.poc.sockets;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.NetworkInterface;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Formatter;

public class RxRing {

    private static Field fAddress;
    private static Field fCapacity;
    private static Field fLimit;

    private static final VarHandle MODIFIERS;
    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(Field.class, MethodHandles.lookup());
            MODIFIERS = lookup.findVarHandle(Field.class, "modifiers", int.class);

            fAddress = Buffer.class.getDeclaredField("address");
            fAddress.setAccessible(true);

            fCapacity = Buffer.class.getDeclaredField("capacity");
            makeNonPrivate(fCapacity);
            fCapacity.setAccessible(true);

            fLimit = Buffer.class.getDeclaredField("limit");
            makeNonPrivate(fLimit);
            fLimit.setAccessible(true);
        } catch (IllegalAccessException | NoSuchFieldException ex) {
            throw new RuntimeException(ex);
        }
    }
    public static void makeNonPrivate(Field field) {
        int mods = field.getModifiers();
        if (Modifier.isPrivate(mods)) {
            MODIFIERS.set(field, mods & ~Modifier.PRIVATE);
        }
    }





    static {
        System.loadLibrary("netrxring");
    }

    public static void main(String[] args) {
        RxRing ring = new RxRing();
    }

    int sd;
    ByteBuffer ring;
    int blockSize;
    int blocks;


    private static int TP_STATUS_KERNEL	=  0;
    private static int TP_STATUS_USER   = (1 << 0);

    public RxRing() {
        blockSize = 1 << 24;
        blocks = 16;
        int frameSize = 1 << 11;

        try {
            ring = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());

            NetworkInterface enp3s0f0 = NetworkInterface.getByName("enp3s0f0");
            int ifIndex = enp3s0f0.getIndex();

            System.out.println("blocks: " + blocks);
            System.out.println("block size: " + blockSize);

            sd = _socket(ifIndex, ring, blocks, blockSize, frameSize);
            System.out.println("socket: " + sd);
            if (sd == -1) {
                System.out.println(_errno());
                return;
            }

            long mmap = ring.getLong(0);
            System.out.println(String.format("\nmmap: 0x%X\n", mmap));


            hack(ring, blocks, blockSize);

//            System.out.print("initial:  ");
//            dumpBlockHeader(ring, blockSize, 0);

            long iterCounter = counter;
            long iterCounterAll = counterAll;
            long iterStart = System.nanoTime();
            long iterations = 0;

            int blockToPoll = 0;
            long tStart = System.currentTimeMillis();
            while (System.currentTimeMillis() - tStart < 100000) {
                int bStatus = ring.getInt(blockSize * blockToPoll + 8);
                if ((bStatus & TP_STATUS_USER) == 0) {
                    _poll(sd, -1);
                    continue;
                }

                process(ring, blockToPoll);
                iterations++;

                // stat
                long time = System.nanoTime();
                if ((long)5E9 < time - iterStart) {
                    double factor = 1E9 / (time - iterStart);

                    long packets = counter - iterCounter;
                    long packetsAll = counterAll - iterCounterAll;
                    System.out.println(              "      [receive]     iterations     pkt:all   pkt:all/s   pkt:xnetp     xnetp/s");
                    System.out.println(String.format("                  %12d%12d%12d%12d%12d\n",
                            iterations, packetsAll, (int)(factor * packetsAll), packets, (int)(factor * packets)));


                    // start next iter
                    iterCounter = counter;
                    iterCounterAll = counterAll;
                    iterStart = time;
                    iterations = 0;
                }



                // release
                ring.putInt(blockSize * blockToPoll + 8, TP_STATUS_KERNEL);
                blockToPoll = (blockToPoll + 1) % blocks;
            }
            System.out.println("counter:  xnetp: " + counter);
            System.out.println("counter:    all: " + counterAll);


            restore(ring);
        } catch (Exception e) {
            e.printStackTrace();
        }

        _X_close(sd, ring, blocks, blockSize);
    }

    long counterAll = 0;
    long counter = 0;

    public void process(ByteBuffer buffer, int block) {
        int base = block * blockSize;
        int num_pkts = buffer.getInt(base + 8 + 4);
        int offset_to_first__pkt = buffer.getInt(base + 8 + 8);

        //System.out.println("num_pkts: " + num_pkts + "    offset: " + offset_to_first__pkt);
        int offset = base + offset_to_first__pkt;
        for (int i = 0; i < num_pkts; i++) {
            int tp_next_offset = processPacket(buffer, offset);
            offset += tp_next_offset;
        }
    }
    public int processPacket(ByteBuffer buffer, int offset) {

        int tp_next_offset = buffer.getInt(offset);
        int tp_mac = buffer.getShort(offset + 24);
        int tp_net = buffer.getShort(offset + 26);

        counterAll++;

        // skip non ipv6
        short eth_proto = buffer.getShort(offset + tp_mac + 12);
        //System.out.println(String.format("  eth_proto: %X", eth_proto));
        if (eth_proto != (short)0xDD86) {
            return tp_next_offset;
        }

        // skip non xnetp
        byte next_hdr = buffer.get(offset + tp_net + 6);
        //System.out.println(String.format("  next_hdr: %X", next_hdr));
        if (next_hdr != (byte)0xFD) {
            return tp_next_offset;
        }

        counter++;

        return tp_next_offset;
    }






    public void close() {
        _close(sd, ring, blocks, blockSize);
    }


    private native static int _socket(int ifIndex, ByteBuffer ring, int _blocks, int _blockSize, int _frameSize);
    private native static int _poll(int socket, int timeout);
    private native static int _close(int socket, ByteBuffer ring, int _blocks, int _blockSize);

    private native static int _X_close(int socket, ByteBuffer ring, int _blocks, int _blockSize);
    private native static int _errno();

    long _mapped;
    long _address;
    int _limit;
    int _capacity;

    private void hack(ByteBuffer ring, int blocks, int blockSize) throws Exception {
        // extract
        _mapped = ring.getLong(0);

        // save original state
        _address = (long)fAddress.get(ring);
        _capacity = (int)fCapacity.get(ring);
        _limit = (int)fCapacity.get(ring);

        // hack
        fAddress.set(ring, _mapped);
        fCapacity.set(ring, blocks * blockSize);
        fLimit.set(ring, blocks * blockSize);

        System.out.println(String.format("hacked: direct buffer now is at: 0x%X\n", (long)fAddress.get(ring)));
    }

    private void restore(ByteBuffer ring) throws Exception {
        fAddress.set(ring, _address);
        fCapacity.set(ring, _capacity);
        fLimit.set(ring, _limit);

        System.out.println(String.format("restore: direct buffer now is at: 0x%X\n", (long)fAddress.get(ring)));
    }


    /**
     * https://elixir.bootlin.com/linux/latest/source/include/uapi/linux/if_packet.h#L189
     *
     *  struct tpacket_hdr_v1 {
     * 	  __u32	block_status;
     * 	  __u32	num_pkts;
     * 	  __u32	offset_to_first_pkt;
     * 	  __u32	blk_len;
     * 	  __aligned_u64	seq_num;
     * 	  struct tpacket_bd_ts	ts_first_pkt, ts_last_pkt;
     * 	}

     * union tpacket_bd_header_u {
     * 	struct tpacket_hdr_v1 bh1;
     * };
     *
     * struct tpacket_block_desc {
     * 	 __u32 version;
     * 	 __u32 offset_to_priv;
     * 	 union tpacket_bd_header_u hdr;
     *  }
     *
     */
    private void dumpBlockHeader(ByteBuffer buffer, int blockSize, int block) {
        Formatter formatter = new Formatter();
        int base = block * blockSize;

        int block_status = buffer.getInt(base + 8 + 0);
        int num_pkts = buffer.getInt(base + 8 + 4);
        int offset_to_first__pkt = buffer.getInt(base + 8 + 8);
        int blk_len = buffer.getInt(base + 8 + 12);

        // block header
        dump(formatter, buffer, base + 0 +  0,  4);
        formatter.format(" ");
        dump(formatter, buffer, base + 0 +  4,  4);
        formatter.format(" ");

        dump(formatter, buffer, base + 8 +  0,  4);
        formatter.format(" ");
        dump(formatter, buffer, base + 8 +  4,  4);
        formatter.format(" ");
        dump(formatter, buffer, base + 8 +  8,  4);
        formatter.format(" ");
        dump(formatter, buffer, base + 8 + 12 , 4);

        System.out.println(formatter.toString());

        if (num_pkts > 0) {
            int packets = Math.min(num_pkts, 16);
            int offset = base + offset_to_first__pkt;
            for (int i = 0; i < packets; i++) {
                dumpPacketHeader(buffer, offset);
                System.out.println();
                offset += buffer.getInt(offset);
            }
        }
    }

    /*
    02:00:00:00 30:00:00:00 21:00:00:00 02:00:00:00 30:00:00:00 78:01:00:00               | 28
     next_ofst        sec        nsec     snaplen       len        status     max   net   |
    A8:00:00:00 84:38:30:5E 5C:AF:57:35 56:00:00:00 56:00:00:00 01:00:00:20 | 52:00:60:00 | F2:B6:F1:1F: 00:00:00:00: 00:00:00:00: 00:00:00:00: 00:00:00:00: 11:00:86:DD: 02:00:00:00: 01:00:04:06: A0:36:9F:4D:42:4C:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:38:EA:A7:16:8F:84:A0:36

    00:00:00:00 84:38:30:5E 5D:70:5A:35 4E:00:00:00 4E:00:00:00 01:00:00:20 | 52:00:60:00 | F2:B6:F1:1F:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:11:00:86:DD:02:00:00:00:01:00:00:06:38:EA:A7:16:8F:84:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00




    */
    /**
     * struct tpacket3_hdr {
     * 	__u32		tp_next_offset;
     * 	__u32		tp_sec;
     * 	__u32		tp_nsec;
     * 	__u32		tp_snaplen;
     * 	__u32		tp_len;
     * 	__u32		tp_status;
     * 	__u16		tp_mac;
     * 	__u16		tp_net;
     * 	union {
     * 		struct tpacket_hdr_variant1 hv1;
     *        };
     * 	__u8		tp_padding[8];
     * };
     *
     *
     * struct ethhdr {
     * 	 unsigned char	h_dest[ETH_ALEN];
     * 	 unsigned char	h_source[ETH_ALEN];
     *  	__be16		h_proto;
     * } __attribute__((packed));
     *
     * 
     *
     *
     */
    private void dumpPacketHeader(ByteBuffer buffer, int base) {

        int tp_mac = buffer.getShort(base + 24);
        int tp_net = buffer.getShort(base + 26);

        short eth_proto = buffer.getShort(base + tp_mac + 12);
        if (eth_proto != (short)0xDD86) {
            System.out.println("packet: " + String.format("0x%X", eth_proto));
            return;
        }

        Formatter formatter = new Formatter();

        // packet header
        dump(formatter, buffer, base +  0,  4);
        formatter.format(" ");
        dump(formatter, buffer, base +  4,  4);
        formatter.format(" ");
        dump(formatter, buffer, base +  8,  4);
        formatter.format(" ");
        dump(formatter, buffer, base + 12,  4);
        formatter.format(" ");
        dump(formatter, buffer, base + 16,  4);
        formatter.format(" ");
        dump(formatter, buffer, base + 20 , 4);

        formatter.format(" | ");
        dump(formatter, buffer, base + 24 , 4);

        formatter.format(" | ");
        int snaplen = buffer.getInt(base + 12);
        snaplen -= 24;
        dump(formatter, buffer, base + 28 , snaplen);



        formatter.format("\n    ethhdr: ");
        dump(formatter, buffer, base + tp_mac, 14);
        formatter.format("\n       net: ");
        dump(formatter, buffer, base + tp_net, 64);



        System.out.println(formatter.toString());
    }

    /**
     * dumps part of the buffer to screen
     * @param b buffer
     * @param from start index
     * @param len number of bytes to dump
     */
    public static void dump(ByteBuffer b, int from, int len) {
        Formatter formatter = new Formatter();
        dump(formatter, b, from, len);
        System.out.println(formatter.toString());
    }

    /**
     * dumps part of the buffer to formatter
     * @param formatter formatter to dump into
     * @param b buffer
     * @param from start index
     * @param len number of bytes to dump
     * @return updated formatter
     */
    private static Formatter dump(Formatter formatter, ByteBuffer b, int from, int len) {
        int to = from + len;
        for (int i = from; i < to - 1; i++) {
            int d = b.get(i);
            d &= 0x000000FF;
            formatter.format("%02X:", d);
        }
        if (len > 0) {
            formatter.format("%02X", b.get(to - 1));
        }
        return formatter;
    }

}
