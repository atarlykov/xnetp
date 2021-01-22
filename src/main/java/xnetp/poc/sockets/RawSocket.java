package xnetp.poc.sockets;

import java.nio.ByteBuffer;

public class RawSocket {

    /**
     * protocol families as declared in linux kernel
     */
    public static final int AF_INET;
    public static final int AF_INET6;

    /**
     * these reflects defines in C code,
     * todo: set them up in runtime and|or move to RawSocketX
     */
    public static final int BUF_IPV6_HEADER_LENGTH          = 32;
    public static final int BUF_IPV6_HEADER_ADDR_SHIFT      = 8;
    public static final int BUF_IPV6_HEADER_DATALEN_SHIFT   = 28;


    /*
     * library loading and necessary optimization
     */
    static {
        System.loadLibrary("xnetprs");
        // get kernel level declarations
        AF_INET  = _AF_INET();
        AF_INET6 = _AF_INET6();
    }

    /**
     * separate class to work with ipv4 version of raw sockets,
     * provides the same interface as ipv6, just calls another low level methods.
     * it' better to use specific class instead of the base one
     * to remove possible virtual checks in a code
     */
    public static class RawSocket4 extends RawSocket {

        /**
         * facade method to open ipv4 raw socket
         * @param _protocol specific ip protocol
         * @return raw socket instance to work with
         */
        public static RawSocket4 open(int _protocol) {
            int result = _socket(AF_INET, _protocol);
            if (result != -1) {
                return new RawSocket4(result, _protocol);
            } else {
                throw new RuntimeException("error opening socket, errno: " + RawSocket._errno());
            }
        }

        /**
         * constructs container object for the specified socket descriptor
         * @param _sd socket descriptor
         * @param _protocol protocol
         */
        private RawSocket4(int _sd, int _protocol) {
            super(_sd, _protocol);
        }

        /**
         * calls low level library to receive ip packet and
         * populates the specified buffer with data and source address,
         * @see RawSocket#_receive4(int, ByteBuffer) for buffer structure
         * @param buffer buffer to populate
         * @return result of the operation, -1 in case of any error
         */
        public int receive(ByteBuffer buffer) {
            return RawSocket._receive4(sd, buffer);
        }

        /**
         * calls low level library to send ip packet
         * with data and destination address from the specified buffer,
         * @see RawSocket#_send4(int, ByteBuffer, int) for buffer structure
         * @param buffer buffer to send and address to use
         * @param length packet data length
         * @return result of the operation, -1 in case of any error
         */
        public int send(ByteBuffer buffer, int length) {
            return RawSocket._send4(sd, buffer, length);
        }

        /**
         * calls low level library to send a bunch of messages at once
         * @param buffer buffer with all the messages to be sent
         * @param msglen number of messages in the buffer
         * @param block number of bytes per each message container,
         *              buffer format: [ [block] [block] ... [block] ]
         *              block format:  [ [header 16bytes] [data]  empty   ]
         *              header format: [ [family 2b] [port 2b] [address 4 bytes] [length 4 bytes] empty ]
         * @return result of the operation, -1 in case of any error
         */
        public int sendmmsg(ByteBuffer buffer, int msglen, int block) {
            return RawSocket._sendmmsg4(sd, buffer, msglen, block);
        }

        /**
         * calls low level library to receive a bunch of messages at once,
         * blocks only to wait for the  1st message, more messages returned
         * only if they are already in receive queue (@see MSG_WAITFORONE socket option)
         * @param buffer buffer to be populated with the messages and src addresses received
         * @param msgsmax max number of messages to receive, can't be more than c code constant (todo: refactor)
         * @param block number of bytes per each message container,
         *              buffer format: [ [block] [block] ... [block] ]
         *              block format:  [ [header 16 bytes] [data]  empty   ]
         *
         *              header format: [ [family 2b] [port 2b] [address 4b] [length 4 bytes] empty ]
         *              todo: review global header format
         * @return number of messages received or -1 in case of any error
         */
        public int recvmmsg(ByteBuffer buffer, int msgsmax, int block) {
            return RawSocket._recvmmsg4(sd, buffer, msgsmax, block);
        }
    }

    /**
     * separate class to work with ipv6 version of raw sockets,
     * provides the same interface as ipv4, just calls another low level methods.
     * it' better to use specific class instead of the base one
     * to remove possible virtual checks in a code
     */
    public static class RawSocket6 extends RawSocket {

        /**
         * facade method to open ipv6 raw socket
         * @param _protocol specific ip protocol
         * @return raw socket instance to work with
         */
        public static RawSocket6 open(int _protocol) {
            int result = _socket(AF_INET6, _protocol);
            if (result != -1) {
                return new RawSocket6(result, _protocol);
            } else {
                throw new RuntimeException("error opening socket, errno: " + RawSocket._errno());
            }
        }

        /**
         * constructs container object for the specified socket descriptor
         * @param _sd socket descriptor
         * @param _protocol protocol
         */
        private RawSocket6(int _sd, int _protocol) {
            super(_sd, _protocol);
        }

        /**
         * calls low level library to receive ip packet and
         * populates the specified buffer with data and source address,
         * @see RawSocket#_receive6(int, ByteBuffer) for buffer structure
         * @param buffer buffer to populate
         * @return result of the operation, -1 in case of any error
         */
        public int receive(ByteBuffer buffer) {
            return RawSocket._receive6(sd, buffer);
        }


        /**
         * calls low level library to send ip packet
         * with data and destination address from the specified buffer,
         * @see RawSocket#_send6(int, ByteBuffer, int) for buffer structure
         * @param buffer buffer to send and address to use
         * @param length packet data length
         * @return result of the operation, -1 in case of any error
         */
        public int send(ByteBuffer buffer, int length) {
            return RawSocket._send6(sd, buffer, length);
        }

