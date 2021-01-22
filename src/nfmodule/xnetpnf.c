#include <linux/module.h>    // included for all kernel modules
#include <linux/kernel.h>    // included for KERN_INFO
#include <linux/init.h>      // included for __init and __exit macros
#include <linux/netfilter.h>
#include <linux/netfilter_ipv6.h>

#include <linux/ipv6.h>
#include <linux/vmalloc.h>

#include <linux/proc_fs.h>
#include <linux/uaccess.h>
#include <linux/percpu-refcount.h>
#include <linux/ktime.h>

MODULE_AUTHOR( "AUTHOR");
MODULE_DESCRIPTION( "DESCRIPTION");
MODULE_VERSION( "VERSION");
MODULE_LICENSE("GPL"); 


// uncomment to enable debug output
//#define __XNETP_DEBUG__

// value of nexthdr field in ipv6 packets
// for xnetp specific packets
#define XNETP_PROTOCOL_TYPE         253

// size of xnetp ext header in bytes
#define XNETP_EXT_HDR_LENGTH         32

// min and max enumerated types of xnetp packet,
// used to check valid types, be careful
#define XNETP_PKT_TYPE_LOWER          1
#define XNETP_PKT_TYPE_UPPER          3

/*
 * correct lengths of xnetp packets by packet type,
 * includes: ip fixed header + xnetp ext header + data.
 * size MUST be (XNETP_PKT_TYPE_UPPER + 1) as types start from 1
 */
__u8 XNETP_PACKETS_LEN[] = {0, 40+32, 40+48, 40+96};


/*
    // win7        mac: 74:D0:2B:C4:73:48                                                    fe:80:00:00:00:00:00:00:05:b3:cf:a5:22:bb:7a:6b
    // vbox        mac: 08:00:27:4e:9c:62                                                    fe:80:00:00:00:00:00:00:0a:00:27:ff:fe:4e:9c:62
    //skb->len              - ip packet length
    //skb->data_len         - 0
    //skb->transport_header - 64
    //skb->network_header   - 24
    //skb->mac_header       - 65535
    //skb->data points to ip packet (24)

    //72 0 0 0 64 24 65535 :0A:69:0D:E9:58:CE:6A:91 :66:01:00:00:06:00:00:00 :20:04:06:00:01:00:00:00 :60:0B:88:B2:00:20:FD:40  | :FE:80:00:00:00:00:00:00:0A:00:27:FF:FE:4E:9C:62:FE:80:00:00:00:00:00:00:05:B3:CF:A5:22:BB:7A:6B:FF:20:00:00:FC:00:00:00

 */
/*
 *  called to initialize the module
    NF_IP_PRE_ROUNTING  — This hook is called when a packet arrives into the machine.
    NF_IP_LOCAL_IN      — This hook is called when a packet is destined to the machine itself.
    NF_IP_FORWARD       — This hook is called when a packet is destined to another interface.
    NF_IP_POST_ROUTING  — Is called when a packet is on its way back to the wire and outside the machine.
    NF_IP_LOCAL_OUT     — When a packet is created locally, and is destined out, this hook is called.

    enum {
        NFPROTO_UNSPEC =  0,
        NFPROTO_INET   =  1,
        NFPROTO_IPV4   =  2,
        NFPROTO_ARP    =  3,
        NFPROTO_NETDEV =  5,
        NFPROTO_BRIDGE =  7,
        NFPROTO_IPV6   = 10,
        NFPROTO_DECNET = 12,
        NFPROTO_NUMPROTO,
    };

    enum nf_ip_hook_priorities {
        NF_IP_PRI_FIRST = INT_MIN,
        NF_IP_PRI_RAW_BEFORE_DEFRAG = -450,
        NF_IP_PRI_CONNTRACK_DEFRAG = -400,
        NF_IP_PRI_RAW = -300,
        NF_IP_PRI_SELINUX_FIRST = -225,
        NF_IP_PRI_CONNTRACK = -200,
        NF_IP_PRI_MANGLE = -150,
        NF_IP_PRI_NAT_DST = -100,
        NF_IP_PRI_FILTER = 0,
        NF_IP_PRI_SECURITY = 50,
        NF_IP_PRI_NAT_SRC = 100,
        NF_IP_PRI_SELINUX_LAST = 225,
        NF_IP_PRI_CONNTRACK_HELPER = 300,
        NF_IP_PRI_CONNTRACK_CONFIRM = INT_MAX,
        NF_IP_PRI_LAST = INT_MAX,
    };
 */



/*
 * xnetp ext header structure (from specification):
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
 */
struct xnetp_exthdr {
    __u8            nexthdr;
    __u8            hdrlen;
    __u8            reltype;
    __u8            varreltype;
    struct in6_addr uni;
    __u8            cmptype;
    __u8            cmptypeattr;
    __u16           lci;
    __u64           reserved;
};


/*
 container for hook function registration
 */
static struct nf_hook_ops nfho_in;



