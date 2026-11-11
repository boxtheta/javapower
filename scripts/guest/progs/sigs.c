/* Signals: raise, blocked and pending signals, alternate stacks, faults caught with siglongjmp,
 * siginfo contents, and setjmp/longjmp preserving callee-saved integer and FP registers. */
#include <setjmp.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

static volatile sig_atomic_t got[64];
static sigjmp_buf jb;
static volatile int fault_sig, fault_code;
static volatile uintptr_t fault_addr;
static char *altstack;
static volatile int on_alt;

static void h_count(int sig) { got[sig]++; }

static void h_alt(int sig) {
    char local;
    on_alt = (&local >= altstack && &local < altstack + SIGSTKSZ * 4);
    got[sig]++;
}

static void h_fault(int sig, siginfo_t *si, void *uc) {
    (void)uc;
    fault_sig = sig;
    fault_code = si->si_code;
    fault_addr = (uintptr_t)si->si_addr;
    siglongjmp(jb, 1);
}

static jmp_buf j2;
static int depth(int n) {
    if (n == 0) longjmp(j2, 42);
    volatile int pad[16];
    for (int i = 0; i < 16; i++) pad[i] = n + i;
    return depth(n - 1) + pad[3];
}

__attribute__((noinline)) static double fpwork(double x) {
    register double a = x * 1.5, b = x * 2.5, c = x * 3.5;
    if (setjmp(j2) == 0) depth(20);
    return a + b + c;
}

int main(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = h_count;
    sigaction(SIGUSR1, &sa, NULL);
    sigaction(SIGUSR2, &sa, NULL);
    for (int i = 0; i < 1000; i++) raise(SIGUSR1);
    printf("usr1 %d\n", (int)got[SIGUSR1]);

    sigset_t set, old;
    sigemptyset(&set);
    sigaddset(&set, SIGUSR2);
    sigprocmask(SIG_BLOCK, &set, &old);
    raise(SIGUSR2);
    raise(SIGUSR2);
    sigset_t pend;
    sigpending(&pend);
    printf("usr2 blocked: delivered %d pending %d\n", (int)got[SIGUSR2], sigismember(&pend, SIGUSR2));
    sigprocmask(SIG_SETMASK, &old, NULL);
    printf("usr2 unblocked: delivered %d\n", (int)got[SIGUSR2]);

    altstack = malloc(SIGSTKSZ * 4);
    stack_t ss = {.ss_sp = altstack, .ss_size = SIGSTKSZ * 4, .ss_flags = 0};
    sigaltstack(&ss, NULL);
    sa.sa_handler = h_alt;
    sa.sa_flags = SA_ONSTACK;
    sigaction(SIGHUP, &sa, NULL);
    raise(SIGHUP);
    printf("altstack: delivered %d on_alt %d\n", (int)got[SIGHUP], (int)on_alt);

    memset(&sa, 0, sizeof sa);
    sa.sa_sigaction = h_fault;
    sa.sa_flags = SA_SIGINFO;
    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGBUS, &sa, NULL);
    sigaction(SIGILL, &sa, NULL);
    sigaction(SIGTRAP, &sa, NULL);
    sigaction(SIGFPE, &sa, NULL);

    volatile int *bad = (volatile int *)(uintptr_t)0x10;
    if (sigsetjmp(jb, 1) == 0) {
        *bad = 1;
        printf("no fault?\n");
    }
    printf("null store: sig %d code %d addr %lx\n", fault_sig, fault_code, (unsigned long)fault_addr);

    char *ro = mmap(NULL, 4096, PROT_READ, MAP_ANON | MAP_PRIVATE, -1, 0);
    if (sigsetjmp(jb, 1) == 0) {
        ro[100] = 1;
        printf("no fault?\n");
    }
    printf("ro store: sig %d code %d offset %ld\n", fault_sig, fault_code, (long)(fault_addr - (uintptr_t)ro));

    if (sigsetjmp(jb, 1) == 0) {
        __asm__ volatile(".long 0");
        printf("no fault?\n");
    }
    printf("illegal: sig %d code %d\n", fault_sig, fault_code);

    if (sigsetjmp(jb, 1) == 0) {
        __asm__ volatile("trap");
        printf("no fault?\n");
    }
    printf("trap: sig %d code %d\n", fault_sig, fault_code);

    if (sigsetjmp(jb, 1) == 0) {
        __asm__ volatile("mfmsr 3" ::: "r3");
        printf("no fault?\n");
    }
    printf("privileged: sig %d code %d\n", fault_sig, fault_code);

    /* Signal mask restored by siglongjmp: SIGUSR1 blocked inside, unblocked after. */
    sigemptyset(&set);
    sigaddset(&set, SIGUSR1);
    if (sigsetjmp(jb, 1) == 0) {
        sigprocmask(SIG_BLOCK, &set, NULL);
        *bad = 2;
    }
    sigprocmask(SIG_SETMASK, NULL, &old);
    printf("mask restored: usr1 blocked %d\n", sigismember(&old, SIGUSR1));

    double r = fpwork(2.0);
    printf("setjmp fp: %g\n", r);
    volatile int iv = 7;
    if (setjmp(j2) == 0) {
        iv = 8;
        longjmp(j2, 0);
    }
    printf("longjmp(0) -> 1, volatile %d\n", iv);
    int k = 0;
    for (int i = 0; i < 2000; i++) {
        if (setjmp(j2) == 0) depth(i % 7);
        else k++;
    }
    printf("nested longjmps %d\n", k);

    printf("kill self: ");
    fflush(stdout);
    sa.sa_handler = h_count;
    sa.sa_flags = 0;
    sigaction(SIGINFO, &sa, NULL);
    kill(getpid(), SIGINFO);
    printf("%d\n", (int)got[SIGINFO]);
    return 0;
}
