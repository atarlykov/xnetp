package xnetp.poc.net;

/**
 * Enumeration of xnetp packet types for testing
 */
public enum PacketType {

    // minimal available packet,
    // only extended header
    MINIMAL((byte)0x01, 32),

    // single attribute access
    ATTRIBUTE((byte)0x02, 48),

    // attribute update,
    // group update
    ATTRIBUTE_GROUP((byte)0x03, 96),

    // packet with incorrect length,
    // could be any type of packet,
    // !!!  THIS MUST BE THE LAST TYPE IN ENUM !!!
    ERROR((byte)0x04, 0);


    // size of packet data in bytes,
    // except for ip fixed header
    int length;
    // code ot this packet type,
    // will be used in network packets
    byte code;

    /**
     * allowed constructor
     * @param _length @see length
     */
    PacketType(byte _code, int _length) {
        this.code = _code;
        this.length = _length;
    }

    /**
     * @param _type packet type
     * @return enumerated value by type
     */
    public static PacketType byType(byte _type) {
        switch (_type) {
            case 0x01: return MINIMAL;
            case 0x02: return ATTRIBUTE;
            case 0x03: return ATTRIBUTE_GROUP;
            default: return ERROR;
        }
    }

    @Override
    public String toString() {
        switch (this) {
            case MINIMAL: return "minimal packet [ipv6 + ext] headers ";
            case ATTRIBUTE: return "attribute simple, minimal plus some data";
            case ATTRIBUTE_GROUP: return "attribute group, more data";
            case ERROR: return "incorrect packets";
        }
        return super.toString();
    }
}