/*
 * container for packets' processing counters
 * @see linux/latest/source/include/linux/percpu-defs.h and
 * https://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git/tree/include/linux/genhd.h
 * for examples and support functions
 */
struct pkt_counters {
    __u64   unknown;        // total processes
    __u64   xnetp;          // that have xnetp nexthdr
    __u64   xnetp_err_type; // incorrect type in ext header
    __u64   xnetp_err_len;  // incorrect length
};

/*
 * per cpu counters for publishing via proc fs
 */
static DEFINE_PER_CPU_ALIGNED(struct pkt_counters, counters);

/*
 * this simplifies access to various fields of counters
 */
#define pkt_counters_inc(variable, type, name)              \
({                                                          \
    struct pkt_counters *__tmp = &get_cpu_var(variable);    \
    __tmp->name++;                                          \
    put_cpu_var(variable);                                  \
})


/*
 * main packets' interception function,
 * registered with standard netfilter parameters
 */
unsigned int hook_func_in(
    unsigned int hooknum,
    struct sk_buff *skb,
    const struct net_device *in,
    const struct net_device *out,
    int (*okfn)(struct sk_buff *))
{

#ifdef __XNETP_DEBUG__
    // debug: dump packet information
    char __tmp[1024];
    int __i;
    for (__i = 0; __i < 72; __i++) {
        sprintf(&tmp[3*__i], ":%02X", skb->head[__i]);
    }
    printk("%d %d %d %d %ld %d %d %d %s\n",
        skb->len, skb->data_len, skb->mac_len, skb->hdr_len, skb->data - skb->head,
        skb->transport_header, skb->network_header,	skb->mac_header, tmp);
#endif


    // we are sure we have IPv6 packet here,
    // as we've requested NFPROTO_IPV6 during hook registration
    struct ipv6hdr *ip6_hdr;
    ip6_hdr = (struct ipv6hdr*)(skb->data);


    // check packet type and let netfilter proceed with not xnetp packets
    if ((__u8)ip6_hdr->nexthdr != (__u8)XNETP_PROTOCOL_TYPE) {
        pkt_counters_inc(counters, __u64, unknown);
        return NF_ACCEPT;
    }

    // ok, this is xnetp packet, count it
    pkt_counters_inc(counters, __u64, xnetp);


    // check minimal allowed length of xnetp packets: ipv6:40 + xnetp:32
    if (skb->len < sizeof(struct ipv6hdr) + XNETP_EXT_HDR_LENGTH) {
        pkt_counters_inc(counters, __u64, xnetp_err_len);
        return NF_DROP;
    }

    // get pointer to xnetp specific ext header
    struct xnetp_exthdr *ext_hdr = (struct xnetp_exthdr*) &(skb->data[sizeof(struct ipv6hdr)]);
    __u8 xnetp_type = ext_hdr->cmptype;

    // check xnetp packet type
    if ((xnetp_type < XNETP_PKT_TYPE_LOWER) || (XNETP_PKT_TYPE_UPPER < xnetp_type)) {
        pkt_counters_inc(counters, __u64, xnetp_err_type);
        return NF_DROP;
    }

    // check packet length that should be correlated with xnetp type
    if (skb->len != XNETP_PACKETS_LEN[xnetp_type]) {
        // incorrect packet size
        pkt_counters_inc(counters, __u64, xnetp_err_len);
        return NF_DROP;
    }

    // ok, this is correct xnetp packet
 	return NF_ACCEPT;
 }



/* ---------- [/PROC FS INTEGRATION] ------------------------------ */

// tracks reference to the registered proc entry
static struct proc_dir_entry    *proc;
// semaphore to allow only sequential accesses to statistics
static atomic_t                 proc_access = ATOMIC_INIT(1);
// buffer to render statistics
static char                     proc_data[2048];
// position in proc_data to support
// small buffers on client side
static int                      proc_data_offset;

// metric performance testing
static u64                      proc_perf_time;


/*
 * called by a client during open file request to our proc fs statistics
 */
static int proc_open(struct inode *inode, struct file *file)
{
    int cpu;
    int cpus_with_data;

    // lower semaphore if available
    if (atomic_cmpxchg(&proc_access, 1, 0) != 1) {
        return -16; // EBUSY
    }

    // track real handler cpus
    cpus_with_data = 0;

    proc_data_offset = 0;
    proc_data_offset += sprintf(&proc_data[proc_data_offset], " cpu       unknown         xnetp         etype          elen\n");
    for_each_present_cpu(cpu) {
        struct pkt_counters *c = &per_cpu(counters, cpu);
        if ((c->unknown != 0) || (c->xnetp != 0) || (c->xnetp_err_type != 0) || (c->xnetp_err_len != 0)) {
            cpus_with_data++;
            proc_data_offset += sprintf(&proc_data[proc_data_offset], "%4d%14lld%14lld%14lld%14lld\n",
                cpu, c->unknown, c->xnetp, c->xnetp_err_type, c->xnetp_err_len);
        }
    }

    // provide some data if there were no packets
    if (cpus_with_data == 0) {
        proc_data_offset += sprintf(&proc_data[proc_data_offset], " all%14d%14d%14d%14d\n", 0, 0, 0, 0);
    }

    // performance counter
    proc_data_offset += sprintf(&proc_data[proc_data_offset], " perf: %llu\n", proc_perf_time);

    return 0;
}

