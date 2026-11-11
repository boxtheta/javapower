/* SHA-256 and CRC-32 over generated data, and a byte-order-sensitive struct dump. */
#include <stdint.h>
#include <stdio.h>
#include <string.h>

static const uint32_t K[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98,
    0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786,
    0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8,
    0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819,
    0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
    0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7,
    0xc67178f2};

#define ROR(x, n) (((x) >> (n)) | ((x) << (32 - (n))))

static void block(uint32_t *st, const uint8_t *p) {
    uint32_t w[64];
    for (int i = 0; i < 16; i++) w[i] = (uint32_t)p[4 * i] << 24 | p[4 * i + 1] << 16 | p[4 * i + 2] << 8 | p[4 * i + 3];
    for (int i = 16; i < 64; i++) {
        uint32_t s0 = ROR(w[i - 15], 7) ^ ROR(w[i - 15], 18) ^ (w[i - 15] >> 3);
        uint32_t s1 = ROR(w[i - 2], 17) ^ ROR(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    uint32_t a = st[0], b = st[1], c = st[2], d = st[3], e = st[4], f = st[5], g = st[6], h = st[7];
    for (int i = 0; i < 64; i++) {
        uint32_t t1 = h + (ROR(e, 6) ^ ROR(e, 11) ^ ROR(e, 25)) + ((e & f) ^ (~e & g)) + K[i] + w[i];
        uint32_t t2 = (ROR(a, 2) ^ ROR(a, 13) ^ ROR(a, 22)) + ((a & b) ^ (a & c) ^ (b & c));
        h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
    }
    st[0] += a; st[1] += b; st[2] += c; st[3] += d; st[4] += e; st[5] += f; st[6] += g; st[7] += h;
}

static void sha256(const uint8_t *m, size_t n, uint8_t out[32]) {
    uint32_t st[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
    size_t i = 0;
    for (; i + 64 <= n; i += 64) block(st, m + i);
    uint8_t tail[128] = {0};
    size_t r = n - i;
    memcpy(tail, m + i, r);
    tail[r] = 0x80;
    size_t tl = r < 56 ? 64 : 128;
    uint64_t bits = (uint64_t)n * 8;
    for (int k = 0; k < 8; k++) tail[tl - 1 - k] = (uint8_t)(bits >> (8 * k));
    for (size_t k = 0; k < tl; k += 64) block(st, tail + k);
    for (int k = 0; k < 8; k++) {
        out[4 * k] = (uint8_t)(st[k] >> 24); out[4 * k + 1] = (uint8_t)(st[k] >> 16);
        out[4 * k + 2] = (uint8_t)(st[k] >> 8); out[4 * k + 3] = (uint8_t)st[k];
    }
}

static uint32_t crc32(const uint8_t *p, size_t n) {
    uint32_t c = 0xffffffff;
    for (size_t i = 0; i < n; i++) {
        c ^= p[i];
        for (int k = 0; k < 8; k++) c = (c >> 1) ^ (0xEDB88320 & -(c & 1));
    }
    return ~c;
}

static uint8_t data[3 << 20];

int main(void) {
    uint8_t d[32];
    sha256((const uint8_t *)"abc", 3, d);
    printf("sha256(abc) = ");
    for (int i = 0; i < 32; i++) printf("%02x", d[i]);
    printf("\n");
    uint64_t s = 88172645463325252ull;
    for (size_t i = 0; i < sizeof data; i++) {
        s ^= s << 13; s ^= s >> 7; s ^= s << 17;
        data[i] = (uint8_t)(s >> 29);
    }
    for (size_t n = 1; n <= sizeof data; n *= 7) {
        sha256(data, n, d);
        printf("n=%-8zu crc %08x sha ", n, crc32(data, n));
        for (int i = 0; i < 8; i++) printf("%02x", d[i]);
        printf("\n");
    }
    struct { uint8_t a; uint16_t b; uint32_t c; uint64_t e; double f; } st;
    memset(&st, 0, sizeof st); /* padding too */
    st.a = 1; st.b = 0x0203; st.c = 0x04050607; st.e = 0x08090a0b0c0d0e0full; st.f = 1.0;
    const uint8_t *b = (const uint8_t *)&st;
    printf("struct bytes (%zu):", sizeof st);
    for (size_t i = 0; i < sizeof st; i++) printf(" %02x", b[i]);
    printf("\n");
    return 0;
}
