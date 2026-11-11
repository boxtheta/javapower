/* Threads: atomics, mutexes, condition variables, TLS, once, barriers, thread-directed signals. */
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define NT 4
#define ITER 20000

static atomic_long acount;
static atomic_llong a64;
static long mcount;
static pthread_mutex_t mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t cv = PTHREAD_COND_INITIALIZER;
static int turn;
static __thread long tls = 5;
static pthread_once_t once = PTHREAD_ONCE_INIT;
static int once_runs;
static pthread_barrier_t bar;
static volatile sig_atomic_t usr1_hits;

static void once_fn(void) { once_runs++; }
static void h_usr1(int s) { (void)s; usr1_hits++; }

static void *worker(void *arg) {
    long id = (long)arg;
    pthread_once(&once, once_fn);
    tls += id;
    for (int i = 0; i < ITER; i++) {
        atomic_fetch_add(&acount, 1);
        atomic_fetch_add(&a64, 0x100000001ll);
        long expect = atomic_load(&acount);
        while (!atomic_compare_exchange_weak(&acount, &expect, expect + 1)) {}
        atomic_fetch_sub(&acount, 1);
        pthread_mutex_lock(&mu);
        mcount += 3;
        pthread_mutex_unlock(&mu);
    }
    pthread_barrier_wait(&bar);
    /* Round-robin hand-off through a condition variable. */
    for (int r = 0; r < 200; r++) {
        pthread_mutex_lock(&mu);
        while (turn % NT != id) pthread_cond_wait(&cv, &mu);
        turn++;
        pthread_cond_broadcast(&cv);
        pthread_mutex_unlock(&mu);
    }
    return (void *)(tls * 10);
}

static void *small(void *arg) {
    tls += (long)arg;
    for (int i = 0; i < 1000; i++) atomic_fetch_add(&acount, 1);
    return (void *)(tls * 3);
}

int main(void) {
    pthread_t t[NT];
    signal(SIGUSR1, h_usr1);
    pthread_barrier_init(&bar, NULL, NT);
    for (long i = 0; i < NT; i++) pthread_create(&t[i], NULL, worker, (void *)i);
    long sum = 0;
    for (int i = 0; i < NT; i++) {
        void *r;
        pthread_join(t[i], &r);
        sum += (long)r;
    }
    printf("atomic %ld mutex %ld a64 %llx turns %d once %d tls-sum %ld main-tls %ld\n", (long)acount, mcount,
           (long long)a64, turn, once_runs, sum, tls);
    pthread_t self = pthread_self();
    pthread_kill(self, SIGUSR1);
    printf("pthread_kill self: %d\n", (int)usr1_hits);
    /* Many short-lived threads. */
    long total = 0;
    for (int k = 0; k < 50; k++) {
        pthread_create(&t[0], NULL, small, (void *)(long)k);
        void *r;
        pthread_join(t[0], &r);
        total += (long)r;
    }
    printf("short-lived: %ld atomic %ld\n", total, (long)acount);
    return 0;
}
