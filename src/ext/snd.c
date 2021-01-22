
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

/* converts str in the form [<ipv4>|<ipv6>|<hostname>]:port to struct sockaddr_storage.
 * Returns < 0 with err set in case of error.
 */
int addr_to_ss(char *str, struct sockaddr_storage *ss, struct errmsg *err)
{
	char *range;

	/* look for the addr/port delimiter, it's the last colon. */
	if ((range = strrchr(str, ':')) == NULL) {
		err->len = snprintf(err->msg, err->size, "Missing port number: '%s'\n", str);
		return -1;
	}	    

	*range++ = 0;

	memset(ss, 0, sizeof(*ss));

	if (strrchr(str, ':') != NULL) {
		/* IPv6 address contains ':' */
		ss->ss_family = AF_INET6;
		((struct sockaddr_in6 *)ss)->sin6_port = htons(atoi(range));

		if (!inet_pton(ss->ss_family, str, &((struct sockaddr_in6 *)ss)->sin6_addr)) {
			err->len = snprintf(err->msg, err->size, "Invalid server address: '%s'\n", str);
			return -1;
		}
	}
	else {
		ss->ss_family = AF_INET;
		((struct sockaddr_in *)ss)->sin_port = htons(atoi(range));

		if (*str == '*' || *str == '\0') { /* INADDR_ANY */
			((struct sockaddr_in *)ss)->sin_addr.s_addr = INADDR_ANY;
			return 0;
		}

		if (!inet_pton(ss->ss_family, str, &((struct sockaddr_in *)ss)->sin_addr)) {
			struct hostent *he = gethostbyname(str);

			if (he == NULL) {
				err->len = snprintf(err->msg, err->size, "Invalid server name: '%s'\n", str);
				return -1;
			}
			((struct sockaddr_in *)ss)->sin_addr = *(struct in_addr *) *(he->h_addr_list);
		}
	}

	return 0;
}

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

void flood(int fd, struct sockaddr *to, int tolen)
{
	struct timeval start, now;
	unsigned long long pkt;
	unsigned long long totbit = 0;
	long long diff = 0;
	int len;
        unsigned long long p_count = 0;
        int last_errno = 0;

	gettimeofday(&start, NULL);
	for (pkt = 0; pkt < (unsigned long long)count; pkt++) {
		len = mtu;
		if (sendto(fd, payload, len, /* MSG_DONTWAIT*/ 0, to, tolen) >= 0) {
			//totbit += (len + 28) * 8;
			p_count++;
                } else {
                        last_errno = errno;
                }
	}
	gettimeofday(&now, NULL);
	diff = tv_diff(&start, &now);
	printf("tried: %llu packets sent in %lld us, success count: %lld,  pps: %f,  errno:%d\n", pkt, diff, p_count, pkt*1000000.0/diff, last_errno);
	printf(" real: %llu packets sent, pps: %f\n", p_count, p_count*1000000.0/diff);
}


#define PACK_SIZE 10
struct mmsghdr msgh[PACK_SIZE];
struct iovec msg;

void floodmmsg(int fd, struct sockaddr *to, int tolen)
{
	struct timeval start, now;
	unsigned long long pkt;
	long long diff = 0;
        unsigned long long p_count = 0;
        int last_errno = 0;

        struct sockaddr_in sin;

        printf("flooding with mtu: %d   pack: %d\n", mtu, PACK_SIZE);

        memset(&sin, 0, sizeof(sin));
        sin = *((struct sockaddr_in*)to);
        
	memset(&msg, 0, sizeof(msg));
	msg.iov_base = "data";
	msg.iov_len = mtu;
	
	memset(msgh, 0, sizeof(msgh));
	for (int i = 0; i < PACK_SIZE; i++) {
		msgh[i].msg_hdr.msg_iov = &msg;
		msgh[i].msg_hdr.msg_iovlen = 1;
	
	        msgh[i].msg_hdr.msg_name = &sin;
	        msgh[i].msg_hdr.msg_namelen = sizeof(sin);

		// reset before send
		msgh[i].msg_len = 0;
	}

	gettimeofday(&start, NULL);
	for (pkt = 0; pkt < (unsigned long long)count; pkt++) {

		// reset sent_len before send
		for (int i = 0; i < PACK_SIZE; i++) {
			msgh[i].msg_len = 0;
		}
		int retval = sendmmsg(fd, msgh, PACK_SIZE, 0);

		if (retval >= 0) {
			/*
			for (int i = 0; i < 1024; i++) {
				if (msgh[i].msg_len >= 28) {
					p_count ++;
				}
			}
			*/
			p_count += retval;
                } else {
                        last_errno = errno;
			printf("errno: %d\n", last_errno);
			exit(1);
                }
	}
	gettimeofday(&now, NULL);
	diff = tv_diff(&start, &now);
	printf("tried: %llu batches sent in %lld us, success messages: %lld, errno:%d\n", pkt, diff, p_count, last_errno);
	printf(" real: %llu packets sent, pps: %f\n", p_count, p_count*1000000.0/diff);
}


int main(int argc, char **argv)
{
	int fd;
	struct sockaddr_storage ss;
	struct errmsg err;
	char *prog = *argv;

	err.len = 0;
	err.size = 100;
	err.msg = malloc(err.size);

	--argc; ++argv;

	while (argc && **argv == '-') {
		if (strcmp(*argv, "-l") == 0) {
			address = *++argv;
			argc--;
		}
		else if (strcmp(*argv, "-m") == 0) {
			mtu = atol(*++argv);
			argc--;
		}
		else if (strcmp(*argv, "-n") == 0) {
			count = atol(*++argv);
			argc--;
		}
		else
			break;
		argc--;
		argv++;
	}

	if (argc > 0 || !*address || mtu < 28) {
		fprintf(stderr,
			"usage: %s [ -l address ] [ -m mtu ] [ -n count ]\n"
			"Note: mtu must be >= 28\n", prog);
		exit(1);
	}

	payload = malloc(mtu);
	if (!payload) {
		perror("malloc");
		exit(1);
	}

	memset(payload, 'A', mtu);

	if (addr_to_ss(address, &ss, &err) < 0) {
		fprintf(stderr, "%s\n", err.msg);
		exit(1);
	}

	if ((fd = socket(ss.ss_family, SOCK_DGRAM, 0)) == -1) {
		perror("socket");
		exit(1);
	}
	
	if (setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, (char *) &one, sizeof(one)) == -1) {
		perror("setsockopt(SO_REUSEADDR)");
		exit(1);
	}

	// flood(fd, (struct sockaddr *)&ss, ss.ss_family == AF_INET6 ? sizeof(struct sockaddr_in6) : sizeof(struct sockaddr_in));

	floodmmsg(fd, (struct sockaddr *)&ss, ss.ss_family == AF_INET6 ? sizeof(struct sockaddr_in6) : sizeof(struct sockaddr_in));

	return 0;
}
