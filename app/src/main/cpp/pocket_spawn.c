#define _GNU_SOURCE
#include <jni.h>
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

/* close_range(2) landed in Linux 5.9 (Android 12+); the syscall number is
 * uniform across architectures. */
#ifndef SYS_close_range
#define SYS_close_range 436
#endif

static void close_pair(int pair[2]) { close(pair[0]); close(pair[1]); }

/**
 * The child is forked from the whole app process, so every descriptor the
 * parent holds (sockets, files) would survive execve unless it is
 * close-on-exec. No launcher path relies on inherited descriptors: stdio is
 * re-dup2'd below and every pipe/pty end is O_CLOEXEC, so all of them can go.
 *
 * The previous /proc/self/fd sweep used opendir/readdir, which allocate and
 * take libc-internal locks. After fork() in a multithreaded JVM the child can
 * inherit those locks in a held state, so the sweep could deadlock before
 * execve. close_range(2) is a single syscall with no userspace allocation.
 * The fallback sweeps a bounded descriptor range with plain close(), which is
 * a syscall and therefore safe in the forked child.
 */
static void close_inherited_fds(void) {
#ifdef SYS_close_range
    if (syscall(SYS_close_range, STDERR_FILENO + 1, ~0U, 0) == 0) return;
    /* ENOSYS on pre-5.9 kernels: fall through to the bounded sweep. */
#endif
    long limit = sysconf(_SC_OPEN_MAX);
    int max_fd = limit > 0 ? (int)limit : 1024;
    if (max_fd < 1024) max_fd = 1024;
    if (max_fd > 65536) max_fd = 65536;
    for (int fd = STDERR_FILENO + 1; fd < max_fd; fd++) {
        close(fd);
    }
}

