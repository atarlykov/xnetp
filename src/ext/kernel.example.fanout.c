#include <stddef.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>

#include <sys/types.h>
#include <sys/wait.h>
#include <sys/socket.h>
#include <sys/ioctl.h>

#include <unistd.h>

#include <linux/if_ether.h>
#include <linux/if_packet.h>

#include <net/if.h>

static const char *device_name;
static int fanout_type;
static int fanout_id;

#ifndef PACKET_FANOUT
# define PACKET_FANOUT			18
# define PACKET_FANOUT_HASH		0
# define PACKET_FANOUT_LB		1
#endif

static int setup_socket(void)
{
	int err, fd = socket(AF_PACKET, SOCK_RAW, htons(ETH_P_IP));
	struct sockaddr_ll ll;
	struct ifreq ifr;
	int fanout_arg;

	if (fd < 0) {
		perror("socket");
		return EXIT_FAILURE;
	}

	memset(&ifr, 0, sizeof(ifr));
	strcpy(ifr.ifr_name, device_name);
	err = ioctl(fd, SIOCGIFINDEX, &ifr);
	if (err < 0) {
		perror("SIOCGIFINDEX");
		return EXIT_FAILURE;
	}

	memset(&ll, 0, sizeof(ll));
	ll.sll_family = AF_PACKET;
	ll.sll_ifindex = ifr.ifr_ifindex;
	err = bind(fd, (struct sockaddr *) &ll, sizeof(ll));
	if (err < 0) {
		perror("bind");
		return EXIT_FAILURE;
	}

	fanout_arg = (fanout_id | (fanout_type << 16));
	err = setsockopt(fd, SOL_PACKET, PACKET_FANOUT,
			 &fanout_arg, sizeof(fanout_arg));
	if (err) {
		perror("setsockopt");
		return EXIT_FAILURE;
	}

	return fd;
}

static void fanout_thread(void)
{
	int fd = setup_socket();
	int limit = 10000;

	if (fd < 0)
		exit(fd);

	while (limit-- > 0) {
		char buf[1600];
		int err;

		err = read(fd, buf, sizeof(buf));
		if (err < 0) {
			perror("read");
			exit(EXIT_FAILURE);
		}
		if ((limit % 10) == 0)
			fprintf(stdout, "(%d) \n", getpid());
	}

	fprintf(stdout, "%d: Received 10000 packets\n", getpid());

	close(fd);
	exit(0);
}

int main(int argc, char **argp)
{
	int fd, err;
	int i;

	if (argc != 3) {
		fprintf(stderr, "Usage: %s INTERFACE {hash|lb}\n", argp[0]);
		return EXIT_FAILURE;
	}

	if (!strcmp(argp[2], "hash"))
		fanout_type = PACKET_FANOUT_HASH;
	else if (!strcmp(argp[2], "lb"))
		fanout_type = PACKET_FANOUT_LB;
	else {
		fprintf(stderr, "Unknown fanout type [%s]\n", argp[2]);
		exit(EXIT_FAILURE);
	}

	device_name = argp[1];
	fanout_id = getpid() & 0xffff;

	for (i = 0; i < 4; i++) {
		pid_t pid = fork();

		switch (pid) {
		case 0:
			fanout_thread();

		case -1:
			perror("fork");
			exit(EXIT_FAILURE);
		}
	}

	for (i = 0; i < 4; i++) {
		int status;

		wait(&status);
	}

	return 0;
}
