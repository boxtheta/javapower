/* Processes and system calls: fork, wait status, pipes, exec (of this binary and of a native
 * 64-bit one), mmap/mprotect, file I/O with lseek/pread, getrusage-free deterministic output. */
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc > 1 && strcmp(argv[1], "child") == 0) {
        printf("exec'd child: argc %d last '%s' env JPVAR=%s\n", argc, argv[argc - 1], getenv("JPVAR"));
        return 7;
    }
    int fd[2];
    pipe(fd);
    pid_t p = fork();
    if (p == 0) {
        close(fd[0]);
        char buf[4096];
        for (int i = 0; i < 64; i++) {
            memset(buf, 'A' + i % 26, sizeof buf);
            write(fd[1], buf, sizeof buf);
        }
        _exit(3);
    }
    close(fd[1]);
    long total = 0, sum = 0;
    char buf[1000];
    ssize_t n;
    while ((n = read(fd[0], buf, sizeof buf)) > 0) {
        total += n;
        for (ssize_t i = 0; i < n; i++) sum += buf[i];
    }
    int st;
    waitpid(p, &st, 0);
    printf("pipe: %ld bytes sum %ld; child exited %d status %d\n", total, sum, WIFEXITED(st), WEXITSTATUS(st));

    p = fork();
    if (p == 0) {
        setenv("JPVAR", "hello", 1);
        execl(argv[0], argv[0], "child", "x", "y", (char *)NULL);
        _exit(99);
    }
    waitpid(p, &st, 0);
    printf("exec self: status %d\n", WEXITSTATUS(st));
    fflush(stdout);

    p = fork();
    if (p == 0) {
        execl("/bin/echo", "echo", "native echo from exec", (char *)NULL);
        _exit(99);
    }
    waitpid(p, &st, 0);
    printf("exec /bin/echo: status %d\n", WEXITSTATUS(st));

    p = fork();
    if (p == 0) {
        raise(SIGABRT);
        _exit(0);
    }
    waitpid(p, &st, 0);
    printf("child signalled %d sig %d\n", WIFSIGNALED(st), WTERMSIG(st));

    size_t len = 1 << 20;
    unsigned char *m = mmap(NULL, len, PROT_READ | PROT_WRITE, MAP_ANON | MAP_PRIVATE, -1, 0);
    for (size_t i = 0; i < len; i += 4096) m[i] = (unsigned char)(i >> 12);
    mprotect(m, len, PROT_READ);
    unsigned long acc = 0;
    for (size_t i = 0; i < len; i += 4096) acc += m[i];
    munmap(m, len);
    printf("mmap 1MiB: %lu\n", acc);

    char path[] = "/tmp/jpXXXXXX";
    int f = mkstemp(path);
    for (int i = 0; i < 100; i++) {
        char line[64];
        int k = snprintf(line, sizeof line, "line %03d %08x\n", i, (unsigned)(i * 2654435761u));
        write(f, line, (size_t)k);
    }
    struct stat sb;
    fstat(f, &sb);
    char rb[32];
    pread(f, rb, 17, 17 * 50);
    rb[17] = 0;
    lseek(f, 0, SEEK_SET);
    ftruncate(f, 170);
    fstat(f, &sb);
    close(f);
    unlink(path);
    printf("file: size after truncate %lld, line 50 = %s", (long long)sb.st_size, rb);
    errno = 0;
    printf("open missing: %d errno %d\n", open("/nonexistent/x", O_RDONLY), errno);
    return 0;
}
