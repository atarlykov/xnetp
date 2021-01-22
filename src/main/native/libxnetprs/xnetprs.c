
#define _GNU_SOURCE

#include <errno.h>
#include <string.h>


#include <netdb.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>
#include <sys/time.h>



#include "xnetprs.h"


/*
these could be copied in run time to java part
to setup extract functions
*/
#define BUF_IPV4_HEADER_LENGTH          16      /* size of a header in buffer */
#define BUF_IPV4_HEADER_ADDR_SHIFT       4      /* address shift from buffer base  */
#define BUF_IPV4_HEADER_DATALEN_SHIFT    8      /* payload size shift from buffer base */


/*
 IPv6 mode uses the following buffer format:
 [SLOT] [SLOT] [SLOT] ...
 Each slot is:
 [SLOT HEADER: 32]  [IPv6 HEADER: 40]  [DATA]
*/

#define SLOT_HEADER_LENGTH              32
#define SLOT_HEADER_ADDR_SHIFT           8
#define SLOT_HEADER_DATALEN_SHIFT       28

#define SLOT_IPV6_HDR_SHIFT             SLOT_HEADER_LENGTH
#define PACKET_IPV6_HDR_LENGTH          40
#define SLOT_DATA_SHIFT                 (SLOT_HEADER_LENGTH + PACKET_IPV6_HDR_LENGTH)


//#define BUF_IPV6_HEADER_LENGTH          SLOT_HEADER_LENGTH
//#define BUF_IPV6_HEADER_ADDR_SHIFT      SLOT_HEADER_ADDR_SHIFT
//#define BUF_IPV6_HEADER_DATALEN_SHIFT   SLOT_HEADER_DATALEN_SHIFT
//#define BUF_IPV6_HEADER_DATA_SHIFT      BUF_IPV6_HEADER_LENGTH

/* TODO: preallocate per thread data _thread array[]*/
#define BUF_MMSG_MESSAGES_MAX 1024


/*
 * Class:     xnetp_poc_sockets_RawSocket
 * Method:    _AF_INET
 * Signature: ()I
 */
JNIEXPORT jint JNICALL Java_xnetp_poc_sockets_RawSocket__1AF_1INET
  (JNIEnv *env, jclass _class) 
{
    return AF_INET;
}

/*
 * Class:     xnetp_poc_sockets_RawSocket
 * Method:    _AF_INET6
 * Signature: ()I
 */
JNIEXPORT jint JNICALL Java_xnetp_poc_sockets_RawSocket__1AF_1INET6
  (JNIEnv *env, jclass _class) 
{
    return AF_INET6;
}



/*
 * Class:     xnetp_poc_sockets_RawSocket
 * Method:    _socket
 * Signature: (II)I
 */
JNIEXPORT jint JNICALL Java_xnetp_poc_sockets_RawSocket__1socket
  (JNIEnv *env, jclass _class, jint family, jint protocol)
{
  return socket(family, SOCK_RAW, protocol);
}

/*
 * Class:     xnetp_poc_sockets_RawSocket
 * Method:    _close
 * Signature: (I)I
 */
JNIEXPORT jint JNICALL Java_xnetp_poc_sockets_RawSocket__1close
  (JNIEnv *env, jclass _class, jint sd) 
{
    return close(sd);
}



/*
 * Class:     xnetp_poc_sockets_RawSocket
 * Method:    _setSendBufferSize
 * Signature: (II)I
 */
JNIEXPORT jint JNICALL Java_xnetp_poc_sockets_RawSocket__1setSendBufferSize
  (JNIEnv *env, jclass _class, jint sd, jint size)
{
    return setsockopt(sd, SOL_SOCKET, SO_SNDBUF, (void*)&size, sizeof(size));
}

/*
 * Class:     xnetp_poc_sockets_RawSocket
 * Method:    _setReceiveBufferSize
 * Signature: (II)I
 */
