// JNI bridge for a real, PTY-backed local shell (dev.fonebrew.data.remote.NativePty /
// PtyShellSession) -- the on-device "This phone" Terminal facet.
//
// A plain ProcessBuilder-spawned process on Android has no controlling terminal at all: no
// line discipline (ONLCR translation, echo, job control signals), which is why the first cut
// of this feature printed "can't find tty fd" / "won't have full job control" and mis-wrapped
// output (bare LF moved the cursor down without returning it to column 0). This file gives the
// shell a genuine pty, the way POSIX documents it: bionic (like glibc) exposes the pty
// primitives (posix_openpt/grantpt/unlockpt/ptsname_r) even though it has no BSD
// openpty()/forkpty() convenience wrappers.
//
// Safety note: after fork(), the child touches ONLY async-signal-safe POSIX calls (setsid,
// open, dup2, ioctl, chdir, execv, _exit) -- no JNI/JVM calls, which are not fork-safe. Every
// JNI string/array is converted to plain C data BEFORE fork() for exactly this reason.

#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <limits.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <vector>
#include <string>

#define LOG_TAG "aarso-pty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Child-side hygiene, all async-signal-safe: nothing the app process inherited may leak into the
// shell. Ignored signals survive exec (ART/zygote ignore SIGPIPE), and so does the signal mask,
// so both are reset; every descriptor above stderr is closed (close_range where the kernel has
// it, a bounded loop otherwise).
static void child_reset_signals() {
    sigset_t none;
    sigemptyset(&none);
    sigprocmask(SIG_SETMASK, &none, nullptr);
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = SIG_DFL;
    const int sigs[] = {SIGHUP, SIGINT, SIGQUIT, SIGPIPE, SIGTERM, SIGCHLD, SIGTSTP, SIGTTIN, SIGTTOU};
    for (int sg : sigs) sigaction(sg, &sa, nullptr);
}

static void child_close_inherited_fds() {
#ifdef __NR_close_range
    if (syscall(__NR_close_range, 3u, ~0u, 0u) == 0) return;
#endif
    long max = sysconf(_SC_OPEN_MAX);
    if (max < 0 || max > 4096) max = 4096;
    for (int fd = 3; fd < max; fd++) close(fd);
}

static jintArray fail_array(JNIEnv* env, const char* why) {
    LOGE("pty spawn failed: %s (errno=%d %s)", why, errno, strerror(errno));
    jintArray out = env->NewIntArray(2);
    jint vals[2] = {-1, -1};
    env->SetIntArrayRegion(out, 0, 2, vals);
    return out;
}

static std::string to_std(JNIEnv* env, jstring js) {
    if (js == nullptr) return std::string();
    const char* c = env->GetStringUTFChars(js, nullptr);
    if (c == nullptr) return std::string();
    std::string s(c);
    env->ReleaseStringUTFChars(js, c);
    return s;
}