/*
 * called when a client process closes the file (proc fs statistics)
 */
static int proc_release(struct inode *inode, struct file *file)
{
    // restore semaphore to available subsequent operations
    atomic_set(&proc_access, 1);
    return 0;
}

/*
 * separate function to allow binary code optimizations
 */
static void proc_write_perf_cycle(struct sk_buff *skb) {
    int i;
    for (i = 0; i < 1000000; i++) {
        hook_func_in(0, skb, NULL, NULL, NULL);
        hook_func_in(0, skb, NULL, NULL, NULL);
        hook_func_in(0, skb, NULL, NULL, NULL);
        hook_func_in(0, skb, NULL, NULL, NULL);
        hook_func_in(0, skb, NULL, NULL, NULL);
        hook_func_in(0, skb, NULL, NULL, NULL);
        hook_func_in(0, skb, NULL, NULL, NULL);
        hook_func_in(0, skb, NULL, NULL, NULL);
        hook_func_in(0, skb, NULL, NULL, NULL);
        hook_func_in(0, skb, NULL, NULL, NULL);
    }
}

/*
 * called on write requests via proc fs,
 * now used to run performance/timing test,
 * this is not the best way for sure, but ...
 */
static ssize_t proc_write(
    struct file *file,
    const char __user *ubuf,
    size_t ubuf_size,
    loff_t *ppos)
{
    // allocate storage space for fake sk_buff data,
    // skb will point inside it
    char data[512];
    // fake sk buffer
    struct sk_buff skb;

    // build correct xnetp packet inside data array
    skb.data = data;

    // ip nexthdr field
    struct ipv6hdr *ip6_hdr = (struct ipv6hdr*)(skb.data);
    ip6_hdr->nexthdr = (__u8)XNETP_PROTOCOL_TYPE;
    // packet length
    skb.len = sizeof(struct ipv6hdr) + XNETP_EXT_HDR_LENGTH;
    // xnetp packet type
    struct xnetp_exthdr *ext_hdr = (struct xnetp_exthdr*) &(skb.data[sizeof(struct ipv6hdr)]);
    ext_hdr->cmptype = (__u8)1;


    u64 i;
    // warm up processor and code
    proc_write_perf_cycle(&skb);
    proc_write_perf_cycle(&skb);
    proc_write_perf_cycle(&skb);
    proc_write_perf_cycle(&skb);
    proc_write_perf_cycle(&skb);

    // start timer and run the test
    u64 time_s = ktime_get_ns();
    proc_write_perf_cycle(&skb);
    u64 time_e = ktime_get_ns();

    // assert r == NF_ACCEPT

    proc_perf_time = time_e - time_s;

    // return error, can't really write to proc fs
	return -1;
}

/*
 * read function, called by client to read content via proc fs
 */
static ssize_t proc_read(
    struct file     *file,
    char __user     *ubuf,          // user space buffer
    size_t          ubuf_size,      // buffer size
    loff_t          *ppos)          // start position in our data client is interested in
{
    // calculate min(data available, buffer size)
    int data_to_send = proc_data_offset - *ppos;
    if (ubuf_size < data_to_send) {
        data_to_send = ubuf_size;
    }

    if ( copy_to_user(ubuf, &proc_data[*ppos], data_to_send)) {
        return -EFAULT;
    }
    *ppos += data_to_send;

    return data_to_send;
}

/*
 * proc fs operations to provide statistics
 */
static struct file_operations proc_ops =
{
	.owner      = THIS_MODULE,
	.open       = proc_open,
    .release    = proc_release,
	.read       = proc_read,
	.write      = proc_write
};


/*
 * initializes the module
 */
static int __init pf_module_init(void)
{
    // reset counters
    int cpu;
    for_each_present_cpu(cpu) {
        memset(&per_cpu(counters, cpu), 0, sizeof(struct pkt_counters));
    }

    // register /proc interface
    proc = proc_create("xnetp", 0, NULL, &proc_ops);

    // register netfilter hook
	nfho_in.hook = (nf_hookfn*) hook_func_in;
 	nfho_in.hooknum = NF_INET_LOCAL_IN;
 	nfho_in.pf = NFPROTO_IPV6;
 	nfho_in.priority = NF_IP6_PRI_FIRST;
 	nf_register_net_hook(&init_net, &nfho_in);

 	printk("xnetp packet filter registered\n");
	return 0;
} 

/*
 * removes the module
 */
static void __exit pf_module_exit(void)
{
 	// unregister netfilter
	nf_unregister_net_hook(&init_net, &nfho_in);

    // remove /proc interface
    proc_remove(proc);

 	printk("xnetp packet filter removed\n");
}


/*
 * register module entry points
 */
module_init( pf_module_init);
module_exit( pf_module_exit);