JNIEXPORT jint JNICALL Java_xnetp_poc_sockets_RawSocket__1setReceiveBufferSize
  (JNIEnv *env, jclass _class, jint sd, jint size)
{
    return setsockopt(sd, SOL_SOCKET, SO_RCVBUF, (void*)&size, sizeof(size));
}


/*
 * Class:     xnetp_poc_sockets_RawSocket
 * Method:    _receive4
 * Signature: (ILjava/nio/ByteBuffer;)I
 */
JNIEXPORT jint JNICALL Java_xnetp_poc_sockets_RawSocket__1receive4
  (JNIEnv *env, jclass _class, jint sd, jobject buffer)
{
    int result;
    struct sockaddr_in sin;
    socklen_t socklen;
  
    socklen = sizeof(sin);
    memset(&sin, 0, sizeof(struct sockaddr_in));
    sin.sin_family = AF_INET;

    void *bAddress  = (*env)->GetDirectBufferAddress(env, buffer);
    jlong bCapacity = (*env)->GetDirectBufferCapacity(env, buffer);

    result = recvfrom(sd, bAddress + BUF_IPV4_HEADER_LENGTH, bCapacity - BUF_IPV4_HEADER_LENGTH, 0, (struct sockaddr *)&sin, &socklen);
    memcpy(bAddress + BUF_IPV4_HEADER_ADDR_SHIFT, &(sin.sin_addr), sizeof(sin.sin_addr));

    return result;
}

/*
 * Class:     xnetp_poc_sockets_RawSocket
 * Method:    _receive6
 * Signature: (ILjava/nio/ByteBuffer;)I
 */
JNIEXPORT jint JNICALL Java_xnetp_poc_sockets_RawSocket__1receive6
  (JNIEnv *env, jclass _class, jint sd, jobject buffer)
{
    int result;
    struct sockaddr_in6 sin6;
    socklen_t socklen;

    socklen = sizeof(sin6);
    memset(&sin6, 0, sizeof(struct sockaddr_in6));
    sin6.sin6_family = AF_INET6;

    void *bAddress  = (*env)->GetDirectBufferAddress(env, buffer);
    jlong bCapacity = (*env)->GetDirectBufferCapacity(env, buffer);

    result = recvfrom(sd, bAddress + SLOT_HEADER_LENGTH, bCapacity - SLOT_HEADER_LENGTH, 0, (struct sockaddr *)&sin6, &socklen);
    memcpy(bAddress, &(sin6.sin6_addr), sizeof(sin6.sin6_addr));

    return result;
}

/*
 * Class:     xnetp_poc_sockets_RawSocket
 * Method:    _send4
 * Signature: (ILjava/nio/ByteBuffer;I)I
 */
JNIEXPORT jint JNICALL Java_xnetp_poc_sockets_RawSocket__1send4
  (JNIEnv *env, jclass _class, jint sd, jobject buffer, jint data_len)
{
    int result;
    struct sockaddr_in sin;
    socklen_t socklen = sizeof(sin);

    void *bAddress  = (*env)->GetDirectBufferAddress(env, buffer);

    sin.sin_family = AF_INET;
    sin.sin_port = (in_port_t)0;
    memcpy(&(sin.sin_addr), bAddress + BUF_IPV4_HEADER_ADDR_SHIFT, sizeof(sin.sin_addr));

    result = sendto(sd, bAddress + BUF_IPV4_HEADER_LENGTH, data_len, 0, (struct sockaddr*)&sin, socklen);

    return result;
}

/*
 * Class:     xnetp_poc_sockets_RawSocket
 * Method:    _send6
 * Signature: (ILjava/nio/ByteBuffer;I)I
 */
