/* String and memory functions over every alignment, plus formatted I/O and parsing. */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static uint64_t s = 0xD1B54A32D192ED03ull;
static uint64_t rnd(void) {
    s ^= s << 13;
    s ^= s >> 7;
    s ^= s << 17;
    return s;
}

static uint64_t h = 1469598103934665603ull;
static void mixb(const void *p, size_t n) {
    const unsigned char *c = p;
    for (size_t i = 0; i < n; i++) {
        h ^= c[i];
        h *= 1099511628211ull;
    }
}
static void mixi(long long v) { mixb(&v, 8); }
static int sgn(int v) { return (v > 0) - (v < 0); }

static int cmp(const void *a, const void *b) {
    uint32_t x = *(const uint32_t *)a, y = *(const uint32_t *)b;
    return (x > y) - (x < y);
}

int main(void) {
    static char src[512], dst[512], buf[512];
    for (int k = 0; k < 512; k++) src[k] = (char)('a' + k % 26);
    for (int so = 0; so < 16; so++) {
        for (int doff = 0; doff < 16; doff++) {
            for (size_t n = 0; n < 80; n += 1 + n / 8) {
                memset(dst, '#', sizeof dst);
                memcpy(dst + doff, src + so, n);
                mixb(dst, 128);
                memmove(dst + doff + 3, dst + doff, n);
                mixb(dst, 128);
                memmove(dst + doff, dst + doff + 5, n);
                mixb(dst, 128);
                dst[doff + n] = 0;
                mixi((long long)strlen(dst + doff));
                mixi(sgn(memcmp(dst + doff, src + so, n)));
                mixi(sgn(strncmp(dst + doff, src + so + 1, n)));
                char *p = strchr(dst + doff, 'q');
                mixi(p ? p - dst : -1);
                p = strrchr(dst + doff, 'e');
                mixi(p ? p - dst : -1);
                p = memchr(dst + doff, 'z', n);
                mixi(p ? p - dst : -1);
                p = strstr(dst + doff, "xyz");
                mixi(p ? p - dst : -1);
                memset(dst + doff, (int)n, n);
                mixb(dst, 128);
            }
        }
    }
    printf("mem/str %016llx\n", (unsigned long long)h);

    h = 1469598103934665603ull;
    static uint32_t arr[5000];
    for (int k = 0; k < 5000; k++) arr[k] = (uint32_t)rnd();
    qsort(arr, 5000, 4, cmp);
    mixb(arr, sizeof arr);
    printf("qsort %016llx first %08x last %08x\n", (unsigned long long)h, arr[0], arr[4999]);

    h = 1469598103934665603ull;
    for (int k = 0; k < 3000; k++) {
        long long v = (long long)rnd() >> (rnd() % 64);
        double d = (double)(long long)rnd() / (double)(1 + rnd() % 100000);
        int n = snprintf(buf, sizeof buf, "%lld|%llx|%o|%.10g|%e|%10.3f|%-8s|%c|%5.2s", v, (unsigned long long)v,
                         (unsigned)v, d, d, d, "ab", (int)('A' + k % 26), "xyz");
        mixb(buf, (size_t)n);
        char *end;
        mixi(strtoll(buf, &end, 10));
        mixi(end - buf);
        double back = strtod(buf + n - 38 > buf ? strchr(buf, '|') + 1 : buf, &end);
        mixb(&back, 8);
        mixi((long long)strtoul("0x7fffABCD", NULL, 16));
        mixi(atoi("-12345"));
    }
    printf("printf/strto %016llx\n", (unsigned long long)h);
    snprintf(buf, sizeof buf, "%.30f %lld %llu", 0.1, -9223372036854775807LL - 1, 18446744073709551615ULL);
    puts(buf);
    char tok[] = "alpha, beta;gamma ,,delta";
    for (char *sp, *t = strtok_r(tok, " ,;", &sp); t; t = strtok_r(NULL, " ,;", &sp)) printf("[%s]", t);
    printf("\n");
    return 0;
}