// Shared by spawn (legacy: inherits the environment, sets TERM) and spawnEx (explicit
// environment: exactly the KEY=VALUE strings given, nothing else).
static jintArray spawn_impl(JNIEnv* env, jstring j_cmd, jobjectArray j_args, jobjectArray j_env,
                            jstring j_cwd, jint rows, jint cols, bool explicit_env) {
    // 1) Marshal every JNI string/array into plain C data first -- the child must not touch
    // JNIEnv after fork().
    std::string cmd = to_std(env, j_cmd);
    std::string cwd = to_std(env, j_cwd);
    if (cmd.empty() || j_args == nullptr) { errno = EINVAL; return fail_array(env, "bad arguments"); }

    jsize argc = env->GetArrayLength(j_args);
    std::vector<std::string> arg_storage;
    arg_storage.reserve(argc);
    for (jsize i = 0; i < argc; i++) {
        auto j_arg = (jstring) env->GetObjectArrayElement(j_args, i);
        arg_storage.push_back(to_std(env, j_arg));
        if (j_arg != nullptr) env->DeleteLocalRef(j_arg);
    }
    std::vector<char*> argv;
    argv.reserve(arg_storage.size() + 1);
    for (auto& s : arg_storage) argv.push_back(const_cast<char*>(s.c_str()));
    argv.push_back(nullptr);

    std::vector<std::string> env_storage;
    std::vector<char*> envp;
    if (explicit_env && j_env != nullptr) {
        jsize envc = env->GetArrayLength(j_env);
        env_storage.reserve(envc);
        for (jsize i = 0; i < envc; i++) {
            auto j_e = (jstring) env->GetObjectArrayElement(j_env, i);
            env_storage.push_back(to_std(env, j_e));
            if (j_e != nullptr) env->DeleteLocalRef(j_e);
        }
        envp.reserve(env_storage.size() + 1);
        for (auto& s : env_storage) envp.push_back(const_cast<char*>(s.c_str()));
        envp.push_back(nullptr);
    }

    // 2) Open the pty master and unlock its slave -- JNI/JVM-side, before fork().
    int master_fd = posix_openpt(O_RDWR | O_NOCTTY);
    if (master_fd < 0) return fail_array(env, "posix_openpt");
    if (grantpt(master_fd) != 0) { int e = errno; close(master_fd); errno = e; return fail_array(env, "grantpt"); }
    if (unlockpt(master_fd) != 0) { int e = errno; close(master_fd); errno = e; return fail_array(env, "unlockpt"); }
    char slave_name[64];
    if (ptsname_r(master_fd, slave_name, sizeof(slave_name)) != 0) {
        int e = errno; close(master_fd); errno = e; return fail_array(env, "ptsname_r");
    }
    fcntl(master_fd, F_SETFD, FD_CLOEXEC);

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;

    // 3) fork(). Everything from here in the child is async-signal-safe POSIX only.
    pid_t pid = fork();
    if (pid < 0) { int e = errno; close(master_fd); errno = e; return fail_array(env, "fork"); }

    if (pid == 0) {
        // --- child: become the session leader of a real controlling terminal, then exec. ---
        close(master_fd);
        setsid();
        int slave_fd = open(slave_name, O_RDWR);
        if (slave_fd < 0) _exit(126);
        ioctl(slave_fd, TIOCSCTTY, 0);
        ioctl(slave_fd, TIOCSWINSZ, &ws);
        dup2(slave_fd, STDIN_FILENO);
        dup2(slave_fd, STDOUT_FILENO);
        dup2(slave_fd, STDERR_FILENO);
        if (slave_fd > STDERR_FILENO) close(slave_fd);
        if (explicit_env) {
            child_reset_signals();
            child_close_inherited_fds();
        }
        if (!cwd.empty() && chdir(cwd.c_str()) != 0) {
            // Keep going: a shell in the wrong directory is better than no shell, and the
            // prompt shows where it is.
        }
        if (explicit_env) {
            execve(cmd.c_str(), argv.data(), envp.empty() ? (char* const*) nullptr : envp.data());
        } else {
            setenv("TERM", "xterm-256color", 1);
            execv(cmd.c_str(), argv.data());
        }
        _exit(127); // exec only returns on failure
    }

    // --- parent: hand the master fd + child pid back to Kotlin. ---
    jintArray out = env->NewIntArray(2);
    jint vals[2] = {master_fd, (jint) pid};
    env->SetIntArrayRegion(out, 0, 2, vals);
    return out;
}

// Legacy entry point: inherited environment + TERM. Signature unchanged.
extern "C" JNIEXPORT jintArray JNICALL
Java_dev_fonebrew_data_remote_NativePty_spawn(
    JNIEnv* env, jobject /*thiz*/,
    jstring j_cmd, jobjectArray j_args, jstring j_cwd, jint rows, jint cols) {
    return spawn_impl(env, j_cmd, j_args, nullptr, j_cwd, rows, cols, false);
}