JNIEXPORT jint JNICALL Java_xnetp_poc_sockets_RawSocket__1send6
  (JNIEnv *env, jclass _class, jint sd, jobject buffer, jint length)
{
    int result;
    struct sockaddr_in6 sin6;
    socklen_t socklen;


    void *bAddress  = (*env)->GetDirectBufferAddress(env, buffer);

    socklen = sizeof(sin6);
    memset(&sin6, 0, sizeof(struct sockaddr_in6));
    sin6.sin6_family = AF_INET6;
    memcpy(&(sin6.sin6_addr), bAddress + SLOT_HEADER_ADDR_SHIFT, sizeof(sin6.sin6_addr));

    result = sendto(sd, bAddress + SLOT_DATA_SHIFT, length, 0, (struct sockaddr*)&sin6, socklen);

    return result;
}

/*
 * Class:     xnetp_poc_sockets_RawSocket
 * Method:    _errno
 * Signature: ()I
 */
JNIEXPORT jint JNICALL Java_xnetp_poc_sockets_RawSocket__1errno
  (JNIEnv *env, jclass _class) 
{
    return errno;
}



#if defined(_WIN32)
    #define PLATFORM_NAME "windows" // Windows
    #define __WIN__
#elif defined(_WIN64)
    #define PLATFORM_NAME "windows" // Windows
    #define __WIN__
#elif defined(__CYGWIN__) && !defined(_WIN32)
    #define PLATFORM_NAME "windows" // Windows (Cygwin POSIX under Microsoft Window)
    #define __WIN__
#endif


#if defined(__WIN__)

    #define sendmmsg            __win_sendmmsg
    #define recvmmsg            __win_recvmmsg
    #define MSG_WAITFORONE      0/*empty*/

    struct mmsghdr {
        struct msghdr msg_hdr;  /* Message header */
        unsigned int  msg_len;  /* Number of bytes transmitted */
    };

    jint __win_sendmmsg
        (int sockfd, struct mmsghdr *msgvec, unsigned int vlen, int flags)
    {
        printf("_win_sendmmsg\n");
        ssize_t tmp;
        int i;

        for (i = 0; i < vlen; i++) {
            tmp = sendmsg(sockfd, (const struct msghdr*) &msgvec[i], flags);
            if (tmp == -1) {
                return -1;
            }
        }
        return vlen;
        return -1;
    }

    jint __win_recvmmsg
        (int sockfd, struct mmsghdr *msgvec, unsigned int vlen, int flags, struct timespec *timeout)
    {
        printf("_win_recvmmsg\n");
        ssize_t tmp;

        /* only 1 message is returned */
        tmp = recvmsg(sockfd, (struct msghdr*) &msgvec[0], flags);
        if (tmp == -1) {
            return -1;
        } else {
            /* emulate mmsg: store number of bytes received */
            msgvec[0].msg_len = tmp;
            /* indicate: one message is received */
            return 1;
        }
    }

#endif

#if defined(__TMP_COMMENT__)
#endif


/*
 * Class:     xnetp_poc_sockets_RawSocket
 * Method:    _sendmmsg4
 * Signature: (ILjava/nio/ByteBuffer;II)I
 */
