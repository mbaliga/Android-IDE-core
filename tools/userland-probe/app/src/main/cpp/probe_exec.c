/* Probe test executable. Prints identity and resource limits, then exits 0. It opens no
 * sockets, writes no files and spawns nothing. */
#include <stdio.h>
#include <sys/resource.h>
#include <sys/utsname.h>
#include <unistd.h>

static void limit(const char *name, int res) {
    struct rlimit r;
    if (getrlimit(res, &r) == 0)
        printf("%s soft=%lld hard=%lld\n", name, (long long)r.rlim_cur, (long long)r.rlim_max);
}

int main(void) {
    struct utsname u;
    printf("probe_exec ok\n");
    if (uname(&u) == 0)
        printf("uname %s %s %s\n", u.sysname, u.release, u.machine);
    printf("uid=%d pid=%d\n", (int)getuid(), (int)getpid());
    limit("RLIMIT_NPROC", RLIMIT_NPROC);
    limit("RLIMIT_NOFILE", RLIMIT_NOFILE);
    return 0;
}
