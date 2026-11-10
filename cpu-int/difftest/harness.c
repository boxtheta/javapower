// Bare-metal differential-test driver (little-endian, 64-bit, real mode). See README.md.
// Built with clang --target=powerpc64le-linux-gnu -mcpu=power9 -ffreestanding -nostdlib.
typedef unsigned long u64;
typedef unsigned int u32;
typedef unsigned char u8;

#define CASES 0x01000000UL  // header page, then records
#define SLOT 0x00200000UL
#define WIN 0x002FFF00UL
#define WINSIZE 512
#define SCR 0x7000UL
#define DAR_IN 0x0000DA00DA00DA00UL
#define NSPR 16
#define MAXSLOT 32
#define MAXPATCH 12

struct Case {
    u64 flags, msr, entry, nslot;
    u32 slot[MAXSLOT];
    u64 gpr[32];
    u64 cr, xer, lr, ctr;
    u64 spr[NSPR];
    u64 npatch;
    u64 patch[MAXPATCH][2];
    u8 mem[WINSIZE];
    // outputs
    u64 o_vec, o_srr0, o_srr1, o_dar, o_dsisr, o_msr;
    u64 o_gpr[32];
    u64 o_cr, o_xer, o_lr, o_ctr;
    u64 o_spr[NSPR];
    u64 o_patch[MAXPATCH];
    u8 o_mem[WINSIZE];
    u64 pad[25];
};
_Static_assert(sizeof(struct Case) == 2560, "layout");
_Static_assert(__builtin_offsetof(struct Case, gpr) == 160, "layout");
_Static_assert(__builtin_offsetof(struct Case, cr) == 416, "layout");
_Static_assert(__builtin_offsetof(struct Case, mem) == 776, "layout");
_Static_assert(__builtin_offsetof(struct Case, o_vec) == 1288, "layout");
_Static_assert(__builtin_offsetof(struct Case, o_patch) == 1752, "layout");

void run_case(struct Case *c);
long hcall(long op, long a1, long a2, long a3, long a4, long a5, long a6, long a7);
void flush_tlb(void);
void sync_icache(void *p, long len);

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

#define MT(n, v) __asm__ volatile("mtspr " #n ", %0" ::"r"(v))
#define MF(n, v) __asm__ volatile("mfspr %0, " #n : "=r"(v))

// Order of Case.spr[]: AMR IAMR UAMOR PIDR FSCR TAR DSCR SPRG0 SPRG3 EBBRR BESCR PPR VRSAVE
// CTRL SPRG2 EBBHR.
static void set_sprs(const u64 *v) {
    MT(29, v[0]);
    MT(61, v[1]);
    MT(157, v[2]);
    MT(48, v[3]);
    MT(153, v[4]);
    MT(815, v[5]);
    MT(17, v[6]);
    MT(272, v[7]);
    MT(275, v[8]);
    MT(805, v[9]);
    MT(806, v[10]);
    MT(896, v[11]);
    MT(256, v[12]);
    MT(152, v[13]);
    MT(274, v[14]);
    MT(804, v[15]);
}

static void get_sprs(u64 *v) {
    MF(29, v[0]);
    MF(61, v[1]);
    MF(157, v[2]);
    MF(48, v[3]);
    MF(153, v[4]);
    MF(815, v[5]);
    MF(17, v[6]);
    MF(272, v[7]);
    MF(275, v[8]);
    MF(805, v[9]);
    MF(806, v[10]);
    MF(896, v[11]);
    MF(256, v[12]);
    MF(136, v[13]);
    MF(274, v[14]);
    MF(804, v[15]);
}

static void puts_vty(const char *s) {
    u64 w[2];
    while (*s) {
        int n = 0;
        u8 *b = (u8 *)w;
        w[0] = w[1] = 0;
        while (n < 16 && s[n]) n++;
        // H_PUT_TERM_CHAR takes the bytes packed big-endian into two registers.
        for (int i = 0; i < n; i++) b[(i & ~7) + 7 - (i & 7)] = (u8)s[i];
        hcall(0x58, 0x71000000, n, w[0], w[1], 0, 0, 0);
        hcall(0x58, 0x71000001, n, w[0], w[1], 0, 0, 0);
        s += n;
    }
}

int cmain(void) {
    // Radix process table at 4 MiB (one page, PIDs 0..255), GTSE so tlbie is usable.
    long rc = hcall(0x37c, 0x10 | 0x08 | 0x04 | 0x01, 0x400000, 0, 0, 0, 0, 0);
    if (rc != 0) {
        puts_vty("REGISTER_PROC_TBL FAILED\n");
        for (;;) {}
    }
    u64 n = *(volatile u64 *)CASES;
    u64 ail = 0;
    struct Case *cases = (struct Case *)(CASES + 4096);
    for (u64 k = 0; k < n; k++) {
        struct Case *c = &cases[k];
        u32 *slot = (u32 *)SLOT;
        for (int i = 0; i < 1024; i++) slot[i] = 0;
        for (u64 i = 0; i < c->nslot && i < MAXSLOT; i++) slot[i] = c->slot[i];
        sync_icache(slot, 4096);
        memcpy((void *)WIN, c->mem, WINSIZE);
        for (u64 i = 0; i < c->npatch && i < MAXPATCH; i++) *(volatile u64 *)c->patch[i][0] = c->patch[i][1];
        u64 want_ail = (c->flags >> 8) & 3;
        if (want_ail != ail) {
            // H_SET_MODE(mflags=AIL, resource=ADDR_TRANS_MODE)
            if (hcall(0x31c, want_ail, 3, 0, 0, 0, 0, 0) != 0) {
                puts_vty("H_SET_MODE AIL FAILED\n");
                for (;;) {}
            }
            ail = want_ail;
        }
        set_sprs(c->spr);
        MT(273, 0x5151515151515151UL);  // SPRG1 (the stubs' scratch register)
        MT(19, DAR_IN);
        MT(18, 0UL);
        MT(22, 0x7fffffffUL);  // DEC: far away
        flush_tlb();
        run_case(c);
        const u64 *scr = (const u64 *)SCR;
        c->o_vec = scr[0];
        c->o_srr0 = scr[1];
        c->o_srr1 = scr[2];
        c->o_dar = scr[3];
        c->o_dsisr = scr[4];
        c->o_msr = scr[5];
        for (int i = 0; i < 32; i++) c->o_gpr[i] = scr[6 + i];
        c->o_cr = scr[38];
        c->o_xer = scr[39];
        c->o_lr = scr[40];
        c->o_ctr = scr[41];
        get_sprs(c->o_spr);
        memcpy(c->o_mem, (void *)WIN, WINSIZE);
        for (u64 i = 0; i < c->npatch && i < MAXPATCH; i++) {
            c->o_patch[i] = *(volatile u64 *)c->patch[i][0];
            *(volatile u64 *)c->patch[i][0] = 0;
        }
    }
    puts_vty("\nDIFFTEST DONE\n");
    for (;;) {}
}
