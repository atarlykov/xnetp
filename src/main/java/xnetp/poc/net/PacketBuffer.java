package xnetp.poc.net;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Formatter;
import java.util.concurrent.ThreadLocalRandom;



/**
 * container class to hold direct buffer with packets' data,
 * necessary additional information and
 * utility functions to access the buffer
 *
 * [SLOT HEADER: 32]  [IP6 HEADER: 40]  [DATA]
 */
public class PacketBuffer {

    // these are copied from RawSocket and
    // should be refactored to be in one place
    // and correlated with defines in RawSocket,
    // this is service header for each slot
    public static final int SLOT_HEADER_LENGTH          = 32;
    public static final int SLOT_HEADER_ADDR_SHIFT      = 8;
    public static final int SLOT_HEADER_DATALEN_SHIFT   = 28;

    /**
     * size of fixed ipv6 header,
     * only in received packets for now
     */
    public static final int SLOT_IPV6_HDR_SHIFT       = SLOT_HEADER_LENGTH;
    public static final int PACKET_IPV6_HDR_LENGTH      = 40;

    /**
     * shift to data in each slot, that includes
     * slot header plus ipv6 header
     */
    public static final int SLOT_DATA_SHIFT             = SLOT_HEADER_LENGTH + PACKET_IPV6_HDR_LENGTH;

    /**
     * xnetp specific packets
     */
    public static final int PACKET_XNETP_EXT_HDR_LENGTH = 32;
    public static final int SLOT_XNETP_EXT_HDR_SHIFT     = SLOT_DATA_SHIFT;
    public static final int SLOT_XNETP_DATA_SHIFT        = SLOT_XNETP_EXT_HDR_SHIFT + PACKET_XNETP_EXT_HDR_LENGTH;



    /**
     * number of packet's slots allocated in buffer,
     * total buffer size MUST be slots*slotSize
     */
    public final int slots;
    /**
     * size of each slot in bytes
     */
    public final int slotSize;

    /**
     *  number of packets in this buffer,
     *  it's always in 0...slots,
     *  updated by application to reflect the actual number
     */
    public int packets;

    /**
     * buffer itself, sized as slots*slotSize
     */
    public ByteBuffer buffer;

    /**
     * allowed constructor
     */
    protected PacketBuffer(ByteBuffer _buffer, int _slots, int _slotSize) {
        buffer = _buffer;
        slots = _slots;
        slotSize = _slotSize;
    }

