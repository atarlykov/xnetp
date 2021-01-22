

#define _GNU_SOURCE


#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <string.h>
#include <ctype.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/udp.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <stdarg.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <time.h>

#if defined(__dietlibc__)
#include <strings.h>
#endif

struct errmsg {
	char *msg;
	int size;
	int len;
};

const int zero = 0;
const int one = 1;

static int mtu = 1500;
static int count = 1;
static char *address = "";
char *payload;


char buffer[2048];


int main(int argc, char **argv)
{
	int fd;
	char *prog = *argv;

	
	memset(buffer, 0, sizeof(buffer));


	if ((fd = socket(AF_INET6, SOCK_RAW, 253)) == -1) {
		perror("socket");
		exit(1);
	}


	struct sockaddr_in6 sin6;
	socklen_t sin6len = sizeof(sin6);
	memset(&sin6, 0, sizeof(sin6));
	// printf("sizeof sin6: %ld\n", sizeof(sin6));


	int received = recvfrom(fd, buffer, sizeof(buffer), 0, &sin6, &sin6len);
	if ( received > 0) {
		printf("ok. received: %d    sin6len: %d\n", received, sin6len);
	} else {
		printf("fail\n");
	}


	//char* destination = "fe80::5b3:cfa5:22bb:7a6b";  // windows7
	//char* destination = "fe80::a00:27ff:fe4e:9c62";  // virtualbox
	//char* destination = "::1";
	char 		addr[256];
	socklen_t   addrlen = sizeof(addr);

	inet_ntop(AF_INET6, &(sin6.sin6_addr), addr, addrlen);
	printf("%s\n", addr);

	return 0;
}
