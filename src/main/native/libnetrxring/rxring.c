
#define _GNU_SOURCE

#include <errno.h>
#include <string.h>


#include <netdb.h>
#include <netinet/in.h>
#include <unistd.h>

#include <sys/socket.h>
#include <sys/mman.h>
#include <sys/time.h>

#include <linux/if_packet.h>
#include <linux/if_ether.h>
#include <linux/ip.h>


#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <assert.h>
#include <net/if.h>
#include <arpa/inet.h>
#include <poll.h>
#include <signal.h>
#include <inttypes.h>





#include "rxring.h"

struct ring {
	struct iovec *rd;
	uint8_t *map;
	struct tpacket_req3 req;
};


/*
 * Class:     xnetp_poc_sockets_RxRing
 * Method:    _socket
 * Signature: (Ljava/nio/ByteBuffer;III)I
 */
JNIEXPORT jint JNICALL Java_xnetp_poc_sockets_RxRing__1socket
  (JNIEnv *env, jclass _class, jint if_index, jobject ring, jint blocks, jint block_size, jint frame_size)
{
	int err, i, fd, v = TPACKET_V3;
	struct sockaddr_ll ll;

	fd = socket(AF_PACKET, SOCK_RAW, htons(ETH_P_ALL));
	if (fd < 0) {
		return fd;
	}

	err = setsockopt(fd, SOL_PACKET, PACKET_VERSION, &v, sizeof(v));
	if (err < 0) {
		return err;
	}

    struct tpacket_req3 req;

	memset(&req, 0, sizeof(req));
	req.tp_block_size = block_size;
	req.tp_frame_size = frame_size;
	req.tp_block_nr = blocks;
	req.tp_frame_nr = (block_size * blocks) / frame_size;
	req.tp_retire_blk_tov = 60;
	req.tp_feature_req_word = TP_FT_REQ_FILL_RXHASH;

	err = setsockopt(fd, SOL_PACKET, PACKET_RX_RING, &req, sizeof(req));
	if (err < 0) {
		return err;
	}

	uint8_t *map;
	map = mmap(NULL, block_size * blocks, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_LOCKED, fd, 0);
	if (map == MAP_FAILED) {
		return -1;
	}

	//printf("lib: open: mapped: 0x%llX\n", (uint64_t)map);
    //printf("lib:           ");

    uint8_t *address  = (*env)->GetDirectBufferAddress(env, ring);
    //memcpy(address, &map, sizeof(map));
    *((uint64_t*)address) = (uint64_t)map;


	memset(&ll, 0, sizeof(ll));
	ll.sll_family = PF_PACKET;
	ll.sll_protocol = htons(ETH_P_ALL);
	ll.sll_ifindex = if_index;
	ll.sll_hatype = 0;
	ll.sll_pkttype = 0;
	ll.sll_halen = 0;

	err = bind(fd, (struct sockaddr *) &ll, sizeof(ll));
	if (err < 0) {
		return err;
	}

	return fd;
}

/*
 * Class:     xnetp_poc_sockets_RxRing
 * Method:    _poll
 * Signature: ()Z
 */
JNIEXPORT jint JNICALL Java_xnetp_poc_sockets_RxRing__1poll
  (JNIEnv *env, jclass _class, jint fd, jint timeout)
{
	struct pollfd pfd;
	//memset(&pfd, 0, sizeof(pfd));
	pfd.fd = fd;
	pfd.events = POLLIN | POLLERR;
	pfd.revents = 0;

    // timeout in milliseconds:
    //    0 - return immediately
    //  *<0 - infinite
	return poll(&pfd, 1, timeout);
}

/*
 * Class:     xnetp_poc_sockets_RxRing
 * Method:    _close
 * Signature: (I)I
 */
JNIEXPORT jint JNICALL Java_xnetp_poc_sockets_RxRing__1close
  (JNIEnv *env, jclass _class, jint sd, jobject ring, jint blocks, jint block_size)
{
    void *address  = (*env)->GetDirectBufferAddress(env, ring);
	munmap(address, blocks * block_size);
    return close(sd);
}


/*
 * Class:     xnetp_poc_sockets_RxRing
 * Method:    _X_close
 * Signature: (ILjava/nio/ByteBuffer;)I
 */
JNIEXPORT jint JNICALL Java_xnetp_poc_sockets_RxRing__1X_1close
  (JNIEnv *env, jclass _class, jint sd, jobject ring, jint blocks, jint block_size)
{
    void *address  = (*env)->GetDirectBufferAddress(env, ring);

	uint8_t *mapped;
    //memcpy(&mapped, address, sizeof(mapped));

    mapped = (uint8_t*) *((uint64_t*)address);


	//printf("lib: close: mapped: 0x%llX\n", (long long)mapped);

	munmap(mapped, blocks * block_size);
    return close(sd);
}

/*
 * Class:     xnetp_poc_sockets_RxRing
 * Method:    _errno
 * Signature: ()I
 */
JNIEXPORT jint JNICALL Java_xnetp_poc_sockets_RxRing__1errno
  (JNIEnv *env, jclass _class)
{
    return errno;
}