    /**
     * main factory method to create buffers
     * @param _slots number of slots
     * @param _slotSize each slot size
     * @return initialized buffer (native byte order) with the correct size
     */
    public static PacketBuffer allocate(int _slots, int _slotSize) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(_slots * _slotSize).order(ByteOrder.nativeOrder());
        return new PacketBuffer(buffer, _slots, _slotSize);
    }

    /**
     * populates destination address in slot's service header
     * @param slot slot index
     * @param address destination network address
     */
    public void populateSlotAddress(int slot, byte[] address) {
        buffer.position(slot * slotSize + SLOT_HEADER_ADDR_SHIFT).put(address);
    }

    /**
     * populates specific packet data into the specified packet slot
     * @param slot slot number to populate
     * @param pType type of the packet to populate
     * @param uniVolumes number of volume segments for UNI addresses
     * @param uniSegments number of address segments for UNI addresses
     * @param uniAddresses number of addresses inside each segment for UNI address
     * @param packetCorrect true if correct package should be generated and false otherwise
     */
    public void populatePacketData(
            int slot,
            PacketType pType,
            int uniVolumes,
            int uniSegments,
            int uniAddresses,
            boolean packetCorrect)
    {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        int base = slot * slotSize;

        // extract correct values to forge id packet is incorrect
        int pTypeLength = pType.length;
        byte pTypeCode = pType.code;

        if (!packetCorrect) {
            // packet is incorrect, select what to forge - length or type
            if (random.nextInt(2) < 1) {
                pTypeLength++;
            } else {
                pTypeCode = PacketType.ERROR.code;
                // only minimal packets with incorrect type
                pTypeLength = PacketType.MINIMAL.length;
            }
        }
        
        // specify packet length to send
        buffer.putInt(base + SLOT_HEADER_DATALEN_SHIFT, pTypeLength);

        // position before last 4 bytes of UIN
        buffer.position(base + SLOT_XNETP_EXT_HDR_SHIFT + 16);

        // volume index [0..FF]
        buffer.put((uniVolumes == 1) ? (byte)0 : (byte)random.nextInt(uniVolumes));

        // segment index [0..FF]
        buffer.put((uniSegments == 1) ? (byte)0 : (byte)random.nextInt(uniSegments));

        // address inside segment [1..0xFFFF]
        int address = random.nextInt(uniAddresses) + 1;
        buffer.put((byte)(address >>  8));
        buffer.put((byte)(address));

        // next is packet type
        buffer.put(pTypeCode);
    }

    /**
     * populates fixed values in packet's external header,
     * structure (from specification):
     *   8 bit      next header
     *   8 bit      header length
     *   8 bit      g3 relation type                / association/generalization/implementation/aggregation/???
     *   8 bit      g3 variable relation type       / info link/increment/decrement  wtf?
     * 128 bit      UNI (unique node identifier)    / node identifier ipv6, equal to destination ip or no (in case of name service)
     *   8 bit      g3 component type               / attribute/function/internal attr or func (what is being called)
     *   8 bit      g3 component type attr          / additional attributes for previous field, wtf?
     *  16 bit      LCI (local component identifier)/ internal name inside node, 0x0002 - 0xFF00, WTF??
     *  64 bit      reserved
     * -----------------------------
     *  32 bytes
     *
     * UNI will be in form FC::S:A:B:C format, where:
     * S:       addresses segment
     * A:B:C:   sequential index inside each segment [1 ... max]
     *
     * @param slot slot index to populate
     */
    public void populatePacketExtHeaderTemplate(int slot) {

        buffer.position(slot*slotSize + SLOT_XNETP_EXT_HDR_SHIFT);

        buffer.put((byte)0xFF);             // no next header
        buffer.put((byte)0x20);             // always 256 bits / 32 bytes

        buffer.put((byte)0x00);             // just zero
        buffer.put((byte)0x00);             // just zero

        buffer.put(new byte[] {(byte)0xFC, 0, 0, 0,   0, 0, 0, 0,   0, 0, 0, 0,   0, 0, 0, 0});

        buffer.put((byte)0x00);             // here will later go packet type
        buffer.put((byte)0x88);             // just value

        buffer.put((byte)0x12);             // just another
        buffer.put((byte)0x34);             // magic number
    }


    /**
     * get packet type into the specified slot (no IP header)
     * @param slot slot index
     */
    public byte getPacketTypeAsByte(int slot) {
        return buffer.get(slot * slotSize + SLOT_XNETP_DATA_SHIFT - 12);
    }

    /**
     * populates packet type into the specified slot (no IP header)
     * @param slot slot index
     * @param pType packet type
     */
    public void setPacketType(int slot, PacketType pType) {
        setPacketType(slot, pType.code);
    }

    /**
     * populates packet type directly from byte
     * @param slot slot index
     * @param pType packet type
     */
    public void setPacketType(int slot, byte pType) {
        buffer.put(slot * slotSize + SLOT_XNETP_DATA_SHIFT - 12, pType);
    }

    /**
     * packet length, that shouldn't include ip6 header size,
     * only ip payload
     * @param slot slot index
     * @param length length of data to be sent
     */
    public void setPacketLength(int slot, int length) {
        buffer.putInt(slot * slotSize + SLOT_HEADER_DATALEN_SHIFT, length);
    }

    /**
     * @param slot slot index
     * @return copy of uni stored in ext header
     */
    public byte[] getUNI(int slot) {
        byte[] uni = new byte[16];
        buffer.position(slot * slotSize + SLOT_XNETP_EXT_HDR_SHIFT + 4);
        buffer.get(uni);
        return uni;
    }

    /**
     * populates some payload to packet data section
     * @param slot slot index to populate in
     * @param length number of payload bytes to populate (after ext header)
     */
    public void populatePacketPayloadTemplate(int slot, int length) {
        byte[] data = new byte[] {(byte)'d', (byte)'a', (byte)'t', (byte)'a', (byte)':'};

        // make sure length is safe
        length = Math.min(length, slotSize - SLOT_XNETP_DATA_SHIFT);
        buffer.position(slot * slotSize + SLOT_XNETP_DATA_SHIFT);

        int repeat = length / data.length;
        for (int i = 0; i < repeat; i++) {
            buffer.put(data);
        }
        buffer.put(data, 0, length % data.length);
    }

    /**
     * @see PacketBuffer#populatePacketExtHeaderTemplate(int)  for buffer format
     *
     * @param slot slot of a packet to validate
     * @return true if packet is valid and false otherwise
     */
    public boolean validate(int slot) {
        int base = slot * slotSize;
        int size = buffer.getInt(base + SLOT_HEADER_DATALEN_SHIFT);

        byte _type = getPacketTypeAsByte(slot);
        PacketType type = PacketType.byType(_type);

        if (type == PacketType.ERROR) {
            return false;
        }

        if (size != type.length) {
            return false;
        }

        return true;
    }


    /**
     * dumps all packets from the buffer
     */
    public void dump() {
        dump(packets);
    }

    /**
     * dumps specified number of packets from the buffer
     * @param _packets packets tyo dump
     */
    public void dump(int _packets) {
        for (int i = 0; i < _packets; i++) {
            dumpHeader(i);
        }
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

    public static final String ANSI_RESET = "\u001B[0m";
    public static final String ANSI_BLACK = "\u001B[30m";
    public static final String ANSI_RED = "\u001B[31m";
    public static final String ANSI_GREEN = "\u001B[32m";
    public static final String ANSI_YELLOW = "\u001B[33m";
    public static final String ANSI_BLUE = "\u001B[34m";
    public static final String ANSI_PURPLE = "\u001B[35m";
    public static final String ANSI_CYAN = "\u001B[36m";
    public static final String ANSI_WHITE = "\u001B[37m";
    /**
     * dumps packet header dividing into sections,
     * don't use for massive output, it's slow
     * @param slot slot index to dump
     */
    public void dumpHeader(int slot) {
        Formatter formatter = new Formatter();
        int base = slot * slotSize;
        int len = buffer.getInt(base + SLOT_HEADER_DATALEN_SHIFT);

        // slot header
        dump(formatter, buffer, base,  8);
        formatter.format(" ");
        dump(formatter, buffer, base + 8, 16);
        formatter.format(" ");
        dump(formatter, buffer, base + 24, 4);
        formatter.format(" ");
        dump(formatter, buffer, base + 28, 4);
        formatter.format("\u001B[32m  | [IP HDR] |  \u001B[37m");

        // ext header
        base += SLOT_DATA_SHIFT;
        dump(formatter, buffer, base, 4);
        formatter.format(" ");
        dump(formatter, buffer, base + 4, 16);
        formatter.format(" ");
        dump(formatter, buffer, base + 20, 4);

        // payload
        len -= PACKET_XNETP_EXT_HDR_LENGTH;
        if (0 < len) {
            base += PACKET_XNETP_EXT_HDR_LENGTH;
            formatter.format("\u001B[32m [RSRV] \u001B[37m");
            dump(formatter, buffer, base, len);
        }

        System.out.println(formatter.toString());
    }
}