JNIEXPORT jint JNICALL Java_xnetp_poc_sockets_RawSocket__1sendmmsg4
  (JNIEnv *env, jclass _class, jint sd, jobject buffer, jint _msglen, jint _bufferBlockSize)
{
    if (BUF_MMSG_MESSAGES_MAX < _msglen) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"), "Number of messages to send is too big.");
    }

    /* headers for all possible messages*/
    struct mmsghdr headers[BUF_MMSG_MESSAGES_MAX];
    /* messages' pointers*/
    struct iovec messages[BUF_MMSG_MESSAGES_MAX];
    /* destinations addresses for all messages*/
    struct sockaddr_in sins[BUF_MMSG_MESSAGES_MAX];

    /* printf("nsglen: %d  block: %d\n", _msglen, _bufferBlockSize); */
    
    /*
    // buffer structure
    // destination 4 - 16
    // data x
    // datalen int
    // [msg header 32: address 16 + len 4][data][align] ...
    */
    void *bAddress  = (*env)->GetDirectBufferAddress(env, buffer);

    memset(&sins, 0, sizeof(struct sockaddr_in) * _msglen);
	memset(&messages, 0, sizeof(struct iovec) * _msglen);
	memset(headers, 0, sizeof(struct mmsghdr) * _msglen);

    int i;
	for (i = 0; i < _msglen; i++) {
        /* setup destination address*/
        sins[i].sin_addr = *(struct in_addr*)(bAddress + i*_bufferBlockSize);
        /* printf("    addr: %#08X\n", sins[i].sin_addr); */

        /* setup message */
        messages[i].iov_base = bAddress + i*_bufferBlockSize + 32;
        messages[i].iov_len = *(int32_t*)(bAddress + i*_bufferBlockSize + 16);
        /*
        printf("    len: %#08X   %d\n", messages[i].iov_len, messages[i].iov_len);
        printf("       : %d\n", *(char*)(bAddress + i*_bufferBlockSize + 16 + 0));
        printf("       : %d\n", *(char*)(bAddress + i*_bufferBlockSize + 16 + 1));
        printf("       : %d\n", *(char*)(bAddress + i*_bufferBlockSize + 16 + 2));
        printf("       : %d\n", *(char*)(bAddress + i*_bufferBlockSize + 16 + 3));
        */
        /* setup message header */
		headers[i].msg_hdr.msg_iov = &messages[i];
		headers[i].msg_hdr.msg_iovlen = 1;
        /* set destination address for message */
        headers[i].msg_hdr.msg_name = &sins[i];
        headers[i].msg_hdr.msg_namelen = sizeof(struct sockaddr_in);
		/* reset bytes send (already reset) */
		/* headers[i].msg_len = 0; */
	}

	int retval = sendmmsg(sd, headers, _msglen, 0);
    return retval;
}


/*
 * Class:     xnetp_poc_sockets_RawSocket
 * Method:    _recvmmsg4
 * Signature: (ILjava/nio/ByteBuffer;II)I

        NOTE, this is low level related, dangerous
        point to buffer for source addresses

        sizeof(struct sockaddr_in) == 16 bytes
              struct sockaddr_in {
                  sa_family_t    sin_family;     2 bytes
                  in_port_t      sin_port;       2 bytes
                  struct in_addr sin_addr;       4 bytes
              };
              struct in_addr {
                  uint32_t       s_addr;
              };

         sizeof(struct sockaddr_in6) == 28 bytes
              struct sockaddr_in6 {
                  sa_family_t     sin6_family;
                  in_port_t       sin6_port;
                  uint32_t        sin6_flowinfo;
                  struct in6_addr sin6_addr;
                  uint32_t        sin6_scope_id;
              };
              struct in6_addr {
                  unsigned char   s6_addr[16];
              };
 */
JNIEXPORT jint JNICALL Java_xnetp_poc_sockets_RawSocket__1recvmmsg4
  (JNIEnv *env, jclass _class, jint sd, jobject buffer, jint _msgs_max, jint _buffer_block_size)
{
    if (BUF_MMSG_MESSAGES_MAX < _msgs_max) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"), "Number of messages to receive is too big.");
    }

    /* headers for all possible messages*/
    struct mmsghdr headers[BUF_MMSG_MESSAGES_MAX];
    /* messages' pointers*/
    struct iovec messages[BUF_MMSG_MESSAGES_MAX];

	memset(&messages, 0, sizeof(struct iovec) * _msgs_max);
	memset(headers, 0, sizeof(struct mmsghdr) * _msgs_max);

    /* base address of the buffer space */
    void *bAddress  = (*env)->GetDirectBufferAddress(env, buffer);

    int i;
	for (i = 0; i < _msgs_max; i++) {
        uint64_t block_shift = i*_buffer_block_size;

        /* setup message buffer, exclude header */
        messages[i].iov_base = bAddress + block_shift + BUF_IPV4_HEADER_LENGTH;
        messages[i].iov_len = _buffer_block_size - BUF_IPV4_HEADER_LENGTH;

        /* setup message header */
		headers[i].msg_hdr.msg_iov = &messages[i];
		headers[i].msg_hdr.msg_iovlen = 1;

        headers[i].msg_hdr.msg_name = (bAddress + block_shift);
        headers[i].msg_hdr.msg_namelen = BUF_IPV4_HEADER_LENGTH;
	}

	/*
	this will wait for the 1st message and return,
	more messages will be read only they are already in queue
	*/
	int recv_number = recvmmsg(sd, headers, _msgs_max, MSG_WAITFORONE, NULL);
    if (recv_number > 0) {
        /* populate received message size (native byte order) */
        for (i = 0; i < recv_number; i++) {
            uint64_t block_shift = i*_buffer_block_size;
            *(int32_t*)(bAddress + block_shift + BUF_IPV4_HEADER_DATALEN_SHIFT) = headers[i].msg_len;
        }
    }

    return recv_number;
}