// Explicit environment (KEY=VALUE strings; null or empty array = an empty environment), signal
// dispositions/mask reset, all inherited descriptors closed.
extern "C" JNIEXPORT jintArray JNICALL
Java_dev_fonebrew_data_remote_NativePty_spawnEx(
    JNIEnv* env, jobject /*thiz*/,
    jstring j_cmd, jobjectArray j_args, jobjectArray j_env, jstring j_cwd, jint rows, jint cols) {
    return spawn_impl(env, j_cmd, j_args, j_env, j_cwd, rows, cols, true);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_fonebrew_data_remote_NativePty_resize(JNIEnv* /*env*/, jobject /*thiz*/, jint fd, jint rows, jint cols) {
    struct winsize ws{};
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ioctl(fd, TIOCSWINSZ, &ws);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_fonebrew_data_remote_NativePty_waitFor(JNIEnv* /*env*/, jobject /*thiz*/, jint pid) {
    int status = 0;
    pid_t r = waitpid((pid_t) pid, &status, 0);
    if (r < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return -1;
}

// Foreground process group of the pty's slave side, asked through the master fd (TIOCGPGRP on a
// pty master answers for the slave's foreground group). Returns -1 if the kernel will not say --
// the caller maps that to "unknown", never a guess.
extern "C" JNIEXPORT jint JNICALL
Java_dev_fonebrew_data_remote_NativePty_foregroundPgid(JNIEnv* /*env*/, jobject /*thiz*/, jint master_fd) {
    pid_t pg = tcgetpgrp(master_fd);
    return pg > 0 ? (jint) pg : -1;
}

// Deliver SIGHUP/SIGINT/SIGQUIT/SIGTSTP to the pty's foreground process group. Refuses (returns
// -1, signals nothing) unless: the signal is one of those four; the group is > 1; the group is
// not this app's own; and, when the kernel can say, the group belongs to the shell's session.
// The group is the one the kernel reports for OUR pty at this instant -- never a cached value.
extern "C" JNIEXPORT jint JNICALL
Java_dev_fonebrew_data_remote_NativePty_signalForeground(JNIEnv* /*env*/, jobject /*thiz*/,
                                                         jint master_fd, jint shell_pid, jint sig) {
    if (sig != SIGHUP && sig != SIGINT && sig != SIGQUIT && sig != SIGTSTP) return -1;
    if (shell_pid <= 1) return -1;
    pid_t pg = tcgetpgrp(master_fd);
    if (pg <= 1) return -1;
    if (pg == getpgrp() || pg == getpid()) return -1;
    pid_t sid = getsid(pg);
    if (sid >= 0 && sid != (pid_t) shell_pid) return -1;
    return kill(-pg, sig) == 0 ? 0 : -1;
}

// Non-blocking reap: INT_MIN while the child still runs, else exit status (or -signal).
// -1 if there is nothing to wait for (already reaped / not our child).
extern "C" JNIEXPORT jint JNICALL
Java_dev_fonebrew_data_remote_NativePty_tryWait(JNIEnv* /*env*/, jobject /*thiz*/, jint pid) {
    int status = 0;
    pid_t r = waitpid((pid_t) pid, &status, WNOHANG);
    if (r == 0) return INT_MIN;
    if (r < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return -1;
}

// Signal the shell process itself (our own unreaped child), only SIGHUP/SIGTERM/SIGKILL; used
// to tear a session down when closing the pty did not end it.
extern "C" JNIEXPORT jint JNICALL
Java_dev_fonebrew_data_remote_NativePty_signalChild(JNIEnv* /*env*/, jobject /*thiz*/, jint pid, jint sig) {
    if (pid <= 1) return -1;
    if (sig != SIGHUP && sig != SIGTERM && sig != SIGKILL) return -1;
    return kill((pid_t) pid, sig) == 0 ? 0 : -1;
}
