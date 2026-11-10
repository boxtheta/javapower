// Guest-side driver for the differential tests. Reads fixed-size Case records from stdin, runs
// each instruction through run_case (harness.S) and writes the resulting Case records to stdout.
// Built with: clang --target=powerpc64le-linux-gnu -mcpu=power9 -nostdlib -static -fuse-ld=lld
typedef unsigned long u64;
typedef unsigned int u32;
typedef unsigned char u8;

#define MEMBASE 0x30000000UL
#define MEMSIZE 512
#define SLOT 0x31000000UL

struct Case {
    u32 insn, flags;
    u64 gpr[32];
    u64 fpscr;
    u32 cr, sig;
    u64 vscr[2];
    u64 vsr[64][2];
    u8 mem[MEMSIZE];
    u64 fpscr_in;
    u64 signip;
    u64 pad;
};
_Static_assert(sizeof(struct Case) == 1856, "layout");
_Static_assert(__builtin_offsetof(struct Case, vsr) == 296, "layout");
_Static_assert(__builtin_offsetof(struct Case, mem) == 1320, "layout");

long sys6(long nr, long a, long b, long c, long d, long e, long f);
void run_case(struct Case *in, struct Case *out, void *save, void *slot);
void sighandler(int, void *, void *);

void *memcpy(void *d, const void *s, unsigned long n) {
    u8 *dd = d;
    const u8 *ss = s;
    while (n--) *dd++ = *ss++;
    return d;
}
void *memset(void *d, int c, unsigned long n) {
    u8 *dd = d;
    while (n--) *dd++ = (u8)c;
    return d;
}

static u64 save[128];
static struct Case in, out;

static long rd(void *p, long n) {
    long got = 0;
    while (got < n) {
        long r = sys6(3, 0, (long)p + got, n - got, 0, 0, 0);
        if (r <= 0) return got;
        got += r;
    }
    return got;
}
static void wr(const void *p, long n) {
    long done = 0;
    while (done < n) {
        long r = sys6(4, 1, (long)p + done, n - done, 0, 0, 0);
        if (r <= 0) sys6(234, 3, 0, 0, 0, 0, 0);
        done += r;
    }
}

struct ksigaction { u64 handler, flags, restorer, mask; };

int cmain(void) {
    if (sys6(90, MEMBASE, 0x10000, 3, 0x32, -1, 0) != (long)MEMBASE) sys6(234, 1, 0, 0, 0, 0, 0);
    if (sys6(90, SLOT, 0x10000, 7, 0x32, -1, 0) != (long)SLOT) sys6(234, 2, 0, 0, 0, 0, 0);
    struct ksigaction sa = {(u64)sighandler, 4 /* SA_SIGINFO */ | 0x40000000 /* SA_NODEFER */, 0, 0};
    for (int s = 4; s <= 11; s++) {  // SIGILL SIGTRAP SIGABRT SIGBUS SIGFPE ... SIGSEGV
        if (s == 9) continue;
        sys6(173, s, (long)&sa, 0, 8, 0, 0);
    }
    volatile u32 *slot = (u32 *)SLOT;
    while (rd(&in, sizeof in) == sizeof in) {
        slot[0] = in.insn;
        slot[1] = 0x4e800020;  // blr
        __asm__ volatile("dcbst 0,%0; sync; icbi 0,%0; isync" ::"r"(slot) : "memory");
        memcpy((void *)MEMBASE, in.mem, MEMSIZE);
        memset(&out, 0, sizeof out);
        out.insn = in.insn;
        out.flags = in.flags;
        run_case(&in, &out, save, (void *)SLOT);
        memcpy(out.mem, (void *)MEMBASE, MEMSIZE);
        wr(&out, sizeof out);
    }
    return 0;
}
