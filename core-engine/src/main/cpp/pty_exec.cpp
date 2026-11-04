// JNI bridge for a real, PTY-backed local shell (dev.fonebrew.data.remote.NativePty /
// PtyShellSession) -- the on-device "This phone" Terminal facet.
//
// A plain ProcessBuilder-spawned process on Android has no controlling terminal at all: no
// line discipline (ONLCR translation, echo, job control signals), which is why the first cut
// of this feature printed "can't find tty fd" / "won't have full job control" and mis-wrapped
// output (bare LF moved the cursor down without returning it to column 0). This file gives the
// shell a genuine pty, the same way a real terminal app does it -- and the same approach
// Termux's terminal-emulator uses on Android, since bionic (like glibc) exposes the POSIX
// pty primitives (posix_openpt/grantpt/unlockpt/ptsname_r) even though it has no BSD
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
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <vector>
#include <string>

#define LOG_TAG "aarso-pty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_fonebrew_data_remote_NativePty_spawn(
    JNIEnv* env, jobject /*thiz*/,
    jstring j_cmd, jobjectArray j_args, jstring j_cwd, jint rows, jint cols) {

    auto fail = [&](const char* why) -> jintArray {
        LOGE("pty spawn failed: %s (errno=%d %s)", why, errno, strerror(errno));
        jintArray out = env->NewIntArray(2);
        jint vals[2] = {-1, -1};
        env->SetIntArrayRegion(out, 0, 2, vals);
        return out;
    };

    // 1) Open the pty master and unlock its slave -- entirely JNI/JVM-side, before fork().
    int master_fd = posix_openpt(O_RDWR | O_NOCTTY);
    if (master_fd < 0) return fail("posix_openpt");
    if (grantpt(master_fd) != 0) { close(master_fd); return fail("grantpt"); }
    if (unlockpt(master_fd) != 0) { close(master_fd); return fail("unlockpt"); }
    char slave_name[64];
    if (ptsname_r(master_fd, slave_name, sizeof(slave_name)) != 0) {
        close(master_fd); return fail("ptsname_r");
    }
    fcntl(master_fd, F_SETFD, FD_CLOEXEC);

    // 2) Marshal every JNI string/array into plain C data now -- the child must not touch
    // JNIEnv after fork().
    const char* cmd_c = env->GetStringUTFChars(j_cmd, nullptr);
    std::string cmd(cmd_c);
    env->ReleaseStringUTFChars(j_cmd, cmd_c);

    const char* cwd_c = env->GetStringUTFChars(j_cwd, nullptr);
    std::string cwd(cwd_c);
    env->ReleaseStringUTFChars(j_cwd, cwd_c);

    jsize argc = env->GetArrayLength(j_args);
    std::vector<std::string> arg_storage;
    arg_storage.reserve(argc);
    for (jsize i = 0; i < argc; i++) {
        auto j_arg = (jstring) env->GetObjectArrayElement(j_args, i);
        const char* a = env->GetStringUTFChars(j_arg, nullptr);
        arg_storage.emplace_back(a);
        env->ReleaseStringUTFChars(j_arg, a);
        env->DeleteLocalRef(j_arg);
    }
    std::vector<char*> argv;
    argv.reserve(arg_storage.size() + 1);
    for (auto& s : arg_storage) argv.push_back(const_cast<char*>(s.c_str()));
    argv.push_back(nullptr);

    struct winsize ws{};
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;

    // 3) fork(). Everything from here in the child is async-signal-safe POSIX only.
    pid_t pid = fork();
    if (pid < 0) { close(master_fd); return fail("fork"); }

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
        if (!cwd.empty()) chdir(cwd.c_str());
        setenv("TERM", "xterm-256color", 1);
        execv(cmd.c_str(), argv.data());
        _exit(127); // execv only returns on failure
    }

    // --- parent: hand the master fd + child pid back to Kotlin. ---
    jintArray out = env->NewIntArray(2);
    jint vals[2] = {master_fd, (jint) pid};
    env->SetIntArrayRegion(out, 0, 2, vals);
    return out;
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
