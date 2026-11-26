/* JNI helpers for the userland probe. Everything risky (ptrace, unshare, fork) runs in a forked
 * child that only calls async-signal-safe functions and _exit, and is always reaped or killed by
 * the parent with a deadline. Results are plain strings carrying the raw errno, never a guess. */
#define _GNU_SOURCE
#include <jni.h>
#include <stdarg.h>
#include <errno.h>
#include <fcntl.h>
#include <sched.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static volatile char g_canary[16] = "PROBE-CANARY-1";

static long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

static jstring out(JNIEnv *env, const char *fmt, ...) __attribute__((format(printf, 2, 3)));
static jstring out(JNIEnv *env, const char *fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    return (*env)->NewStringUTF(env, buf);
}

/* Reap a child with a deadline; on timeout kill it. Returns 1 if it exited/signalled by itself. */
static int reap(pid_t pid, int *status, long deadline_ms) {
    while (now_ms() < deadline_ms) {
        pid_t r = waitpid(pid, status, WNOHANG);
        if (r == pid) return 1;
        if (r < 0 && errno != EINTR) return 0;
        usleep(2000);
    }
    kill(pid, SIGKILL);
    waitpid(pid, status, 0);
    return 0;
}

JNIEXPORT jstring JNICALL Java_dev_fonebrew_probe_Native_ptraceProbe(JNIEnv *env, jclass c) {
    (void)c;
    pid_t pid = fork();
    if (pid < 0) return out(env, "FAIL fork errno=%d (%s)", errno, strerror(errno));
    if (pid == 0) {
        if (ptrace(PTRACE_TRACEME, 0, 0, 0) != 0) _exit(100 + (errno & 0x3f));
        raise(SIGSTOP);
        for (int i = 0; i < 8; i++) syscall(SYS_getppid);
        _exit(0);
    }
    long deadline = now_ms() + 5000;
    int st = 0;
    /* first stop: the child's own SIGSTOP */
    pid_t r;
    do { r = waitpid(pid, &st, WNOHANG); if (r == 0) usleep(2000); } while (r == 0 && now_ms() < deadline);
    if (r != pid) { kill(pid, SIGKILL); waitpid(pid, &st, 0); return out(env, "FAIL no first stop within 5s"); }
    if (WIFEXITED(st)) return out(env, "FAIL PTRACE_TRACEME rejected in child (exit %d => errno %d)", WEXITSTATUS(st), WEXITSTATUS(st) - 100);
    if (WIFSIGNALED(st)) return out(env, "FAIL child killed by signal %d before first stop (SIGSYS=31 suggests seccomp)", WTERMSIG(st));
    /* process_vm_readv: read the parent's canary at the same address in the stopped child */
    char got[16] = {0};
    struct iovec local = { got, sizeof got }, remote = { (void *)g_canary, sizeof got };
    ssize_t n = process_vm_readv(pid, &local, 1, &remote, 1, 0);
    int pvr_errno = errno;
    int pvr_ok = (n == (ssize_t)sizeof got) && memcmp(got, (const void *)g_canary, 14) == 0;
    int stops = 0, sys_errno = 0;
    for (int i = 0; i < 64; i++) {
        if (ptrace(PTRACE_SYSCALL, pid, 0, 0) != 0) { sys_errno = errno; break; }
        long left = deadline - now_ms();
        if (left <= 0) break;
        pid_t w;
        do { w = waitpid(pid, &st, WNOHANG); if (w == 0) usleep(1000); } while (w == 0 && now_ms() < deadline);
        if (w != pid) break;
        if (WIFEXITED(st) || WIFSIGNALED(st)) { goto done; }
        stops++;
    }
    kill(pid, SIGKILL);
    waitpid(pid, &st, 0);
done:
    if (stops == 0)
        return out(env, "FAIL PTRACE_SYSCALL produced no stops (errno=%d %s); process_vm_readv %s (errno=%d)",
                   sys_errno, strerror(sys_errno), pvr_ok ? "ok" : "failed", pvr_errno);
    if (pvr_ok)
        return out(env, "PASS traceme=ok syscall_stops=%d process_vm_readv=ok", stops);
    return out(env, "FAIL traceme=ok syscall_stops=%d process_vm_readv failed n=%zd errno=%d (%s)",
               stops, n, pvr_errno, strerror(pvr_errno));
}

JNIEXPORT jstring JNICALL Java_dev_fonebrew_probe_Native_pvrErrno(JNIEnv *env, jclass c) {
    (void)c;
    /* Self-read sanity: process_vm_readv on our own pid, so a FAIL above can be told apart from
     * the syscall being blocked outright. */
    char got[16] = {0};
    struct iovec local = { got, sizeof got }, remote = { (void *)g_canary, sizeof got };
    ssize_t n = process_vm_readv(getpid(), &local, 1, &remote, 1, 0);
    return out(env, "self process_vm_readv n=%zd errno=%d (%s)", n, n < 0 ? errno : 0, n < 0 ? strerror(errno) : "ok");
}

JNIEXPORT jstring JNICALL Java_dev_fonebrew_probe_Native_unshareUserNs(JNIEnv *env, jclass c) {
    (void)c;
    pid_t pid = fork();
    if (pid < 0) return out(env, "FAIL fork errno=%d (%s)", errno, strerror(errno));
    if (pid == 0) {
        if (unshare(CLONE_NEWUSER) == 0) _exit(0);
        _exit(100 + (errno & 0x3f));
    }
    int st = 0;
    if (!reap(pid, &st, now_ms() + 3000)) return out(env, "UNKNOWN child did not finish in 3s");
    if (WIFEXITED(st) && WEXITSTATUS(st) == 0) return out(env, "PASS unshare(CLONE_NEWUSER) succeeded in a forked child");
    if (WIFEXITED(st)) return out(env, "FAIL unshare(CLONE_NEWUSER) errno=%d (%s)", WEXITSTATUS(st) - 100, strerror(WEXITSTATUS(st) - 100));
    return out(env, "FAIL child killed by signal %d (SIGSYS=31 suggests seccomp)", WTERMSIG(st));
}

/* Check (iv): map a file PROT_READ|PROT_EXEC. This is what a ptrace-based loader / dlopen needs. */
JNIEXPORT jstring JNICALL Java_dev_fonebrew_probe_Native_mmapExec(JNIEnv *env, jclass c, jstring jpath) {
    (void)c;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    int e = errno;
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (fd < 0) return out(env, "UNKNOWN open failed errno=%d (%s)", e, strerror(e));
    struct stat sb;
    if (fstat(fd, &sb) != 0 || sb.st_size == 0) { close(fd); return out(env, "UNKNOWN fstat/empty"); }
    void *p = mmap(NULL, (size_t)sb.st_size, PROT_READ | PROT_EXEC, MAP_PRIVATE, fd, 0);
    e = errno;
    close(fd);
    if (p == MAP_FAILED) return out(env, "FAIL mmap(PROT_EXEC) errno=%d (%s)", e, strerror(e));
    munmap(p, (size_t)sb.st_size);
    return out(env, "PASS mmap(PROT_READ|PROT_EXEC) of a %lld-byte file succeeded", (long long)sb.st_size);
}