/*
 * Class:     xnetp_poc_sockets_RawSocket
 * Method:    _sendmmsg6
 * Signature: (ILjava/nio/ByteBuffer;II)I
 */
JNIEXPORT jint JNICALL Java_xnetp_poc_sockets_RawSocket__1sendmmsg6
  (JNIEnv *env, jclass _class, jint sd, jobject buffer, jint _msglen, jint _slotSize)
{
    if (BUF_MMSG_MESSAGES_MAX < _msglen) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"), "Number of messages to send is too big.");
    }

    /* headers for all possible messages*/
    struct mmsghdr headers[BUF_MMSG_MESSAGES_MAX];
    /* messages' pointers*/
    struct iovec messages[BUF_MMSG_MESSAGES_MAX];
    /* destinations addresses for all messages*/
    struct sockaddr_in6 sins[BUF_MMSG_MESSAGES_MAX];

    /* printf("nsglen: %d  block: %d\n", _msglen, _bufferBlockSize); */

    void *bAddress  = (*env)->GetDirectBufferAddress(env, buffer);

    memset(&sins, 0, sizeof(struct sockaddr_in6) * _msglen);
    memset(&messages, 0, sizeof(struct iovec) * _msglen);
    memset(headers, 0, sizeof(struct mmsghdr) * _msglen);

    int i;
    for (i = 0; i < _msglen; i++) {
        /* printf("message: %d\n", i); */

        /* setup destination address*/
        sins[i].sin6_addr = *(struct in6_addr*)(bAddress + i*_slotSize + SLOT_HEADER_ADDR_SHIFT);
        /*
        printf("    address: ");
        int __i1;
        for (__i1 = 0; __i1 < 16; __i1++) {
            printf("%02X ", sins[i].sin6_addr.s6_addr[__i1]);
        }
        printf("\n");
        */

        /* setup message */
        messages[i].iov_base = bAddress + i*_slotSize + SLOT_DATA_SHIFT;
        messages[i].iov_len = *(int32_t*)(bAddress + i*_slotSize + SLOT_HEADER_DATALEN_SHIFT);
        /*
        printf("    len: %#08x   %d\n", messages[i].iov_len, messages[i].iov_len);
        printf("       : %02x\n", *(char*)(bAddress + i*_bufferBlockSize + BUF_IPV6_HEADER_DATALEN_SHIFT + 0));
        printf("       : %02x\n", *(char*)(bAddress + i*_bufferBlockSize + BUF_IPV6_HEADER_DATALEN_SHIFT + 1));
        printf("       : %02x\n", *(char*)(bAddress + i*_bufferBlockSize + BUF_IPV6_HEADER_DATALEN_SHIFT + 2));
        printf("       : %02x\n", *(char*)(bAddress + i*_bufferBlockSize + BUF_IPV6_HEADER_DATALEN_SHIFT + 3));
        */
        /* setup message header */
        headers[i].msg_hdr.msg_iov = &messages[i];
        headers[i].msg_hdr.msg_iovlen = 1;
        /* set destination address for message */
        headers[i].msg_hdr.msg_name = &sins[i];
        headers[i].msg_hdr.msg_namelen = sizeof(struct sockaddr_in6);
        /* reset bytes send (already reset) */
        /* headers[i].msg_len = 0; */
    }

	int retval = sendmmsg(sd, headers, _msglen, 0);
    return retval;
}



