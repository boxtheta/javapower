/* Integer operations on 32- and 64-bit types: results folded into checksums. */
#include <stdint.h>
#include <stdio.h>

static uint64_t s = 0x9E3779B97F4A7C15ull;
static uint64_t rnd(void) {
    s ^= s << 13;
    s ^= s >> 7;
    s ^= s << 17;
    return s;
}

static uint64_t h = 1469598103934665603ull;
static void mix(uint64_t v) {
    for (int i = 0; i < 8; i++) {
        h ^= (v >> (8 * i)) & 0xff;
        h *= 1099511628211ull;
    }
}

static uint64_t pick(void) {
    static const uint64_t edge[] = {0, 1, 2, 0x7f, 0x80, 0xff, 0x7fff, 0x8000, 0xffff, 0x7fffffff,
                                    0x80000000, 0xffffffff, 0x100000000ull, 0x7fffffffffffffffull,
                                    0x8000000000000000ull, 0xffffffffffffffffull};
    uint64_t r = rnd();
    if ((r & 7) == 0) return edge[(r >> 3) % 16];
    if ((r & 7) == 1) return r >> (r % 64);
    return rnd();
}

int main(void) {
    for (int sec = 0; sec < 8; sec++) {
        for (int k = 0; k < 20000; k++) {
            uint64_t a = pick(), b = pick();
            uint32_t a32 = (uint32_t)a, b32 = (uint32_t)b;
            int32_t sa32 = (int32_t)a32, sb32 = (int32_t)b32;
            int64_t sa = (int64_t)a, sb = (int64_t)b;
            switch (sec) {
            case 0:
                mix(a + b); mix(a - b); mix(a * b); mix(a32 * b32); mix((uint64_t)(sa32 * (int64_t)sb32));
                mix(a ^ b); mix(a & ~b); mix(a | b);
                break;
            case 1:
                if (b) { mix(a / b); mix(a % b); }
                if (b32) { mix(a32 / b32); mix(a32 % b32); }
                if (sb && !(sa == INT64_MIN && sb == -1)) { mix((uint64_t)(sa / sb)); mix((uint64_t)(sa % sb)); }
                if (sb32 && !(sa32 == INT32_MIN && sb32 == -1)) { mix((uint64_t)(int64_t)(sa32 / sb32)); mix((uint64_t)(int64_t)(sa32 % sb32)); }
                break;
            case 2: {
                unsigned sh = b % 64, sh32 = b % 32;
                mix(a << sh); mix(a >> sh); mix((uint64_t)(sa >> sh));
                mix(a32 << sh32); mix(a32 >> sh32); mix((uint64_t)(int64_t)(sa32 >> sh32));
                mix((a << sh) | (a >> ((64 - sh) & 63))); mix((a32 << sh32) | (a32 >> ((32 - sh32) & 31)));
                break;
            }
            case 3:
                mix(a ? (uint64_t)__builtin_clzll(a) : 99); mix(a ? (uint64_t)__builtin_ctzll(a) : 99);
                mix((uint64_t)__builtin_popcountll(a)); mix(a32 ? (uint64_t)__builtin_clz(a32) : 99);
                mix(__builtin_bswap64(a)); mix(__builtin_bswap32(a32)); mix(__builtin_bswap16((uint16_t)a));
                mix((uint64_t)__builtin_parityll(a));
                break;
            case 4: {
                uint64_t r;
                uint32_t r32;
                int64_t sr;
                mix(__builtin_add_overflow(a, b, &r)); mix(r);
                mix(__builtin_sub_overflow(sa, sb, &sr)); mix((uint64_t)sr);
                mix(__builtin_mul_overflow(a32, b32, &r32)); mix(r32);
                mix(__builtin_mul_overflow(sa, sb, &sr)); mix((uint64_t)sr);
                break;
            }
            case 5:
                mix(a < b); mix(sa < sb); mix(a32 < b32); mix(sa32 < sb32); mix(a == b);
                mix(sa > 0 ? sa : -sa); mix(sa32 > sb32 ? (uint64_t)sa32 : (uint64_t)sb32);
                mix((uint64_t)(int8_t)a); mix((uint64_t)(int16_t)a); mix((uint64_t)(int32_t)a);
                break;
            case 6: {
#ifdef __SIZEOF_INT128__
                unsigned __int128 p = (unsigned __int128)a * b;
                mix((uint64_t)(p >> 64)); mix((uint64_t)p);
                __int128 sp = (__int128)sa * sb;
                mix((uint64_t)((unsigned __int128)sp >> 64));
#else
                /* 32-bit: 64x64 products through 32-bit halves. */
                uint64_t lo = (a & 0xffffffff) * (b & 0xffffffff), m1 = (a >> 32) * (b & 0xffffffff);
                uint64_t m2 = (a & 0xffffffff) * (b >> 32), hi = (a >> 32) * (b >> 32);
                uint64_t mid = (lo >> 32) + (m1 & 0xffffffff) + (m2 & 0xffffffff);
                mix(hi + (m1 >> 32) + (m2 >> 32) + (mid >> 32)); mix(a * b);
                mix((uint64_t)(sa * sb));
#endif
                break;
            }
            default: {
                volatile uint8_t buf[32];
                for (int i = 0; i < 32; i++) buf[i] = (uint8_t)(a >> (i % 8 * 8)) ^ (uint8_t)i;
                unsigned off = b % 24;
                uint64_t v;
                __builtin_memcpy(&v, (const void *)(buf + off), 8);
                mix(v);
                uint32_t w;
                __builtin_memcpy(&w, (const void *)(buf + (off ^ 3)), 4);
                mix(w);
                break;
            }
            }
        }
        printf("section %d: %016llx\n", sec, (unsigned long long)h);
    }
    printf("sizeof(long)=%zu sizeof(void*)=%zu\n", sizeof(long), sizeof(void *));
    return 0;
}
