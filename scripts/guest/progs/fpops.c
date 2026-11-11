/* Floating point: arithmetic, conversions, libm, rounding modes and exception flags. */
#include <fenv.h>
#include <float.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

static uint64_t s = 0x2545F4914F6CDD1Dull;
static uint64_t rnd(void) {
    s ^= s << 13;
    s ^= s >> 7;
    s ^= s << 17;
    return s;
}

static double pickd(void) {
    static const double edge[] = {0.0, -0.0, 1.0, -1.0, 0.5, 3.0, 1e308, -1e308, 1e-308, 4.9e-324,
                                  INFINITY, -INFINITY, NAN, 2.5, -2.5, 0x1p52, 0x1p63, 0x1p31};
    uint64_t r = rnd();
    if ((r & 3) == 0) return edge[(r >> 2) % 18];
    if ((r & 3) == 1) {
        uint64_t b = rnd();
        double d;
        memcpy(&d, &b, 8);
        return d;
    }
    return ((int64_t)rnd() >> (r % 50)) / (double)(1 + (rnd() % 1000));
}

static uint64_t h = 1469598103934665603ull;
static void mixb(const void *p, int n) {
    const unsigned char *c = p;
    for (int i = 0; i < n; i++) {
        h ^= c[i];
        h *= 1099511628211ull;
    }
}
static void mixd(double d) {
    if (isnan(d)) d = NAN; /* NaN payloads/signs are not specified by C: canonicalise */
    mixb(&d, 8);
}
static void mixf(float f) {
    if (isnan(f)) f = NAN;
    mixb(&f, 4);
}
static void mixi(int64_t v) { mixb(&v, 8); }

int main(void) {
    static const int modes[] = {FE_TONEAREST, FE_TOWARDZERO, FE_UPWARD, FE_DOWNWARD};
    static const char *names[] = {"nearest", "zero", "up", "down"};
    for (int m = 0; m < 4; m++) {
        fesetround(modes[m]);
        h = 1469598103934665603ull;
        int flags = 0;
        for (int k = 0; k < 20000; k++) {
            volatile double a = pickd(), b = pickd(), c = pickd();
            feclearexcept(FE_ALL_EXCEPT);
            mixd(a + b); mixd(a - b); mixd(a * b); mixd(a / b); mixd(fma(a, b, c)); mixd(sqrt(fabs(a)));
            mixf((float)a + (float)b); mixf((float)a * (float)b); mixf((float)a / (float)b); mixf(sqrtf(fabsf((float)a)));
            mixd(rint(a)); mixd(nearbyint(b)); mixd(floor(a)); mixd(ceil(b)); mixd(trunc(a)); mixd(round(b));
            mixf((float)a);
            if (fabs(a) < 0x1p62) { mixi((int64_t)a); mixi(llrint(a)); }
            if (fabs(a) < 0x1p30) { mixi((int32_t)a); mixi(lrint(a)); }
            mixd((double)(int64_t)rnd()); mixd((double)rnd()); mixf((float)(int32_t)rnd());
            mixd(copysign(a, b)); mixd(fmin(a, b)); mixd(fmax(a, b)); mixi(a < b); mixi(a == b); mixi(isunordered(a, b));
            flags |= fetestexcept(FE_ALL_EXCEPT);
            mixi(fetestexcept(FE_ALL_EXCEPT));
        }
        printf("arith %-7s %016llx flags %x\n", names[m], (unsigned long long)h, flags);
    }
    fesetround(FE_TONEAREST);
    h = 1469598103934665603ull;
    for (int k = 0; k < 4000; k++) {
        double x = (int64_t)(rnd() % 2000000 - 1000000) / 1000.0;
        mixd(sin(x)); mixd(cos(x)); mixd(tan(x)); mixd(atan2(x, 3.0)); mixd(exp(x / 100)); mixd(log(fabs(x) + 1));
        mixd(pow(fabs(x), 0.37)); mixd(cbrt(x)); mixd(hypot(x, 7.5)); mixd(sinh(x / 300)); mixd(log10(fabs(x) + 1));
        mixf(sinf((float)x)); mixf(expf((float)x / 100)); mixf(logf(fabsf((float)x) + 1));
        int e;
        mixd(frexp(x, &e)); mixi(e); mixd(ldexp(x, 13)); mixd(fmod(x, 3.7)); mixd(remainder(x, 2.3));
    }
    printf("libm %016llx\n", (unsigned long long)h);
    printf("%.17g %.17g %a %a\n", M_PI, exp(1.0), sqrt(2.0), (double)FLT_MAX);
    printf("%.9g %g %e %f\n", (float)M_PI, 1e-310, 123456.789e10, -0.0);
    volatile double z = 0.0;
    feclearexcept(FE_ALL_EXCEPT);
    volatile double r = 1.0 / z;
    printf("1/0=%g divbyzero=%d\n", r, fetestexcept(FE_DIVBYZERO) != 0);
    feclearexcept(FE_ALL_EXCEPT);
    r = z / z;
    printf("0/0 isnan=%d invalid=%d\n", isnan(r), fetestexcept(FE_INVALID) != 0);
    feclearexcept(FE_ALL_EXCEPT);
    r = DBL_MIN / 3.0;
    printf("underflow=%d inexact=%d %a\n", fetestexcept(FE_UNDERFLOW) != 0, fetestexcept(FE_INEXACT) != 0, r);
    long double ld = 1.0L / 3.0L;
    printf("sizeof(long double)=%zu %.20Lg\n", sizeof(long double), ld);
    return 0;
}