JNIEXPORT jintArray JNICALL
Java_com_jarves_mh_runtime_NativeSpawn_spawn(JNIEnv *env, jobject self, jobjectArray java_argv,
                                               jobjectArray java_env, jstring java_cwd,
                                               jstring java_output, jboolean use_pty,
                                               jint pty_rows, jint pty_columns) {
    (void)self;
    jsize argc = (*env)->GetArrayLength(env, java_argv);
    jsize envc = (*env)->GetArrayLength(env, java_env);
    char **argv = calloc((size_t)argc + 1, sizeof(char *));
    char **envp = calloc((size_t)envc + 1, sizeof(char *));
    char *cwd = NULL;
    char *output_path = NULL;
    char *slave_name = NULL;
    int in_pipe[2] = {-1, -1};
    int master_fd = -1;
    pid_t pid = -1;
    jintArray result = NULL;

    if (!argv || !envp) goto fail;
    for (jsize i = 0; i < argc; i++) {
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, java_argv, i);
        if (!value || (*env)->ExceptionCheck(env)) goto fail;
        const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
        if (!utf) goto fail;
        argv[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, value, utf);
        (*env)->DeleteLocalRef(env, value);
        if (!argv[i]) goto fail;
    }
    for (jsize i = 0; i < envc; i++) {
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, java_env, i);
        if (!value || (*env)->ExceptionCheck(env)) goto fail;
        const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
        if (!utf) goto fail;
        envp[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, value, utf);
        (*env)->DeleteLocalRef(env, value);
        if (!envp[i]) goto fail;
    }

    const char *cwd_utf = (*env)->GetStringUTFChars(env, java_cwd, NULL);
    if (!cwd_utf) goto fail;
    cwd = strdup(cwd_utf);
    (*env)->ReleaseStringUTFChars(env, java_cwd, cwd_utf);
    if (!cwd) goto fail;

    const char *output_utf = (*env)->GetStringUTFChars(env, java_output, NULL);
    if (!output_utf) goto fail;
    output_path = strdup(output_utf);
    (*env)->ReleaseStringUTFChars(env, java_output, output_utf);
    if (!output_path) goto fail;

    if (use_pty) {
        master_fd = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
        if (master_fd < 0 || grantpt(master_fd) != 0 || unlockpt(master_fd) != 0) goto fail;
        const char *name = ptsname(master_fd);
        if (!name) goto fail;
        slave_name = strdup(name);
        if (!slave_name) goto fail;
    } else if (pipe2(in_pipe, O_CLOEXEC) != 0) {
        goto fail;
    }

    pid = fork();
    if (pid == 0) {
        if (use_pty) {
            if (setsid() < 0) _exit(126);
            int slave_fd = open(slave_name, O_RDWR);
            if (slave_fd < 0 || ioctl(slave_fd, TIOCSCTTY, 0) != 0) _exit(126);
            struct winsize size = {
                .ws_row = (unsigned short)(pty_rows > 0 ? pty_rows : 40),
                .ws_col = (unsigned short)(pty_columns > 0 ? pty_columns : 120),
            };
            ioctl(slave_fd, TIOCSWINSZ, &size);
            struct termios terminal;
            if (tcgetattr(slave_fd, &terminal) == 0) {
                terminal.c_lflag &= (tcflag_t)~(ECHO | ECHONL);
                tcsetattr(slave_fd, TCSANOW, &terminal);
            }
            dup2(slave_fd, STDIN_FILENO);
            dup2(slave_fd, STDOUT_FILENO);
            dup2(slave_fd, STDERR_FILENO);
            if (slave_fd > STDERR_FILENO) close(slave_fd);
            close(master_fd);
        } else {
            // Give every runtime launch its own process group so stopping the wrapper
            // also stops Claude Code and commands spawned underneath it.
            setpgid(0, 0);
            close(in_pipe[1]);
            int output_fd = open(output_path, O_CREAT | O_TRUNC | O_WRONLY, 0600);
            if (output_fd < 0) _exit(126);
            dup2(in_pipe[0], STDIN_FILENO);
            dup2(output_fd, STDOUT_FILENO);
            dup2(output_fd, STDERR_FILENO);
            close(in_pipe[0]);
            close(output_fd);
        }
        chdir(cwd);
        prctl(PR_SET_DUMPABLE, 1, 0, 0, 0);
        close_inherited_fds();
        execve(argv[0], argv, envp);
        dprintf(STDERR_FILENO, "Pocket native exec failed: %s\n", strerror(errno));
        _exit(127);
    }
    if (pid > 0 && !use_pty) setpgid(pid, pid);
    if (pid < 0) goto fail;

    if (use_pty) {
        free(slave_name);
        slave_name = NULL;
    }
    for (jsize i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    argv = NULL;
    for (jsize i = 0; i < envc; i++) free(envp[i]);
    free(envp);
    envp = NULL;
    free(cwd);
    cwd = NULL;
    free(output_path);
    output_path = NULL;

    if (!use_pty) {
        close(in_pipe[0]);
        in_pipe[0] = -1;
    }
    int input_fd = use_pty ? (int)fcntl(master_fd, F_DUPFD_CLOEXEC, 0) : in_pipe[1];
    if (input_fd < 0) goto fail;
    jint values[3] = {(jint)pid, (jint)input_fd, use_pty ? master_fd : -1};
    result = (*env)->NewIntArray(env, 3);
    if (!result) goto fail;
    (*env)->SetIntArrayRegion(env, result, 0, 3, values);
    return result;

fail:
    // Reached only before the descriptors are handed to the VM, so closing
    // them here cannot yank anything Kotlin already owns.
    if (pid > 0) {
        // Never leak an unkillable child behind a failed spawn.
        kill(pid, SIGKILL);
        waitpid(pid, NULL, 0);
    }
    free(slave_name);
    if (argv) {
        for (jsize i = 0; i < argc; i++) free(argv[i]);
        free(argv);
    }
    if (envp) {
        for (jsize i = 0; i < envc; i++) free(envp[i]);
        free(envp);
    }
    free(cwd);
    free(output_path);
    if (master_fd >= 0) close(master_fd);
    close_pair(in_pipe);
    return NULL;
}

JNIEXPORT jint JNICALL
Java_com_jarves_mh_runtime_NativeSpawn_waitFor(JNIEnv *env, jobject self, jint pid, jboolean no_hang) {
    (void)env; (void)self;
    int status = 0;
    pid_t value = waitpid(pid, &status, no_hang ? WNOHANG : 0);
    if (value == 0) return -2;
    if (value < 0) return -128 - errno;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

JNIEXPORT jint JNICALL
Java_com_jarves_mh_runtime_NativeSpawn_kill(JNIEnv *env, jobject self, jint pid, jint signal) {
    (void)env; (void)self;
    // Negative pid targets the whole runtime process group. Fall back to the
    // wrapper pid for devices where group creation raced with an early exit.
    int result = kill(-pid, signal);
    if (result != 0 && errno == ESRCH) result = kill(pid, signal);
    return result;
}