        /**
         * calls low level library to send a bunch of messages at once
         * @param buffer buffer with all the messages to be sent
         * @param msglen number of messages in the buffer
         * @param slotSize number of bytes per each slot,
         *              buffer format: [ [slot] [slot] ... [slot] ]
         *              block format:  [ [slot header 32 bytes] [ip header reserve 40] [data]  empty   ]
         *              slot header:   [ 8 bytes] [ipv6 address 16] [4 bytes] [length 4 bytes]
         * @return result of the operation, -1 in case of any error
         */
        public int sendmmsg(ByteBuffer buffer, int msglen, int slotSize) {
            return RawSocket._sendmmsg6(sd, buffer, msglen, slotSize);
        }

        /**
         * calls low level library to receive a bunch of messages at once,
         * blocks only to wait for the  1st message, more messages returned
         * only if they are already in receive queue (@see MSG_WAITFORONE socket option)
         * @param buffer buffer to be populated with the messages and src addresses received
         * @param msgsmax max number of messages to receive, can't be more than c code constant (todo: refactor)
         * @param slotSize number of bytes per each slot,
         *              buffer format: [ [slot] [slot] ... [slot] ]
         *              block format:  [ [slot header 32 bytes] [ip header reserve 40] [data]  empty   ]
         *              slot header:   [ [family 2b] [port 2b] [flow info 4b] [address 16b] [length 4 bytes]]
         * @return number of messages received or -1 in case of any error
         */
        public int recvmmsg(ByteBuffer buffer, int msgsmax, int slotSize) {
            return RawSocket._recvmmsg6(sd, buffer, msgsmax, slotSize);
        }
    }

    /**
     * facade method to open ipv4 and ipv6 sockets,
     * it' better to use specific class on the calling side instead of the base one
     * to remove possible virtual checks in a code
     * @param _family protocol family, @see AF_INET and AF_INET6
     * @param _protocol protocol code
     * @return instance of the appropriate socket implementation
     */
    public static RawSocket open(int _family, int _protocol) {
        if (_family == AF_INET) {
            return RawSocket4.open(_protocol);
        } else if (_family == AF_INET6) {
            return RawSocket6.open(_protocol);
        } else {
            throw new IllegalArgumentException("unknown protocol family specified: " + _family);
        }
    }

    /**
     * socket file descriptor from native code
     */
    protected int sd;
    /**
     * protocol code as will be specified in ip headers
     */
    protected int protocol;

    /**
     * empty method to be called for library
     * initialization and possible exception
     * in a convenient point
     */
    public static void load() {
    }

    /**
     * the only allowed constructor
     * @param _sd ref to socket descriptor
     * @param _protocol protocol
     */
    protected RawSocket(int _sd, int _protocol) {
        sd = _sd;
        protocol = _protocol;
    }

    /**
     * closes the stored socket descriptor
     * @return result of the operations, -1 in case of any error
     */
    public int close() {
        return _close(sd);
    }

    /**
     * @return error code after any operation that returned error flag (-1)
     */
    public int errno() {
        return _errno();
    }


    /**
     * sets SO_SNDBUF option on this socket descriptor
     * @param _size socket's send buffer size in bytes
     * @return result of the operation
     * @return
     */
    public int setSendBufferSize(int _size) {
        return _setSendBufferSize(sd, _size);
    }


    /**
     * sets SO_RCVBUF option on this socket descriptor
     * @param _size socket's receive buffer size in bytes
     * @return result of the operation
     */
    public int setReceivedBufferSize(int _size) {
        return _setReceiveBufferSize(sd, _size);
    }






    /**
     * @return kernel level constant for ipv4
     */
    private native static int _AF_INET();
    /**
     * @return kernel level constant for ipv6
     */
    private native static int _AF_INET6();

    /**
     * opens low level raw socket via system call
     * @param family protocol family, @see (AF_INET and AF_INET6)
     * @param protocol protocol inside ip packets, only packets
     *                 with this value in ip header will be received and
     *                 this value will be specified while sending packets
     * @return socket descriptor
     */
    private native static int _socket(int family, int protocol);

    /**
     * closes the specified socket descriptor
     * @return result of the operations, -1 in case of any error
     * @param socket socket descriptor ro be closed
     */
    private native static int _close(int socket);

    /**
     * sets SO_SNDBUF option on the specified socket descriptor
     * @param socket socket descriptor
     * @param size socket's send buffer size in bytes
     * @return result of the operation
     */
    private native static int _setSendBufferSize(int socket, int size);

    /**
     * sets SO_RCVBUF option on the specified socket descriptor
     * @param socket socket descriptor
     * @param size socket's receive buffer size in bytes
     * @return result of the operation
     */
    private native static int _setReceiveBufferSize(int socket, int size);


    private native static int _receive4(int socket, ByteBuffer buffer);
    private native static int _receive6(int socket, ByteBuffer buffer);

    private native static int _send4(int socket, ByteBuffer buffer, int length);
    private native static int _send6(int socket, ByteBuffer buffer, int length);

    private native static int _sendmmsg4(int socket, ByteBuffer buffer, int msglen, int bufferBlockSize);
    private native static int _sendmmsg6(int socket, ByteBuffer buffer, int msglen, int bufferBlockSize);


    private native static int _recvmmsg4(int socket, ByteBuffer buffer, int msgsmax, int bufferBlockSize);
    private native static int _recvmmsg6(int socket, ByteBuffer buffer, int msgsmax, int bufferBlockSize);


    /**
     * @return errno result with the last error
     */
    private native static int _errno();

    //    private native static
    // SO_BINDTODEVICE | bind
    // SO_ATTACH_FILTER (since Linux 2.2), SO_ATTACH_BPF
}