/*
 * Class:     xnetp_poc_sockets_RawSocket
 * Method:    _recvmmsg6
 * Signature: (ILjava/nio/ByteBuffer;II)I

        struct mmsghdr {
            struct msghdr msg_hdr;  // Message header
            unsigned int  msg_len;  // Number of received bytes for header
        };

        struct iovec {                    // Scatter/gather array items
            void  *iov_base;              // Starting address
            size_t iov_len;               // Number of bytes to transfer
        };

        struct msghdr {
            void         *msg_name;       // Optional address
            socklen_t     msg_namelen;    // Size of address
            struct iovec *msg_iov;        // Scatter/gather array
            size_t        msg_iovlen;     // # elements in msg_iov
            void         *msg_control;    // Ancillary data, see below
            size_t        msg_controllen; // Ancillary data buffer len
            int           msg_flags;      // Flags on received message
        };

        NOTE, this is low level related, dangerous
        point to buffer for source addresses

         sizeof(struct sockaddr_in6) == 28 bytes
              struct sockaddr_in6 {
                  sa_family_t     sin6_family;     2 bytes
                  in_port_t       sin6_port;       2 bytes
                  uint32_t        sin6_flowinfo;   4 bytes
                  struct in6_addr sin6_addr;      16 bytes
                  uint32_t        sin6_scope_id;   4 bytes
              };
              struct in6_addr {
                  unsigned char   s6_addr[16];
              };
 */
JNIEXPORT jint JNICALL Java_xnetp_poc_sockets_RawSocket__1recvmmsg6
  (JNIEnv *env, jclass _class, jint sd, jobject buffer, jint _msgs_max, jint _slotSize)
{
    if (BUF_MMSG_MESSAGES_MAX < _msgs_max) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"), "Number of messages to receive is too big.");
    }

    /* headers and messages' pointers */
    struct mmsghdr headers[BUF_MMSG_MESSAGES_MAX];
    struct iovec   messages[BUF_MMSG_MESSAGES_MAX];
    memset(headers, 0, sizeof(struct mmsghdr) * _msgs_max);
    memset(&messages, 0, sizeof(struct iovec) * _msgs_max);

    /* base address of the buffer space */
    void *bAddress  = (*env)->GetDirectBufferAddress(env, buffer);

    int i;
    for (i = 0; i < _msgs_max; i++) {
        uint64_t slot_base = i*_slotSize;

        // setup message buffer, ip header will not be received
        // so we just reserve space for future use
        messages[i].iov_base = bAddress + slot_base + SLOT_DATA_SHIFT;
        messages[i].iov_len = _slotSize - SLOT_DATA_SHIFT;

        // setup message header
        headers[i].msg_hdr.msg_iov = &messages[i];
        headers[i].msg_hdr.msg_iovlen = 1;

        headers[i].msg_hdr.msg_name = bAddress + slot_base;  // this will save all sockaddr_in6 fields
        headers[i].msg_hdr.msg_namelen = SLOT_HEADER_LENGTH; // that's too much, only 28 from 32 will be saved
    }

    /*
    this will wait for the 1st message and return,
    more messages will be read only they are already in queue
    */
	int recv_number = recvmmsg(sd, headers, _msgs_max, MSG_WAITFORONE, NULL);
    if (recv_number > 0) {
        /* populate received message size (native byte order) */
        for (i = 0; i < recv_number; i++) {
            uint64_t slot_base = i*_slotSize;
            *(int32_t*)(bAddress + slot_base + SLOT_HEADER_DATALEN_SHIFT) = headers[i].msg_len;
        }
    }

    return recv_number;
}
