/* ipc_latency_bench.c
 * Measures same-host IPC round-trip latency between two processes,
 * simulating the "thin game-side stub <-> Rust core process" hop.
 * Evidence for docs/PROTECTION.md 9.5.1 (P6 v2: core as a standalone process).
 *
 * Channels:
 *   1. shared memory + busy-spin   (the floor, no syscalls in the hot path)
 *   2. unix socketpair, datagram   (blocking, 2 syscalls per hop)
 *   3. unix socketpair, stream
 *   4. pipe pair
 *
 * Message: 256 bytes (one tick state snapshot). 20000 samples + 2000 warmup.
 *
 * Build & run:   gcc -O2 -o ipc_bench ipc_latency_bench.c && ./ipc_bench
 * Reference output (Linux, 2 cores, 2026-11):
 *   shm + busy-spin        p50  0.43 us | p99  0.53 us | p999   3.15 us
 *   unix socket dgram      p50  9.50 us | p99 14.91 us | p999  21.18 us
 *   unix socket stream     p50  7.17 us | p99 13.42 us | p999  19.40 us
 *   pipe pair              p50  7.91 us | p99 12.77 us | p999  18.41 us
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdatomic.h>
#include <unistd.h>
#include <sched.h>
#include <time.h>
#include <sys/socket.h>
#include <sys/mman.h>
#include <sys/wait.h>

#define N 20000
#define WARM 2000
#define MSGSZ 256

static void die(const char *m){ perror(m); exit(1); }

static uint64_t now_ns(void){
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

static int cmp_u64(const void *a, const void *b){
    uint64_t x = *(const uint64_t*)a, y = *(const uint64_t*)b;
    return x < y ? -1 : (x > y ? 1 : 0);
}

static void report(const char *name, uint64_t *lat){
    qsort(lat, N, sizeof(uint64_t), cmp_u64);
    printf("%-24s p50 %7.2f us | p99 %7.2f us | p999 %8.2f us | max %8.2f us\n",
           name,
           lat[N/2]        / 1e3,
           lat[N*99/100]   / 1e3,
           lat[N*999/1000] / 1e3,
           lat[N-1]        / 1e3);
}

/* ---- shared memory + busy-spin ---- */
typedef struct {
    _Atomic uint32_t seq;
    char payload[MSGSZ];
} shmbox_t;

#if defined(__x86_64__) || defined(__i386__)
static inline void cpu_relax(void){ __builtin_ia32_pause(); }
#else
static inline void cpu_relax(void){ sched_yield(); }
#endif

static void bench_shm(void){
    shmbox_t *box = mmap(NULL, sizeof(shmbox_t), PROT_READ|PROT_WRITE,
                         MAP_SHARED|MAP_ANONYMOUS, -1, 0);
    if (box == MAP_FAILED) die("mmap");
    atomic_store(&box->seq, 0);

    uint64_t *lat = malloc(N * sizeof(uint64_t));
    char buf[MSGSZ];
    memset(buf, 0x11, MSGSZ);

    pid_t p = fork();
    if (p == 0){
        for (uint32_t i = 0; i < WARM + N; i++){
            uint32_t want = 2*i + 1;
            while (atomic_load_explicit(&box->seq, memory_order_acquire) != want)
                cpu_relax();
            atomic_store_explicit(&box->seq, 2*i + 2, memory_order_release);
        }
        _exit(0);
    }
    for (uint32_t i = 0; i < WARM + N; i++){
        uint64_t t0 = now_ns();
        memcpy(box->payload, buf, MSGSZ);
        atomic_store_explicit(&box->seq, 2*i + 1, memory_order_release);
        while (atomic_load_explicit(&box->seq, memory_order_acquire) != 2*i + 2)
            cpu_relax();
        if (i >= WARM) lat[i - WARM] = now_ns() - t0;
    }
    wait(NULL);
    report("shm + busy-spin", lat);
    munmap(box, sizeof(shmbox_t));
    free(lat);
}

/* ---- socketpair (dgram / stream) ---- */
static void bench_socketpair(int type, const char *name){
    int sv[2];
    if (socketpair(AF_UNIX, type, 0, sv)) die("socketpair");
    uint64_t *lat = malloc(N * sizeof(uint64_t));
    char buf[MSGSZ];
    memset(buf, 0xA5, MSGSZ);

    pid_t p = fork();
    if (p == 0){
        char rb[MSGSZ];
        for (int i = 0; i < WARM + N; i++){
            size_t got = 0; ssize_t r;
            while (got < MSGSZ){
                r = read(sv[1], rb + got, MSGSZ - got);
                if (r <= 0) _exit(2);
                got += (size_t)r;
            }
            if (write(sv[1], rb, MSGSZ) != MSGSZ) _exit(2);
        }
        _exit(0);
    }
    for (int i = 0; i < WARM + N; i++){
        uint64_t t0 = now_ns();
        if (write(sv[0], buf, MSGSZ) != MSGSZ) die("write");
        size_t got = 0; ssize_t r;
        while (got < MSGSZ){
            r = read(sv[0], buf + got, MSGSZ - got);
            if (r <= 0) die("read");
            got += (size_t)r;
        }
        if (i >= WARM) lat[i - WARM] = now_ns() - t0;
    }
    wait(NULL);
    report(name, lat);
    close(sv[0]); close(sv[1]); free(lat);
}

/* ---- pipe pair ---- */
static void bench_pipe(void){
    int a[2], b[2];
    if (pipe(a) || pipe(b)) die("pipe");
    uint64_t *lat = malloc(N * sizeof(uint64_t));
    char buf[MSGSZ];
    memset(buf, 0x5A, MSGSZ);

    pid_t p = fork();
    if (p == 0){
        char rb[MSGSZ];
        for (int i = 0; i < WARM + N; i++){
            size_t got = 0; ssize_t r;
            while (got < MSGSZ){
                r = read(a[0], rb + got, MSGSZ - got);
                if (r <= 0) _exit(2);
                got += (size_t)r;
            }
            if (write(b[1], rb, MSGSZ) != MSGSZ) _exit(2);
        }
        _exit(0);
    }
    for (int i = 0; i < WARM + N; i++){
        uint64_t t0 = now_ns();
        if (write(a[1], buf, MSGSZ) != MSGSZ) die("write");
        size_t got = 0; ssize_t r;
        while (got < MSGSZ){
            r = read(b[0], buf + got, MSGSZ - got);
            if (r <= 0) die("read");
            got += (size_t)r;
        }
        if (i >= WARM) lat[i - WARM] = now_ns() - t0;
    }
    wait(NULL);
    report("pipe pair", lat);
    close(a[0]); close(a[1]); close(b[0]); close(b[1]); free(lat);
}

int main(void){
    printf("IPC round-trip, 2 processes, %d-byte msg, %d samples (+%d warmup)\n\n",
           MSGSZ, N, WARM);
    bench_shm();
    bench_socketpair(SOCK_DGRAM,  "unix socket dgram");
    bench_socketpair(SOCK_STREAM, "unix socket stream");
    bench_pipe();
    return 0;
}
