

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




int main(int argc, char **argv)
{
	int fd;
	struct sockaddr_storage ss;
	struct errmsg err;
	char *prog = *argv;

	err.len = 0;
	err.size = 100;
	err.msg = malloc(err.size);


	mtu = 104;

	payload = malloc(mtu);
	if (!payload) {
		perror("malloc");
		exit(1);
	}

	memset(payload, 'A', mtu);


	if ((fd = socket(AF_INET6, SOCK_RAW, 253)) == -1) {
		perror("socket");
		exit(1);
	}



	memset(&ss, 0, sizeof(ss));
	ss.ss_family = AF_INET6;
	//(struct sockaddr_in6)ss.sin6_port = htons(atoi(4321));

	//char* destination = "fe80::5b3:cfa5:22bb:7a6b";  // windows7
	//char* destination = "fe80::a00:27ff:fe4e:9c62";  // virtualbox
	char* destination = "::1";


	if (!inet_pton(AF_INET6, destination, &((struct sockaddr_in6*)&ss)->sin6_addr)) {
		perror("Invalid server address\n");
		return -1;
	}


	if (sendto(fd, payload, mtu, 0, (struct sockaddr_in6*)&ss, sizeof(struct sockaddr_in6)) >= 0) {
		printf("ok\n");
	} else {
		printf("fail\n");
	}



	return 0;
}
