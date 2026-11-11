/*
 * Testing only: touch the scratch device of the CLI's --test-mmio option from a FreeBSD guest
 * through /dev/mem, with ordinary loads and stores of every size. The emulator prints each access
 * the device sees on stderr; this prints what the guest sees.
 *   mmiotest [base]     (default 0x100000000000)
 */
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <unistd.h>

int
main(int argc, char **argv)
{
	off_t base = argc > 1 ? (off_t)strtoull(argv[1], NULL, 0) : (off_t)0x100000000000ULL;
	int fd = open("/dev/mem", O_RDWR);
	if (fd < 0) {
		perror("open /dev/mem");
		return 1;
	}
	volatile uint8_t *p = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, fd, base);
	if (p == MAP_FAILED) {
		perror("mmap");
		return 1;
	}
	printf("mmiotest: id %08x\n", *(volatile uint32_t *)p);
	*(volatile uint8_t *)(p + 0x10) = 0xa5;
	*(volatile uint16_t *)(p + 0x12) = 0xbeef;
	*(volatile uint32_t *)(p + 0x14) = 0x11223344;
	*(volatile uint64_t *)(p + 0x18) = 0x0102030405060708ULL;
	*(volatile uint32_t *)p = 0;	/* the id ignores stores */
	uint8_t b = *(volatile uint8_t *)(p + 0x10);
	uint16_t h = *(volatile uint16_t *)(p + 0x12);
	uint32_t w = *(volatile uint32_t *)(p + 0x14);
	uint64_t d = *(volatile uint64_t *)(p + 0x18);
	printf("mmiotest: u8 %02x u16 %04x u32 %08x u64 %016llx\n", b, h, w, (unsigned long long)d);
	printf("mmiotest: bytes at 0x14: %02x %02x %02x %02x\n", p[0x14], p[0x15], p[0x16], p[0x17]);
	printf("mmiotest: id %08x\n", *(volatile uint32_t *)p);
	int ok = b == 0xa5 && h == 0xbeef && w == 0x11223344 && d == 0x0102030405060708ULL;
	printf("mmiotest: %s\n", ok ? "ok" : "MISMATCH");
	return ok ? 0 : 1;
}
