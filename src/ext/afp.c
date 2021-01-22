#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <iostream>
#include <unistd.h>

#include <sys/time.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <net/if.h>
#include <linux/if_packet.h>
#include <net/ethernet.h> /* the L2 protocols */
#include <time.h>

/*

Build it:
g++ ../fastnetmon_packet_parser.c -ofastnetmon_packet_parser.o -c
g++ af_packet.cpp fastnetmon_packet_parser.o -lboost_thread -lboost_system -lpthread 

*/

/*
 * returns the difference, in microseconds, between tv1 and tv2, which must
 * be ordered.
 */
static inline long long tv_diff(struct timeval *tv1, struct timeval *tv2)
{
        long long ret;
  
        ret = (long long)(tv2->tv_sec - tv1->tv_sec) * 1000000LL;
	ret += tv2->tv_usec - tv1->tv_usec;
        return ret;
}

/*
Parser files:
https://github.com/FastVPSEestiOu/fastnetmon/blob/master/src/fastnetmon_packet_parser.c
https://github.com/FastVPSEestiOu/fastnetmon/blob/master/src/fastnetmon_packet_parser.h
*/
// Copy and paste from netmap code
void consume_pkt(u_char* buffer, int len) {
    /*
    struct pfring_pkthdr packet_header;
    memset(&packet_header, 0, sizeof(packet_header));
    packet_header.len = len;
    packet_header.caplen = len;

    // We do not calculate timestamps because timestamping is very CPU intensive operation:
    // https://github.com/ntop/PF_RING/issues/9
    u_int8_t timestamp = 0;
    u_int8_t add_hash = 0;
    fastnetmon_parse_pkt((u_char*)buffer, &packet_header, 4, timestamp, add_hash);
    */

    //char print_buffer[512];
    //fastnetmon_print_parsed_pkt(print_buffer, 512, (u_char*)buffer, &packet_header);
    //printf("%s\n", print_buffer);
    // logger.info("%s", print_buffer);
}

// Get interface number by name
int get_interface_number_by_device_name(int socket_fd, std::string interface_name) {
    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));

    if (interface_name.size() > IFNAMSIZ) {
        return -1;
    }

    strncpy(ifr.ifr_name, interface_name.c_str(), sizeof(ifr.ifr_name));
    
    if (ioctl(socket_fd, SIOCGIFINDEX, &ifr) == -1) {
        return -1;
    }

    return ifr.ifr_ifindex;
}

unsigned int af_packet_threads = 1;

uint64_t received_packets = 0;


int setup_socket(std::string interface_name, int fanout_group_id) {
    // More details here: http://man7.org/linux/man-pages/man7/packet.7.html
    // We could use SOCK_RAW or SOCK_DGRAM for second argument
    // SOCK_RAW - raw packets pass from the kernel
    // SOCK_DGRAM - some amount of processing 
    // Third argument manage ether type of captured packets
    int packet_socket = socket(AF_PACKET, SOCK_RAW, htons(ETH_P_ALL));
   
    if (packet_socket == -1) {
        printf("Can't create AF_PACKET socket\n");
        return -1;
    }

    int interface_number = get_interface_number_by_device_name(packet_socket, interface_name);

    if (interface_number == -1) {
        printf("Can't get interface number by interface name\n");
        return -1;
    }
 
    // Switch to PROMISC mode
    struct packet_mreq sock_params;
    memset(&sock_params, 0, sizeof(sock_params));
    sock_params.mr_type = PACKET_MR_PROMISC;
    sock_params.mr_ifindex = interface_number;
    
    int set_promisc = setsockopt(packet_socket, SOL_PACKET, PACKET_ADD_MEMBERSHIP, (void *)&sock_params, sizeof(sock_params));
    if (set_promisc == -1) {
        printf("Can't enable promisc mode\n");
        return -1;
    }

    struct sockaddr_ll bind_address;
    memset(&bind_address, 0, sizeof(bind_address));

    bind_address.sll_family = AF_PACKET;
    bind_address.sll_protocol = htons(ETH_P_ALL);
    bind_address.sll_ifindex = interface_number;

    
    int bind_result = bind(packet_socket, (struct sockaddr *)&bind_address, sizeof(bind_address));
    if (bind_result == -1) {
        printf("Can't bind to AF_PACKET socket\n");
        return -1;
    }
 
    return packet_socket;
}



void start_af_packet_capture(std::string interface_name, int fanout_group_id) {
    int packet_socket = setup_socket(interface_name, fanout_group_id); 

    if (packet_socket == -1) {
        printf("Can't create socket\n");
        return;
    }
    
    unsigned int capture_length = 1500;
    char buffer[capture_length];

    struct timeval start, now;
    gettimeofday(&start, NULL);
    long long diff = 0;
    
    while (true) {
        received_packets++;

        int readed_bytes = read(packet_socket, buffer, capture_length); 

        // printf("Got %d bytes from interface\n", readed_bytes);

        consume_pkt((u_char*)buffer, readed_bytes);
        

        if (received_packets % 1000000 == 0) {
            gettimeofday(&now, NULL);
            diff = tv_diff(&start, &now);
            printf("packets pps: %f\n",  1000000 * 1000000.0/diff);
            start = now;
        }

        if (readed_bytes < 0) {
            break;
        }
    }

} 

void get_af_packet_stats() {
// getsockopt PACKET_STATISTICS
}


int main() {

        start_af_packet_capture("enp0s3", 0);
}


