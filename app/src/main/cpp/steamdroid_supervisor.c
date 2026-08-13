#define _GNU_SOURCE

#include <errno.h>
#include <arpa/inet.h>
#include <ctype.h>
#include <dirent.h>
#include <fcntl.h>
#include <linux/limits.h>
#include <linux/capability.h>
#include <linux/securebits.h>
#include <sched.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/mount.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/time.h>
#include <unistd.h>
#include <sys/wait.h>
#include <grp.h>
#include <stddef.h>
#include <stdarg.h>

#define STEAMDROID_MAGIC 0x53445031u
#define STEAMDROID_VERSION 1u
#define MAX_PAYLOAD (1024u * 1024u)
#define MAX_NATIVE_ARGUMENTS 4096u
#define MAX_NATIVE_ARGUMENT_LENGTH (64u * 1024u)
#define NATIVE_EXEC_MAGIC 0x53444531u
#define NATIVE_EXEC_VERSION 1u

enum {
    OP_GET_CONTROL_STATUS = 1,
    OP_PREPARE_SESSION = 2,
    OP_DESTROY_SESSION = 3,
    OP_CREATE_UINPUT = 4,
    OP_DESTROY_UINPUT = 5,
    OP_GET_SESSION_STATUS = 6,
    OP_EXEC_RUNTIME_BWRAP = 7,
    OP_EXEC_NATIVE_STEAM = 8,
    OP_INSTALL_HOLO_PACKAGES = 9,
};

struct request_header {
    uint32_t magic;
    uint16_t version;
    uint16_t opcode;
    uint32_t request_id;
    uint32_t payload_length;
};

struct response_header {
    uint32_t magic;
    uint16_t version;
    uint16_t opcode;
    uint32_t request_id;
    int32_t status;
    uint32_t payload_length;
};

static volatile sig_atomic_t stop_requested;
static int expected_uid = -1;
static int expected_gid = -1;
static gid_t expected_groups[256];
static size_t expected_group_count;
static int prepared;
static unsigned int session_id;
static char mount_namespace[128] = "unknown";
static int guest_server_fd = -1;
static const char *guest_socket_path;
static const char *holo_root_path;
static const char *substrate_root_path;
static const char *holo_package_dir_path;
static char mounted_paths[16][PATH_MAX];
static size_t mounted_path_count;
static pid_t native_steam_pid = -1;

static int make_directory_path(const char *path);
static int prepare_holo_mounts(void);
static void unmount_holo_mounts(void);
static int owner_pid = -1;
static unsigned long long owner_start_time;

static void log_supervisor_message(const char *format, ...) {
    if (holo_root_path == NULL) return;
    char path[PATH_MAX];
    int path_length = snprintf(path, sizeof(path), "%s/tmp/steamdroid-supervisor.log", holo_root_path);
    if (path_length <= 0 || (size_t)path_length >= sizeof(path)) return;
    int fd = open(path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
    if (fd < 0) return;
    va_list arguments;
    va_start(arguments, format);
    vdprintf(fd, format, arguments);
    va_end(arguments);
    close(fd);
}

static void handle_signal(int signal_number) {
    (void)signal_number;
    stop_requested = 1;
}

static int read_full(int fd, void *buffer, size_t length) {
    unsigned char *cursor = (unsigned char *)buffer;
    while (length > 0) {
        ssize_t count = read(fd, cursor, length);
        if (count == 0) return 0;
        if (count < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        cursor += count;
        length -= (size_t)count;
    }
    return 1;
}

static int write_full(int fd, const void *buffer, size_t length) {
    const unsigned char *cursor = (const unsigned char *)buffer;
    while (length > 0) {
        ssize_t count = write(fd, cursor, length);
        if (count < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        cursor += count;
        length -= (size_t)count;
    }
    return 0;
}

static void namespace_identity(void) {
    char link_path[64];
    snprintf(link_path, sizeof(link_path), "/proc/%d/ns/mnt", getpid());
    ssize_t length = readlink(link_path, mount_namespace, sizeof(mount_namespace) - 1);
    if (length < 0) {
        snprintf(mount_namespace, sizeof(mount_namespace), "unavailable:%d", errno);
        return;
    }
    mount_namespace[length] = '\0';
}

static int prepare_private_namespace(void) {
    if (prepared) return 0;
    if (unshare(CLONE_NEWNS) != 0) return -errno;
    if (mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL) != 0) return -errno;
    if (prctl(PR_SET_CHILD_SUBREAPER, 1L, 0L, 0L, 0L) != 0) return -errno;
    int mount_status = prepare_holo_mounts();
    if (mount_status != 0) {
        unmount_holo_mounts();
        return mount_status;
    }
    prepared = 1;
    session_id++;
    namespace_identity();
    return 0;
}

static void reap_children(void) {
    int status;
    pid_t child;
    while ((child = waitpid(-1, &status, WNOHANG)) > 0) {
        if (child != native_steam_pid) continue;
        if (WIFEXITED(status)) {
            log_supervisor_message("native steam pid=%d exited status=%d\n", child, WEXITSTATUS(status));
        } else if (WIFSIGNALED(status)) {
            log_supervisor_message("native steam pid=%d killed signal=%d core=%d\n",
                                   child, WTERMSIG(status), WCOREDUMP(status) ? 1 : 0);
        } else if (WIFSTOPPED(status)) {
            log_supervisor_message("native steam pid=%d stopped signal=%d\n", child, WSTOPSIG(status));
        } else {
            log_supervisor_message("native steam pid=%d changed wait_status=0x%x\n", child, status);
        }
        native_steam_pid = -1;
    }
}

static int read_process_start_time(int pid, unsigned long long *start_time) {
    char path[64];
    char line[4096];
    snprintf(path, sizeof(path), "/proc/%d/stat", pid);
    FILE *file = fopen(path, "r");
    if (file == NULL || fgets(line, sizeof(line), file) == NULL) {
        if (file != NULL) fclose(file);
        return -1;
    }
    fclose(file);

    // The comm field can contain spaces and parentheses. Start tokenizing
    // only after its final closing parenthesis; the first token there is
    // field 3 and starttime is field 22.
    char *fields = strrchr(line, ')');
    if (fields == NULL) return -1;
    fields += 2;
    int field_number = 3;
    char *save_pointer = NULL;
    for (char *token = strtok_r(fields, " ", &save_pointer);
         token != NULL;
         token = strtok_r(NULL, " ", &save_pointer), field_number++) {
        if (field_number == 22) {
            char *end = NULL;
            unsigned long long value = strtoull(token, &end, 10);
            if (end == token || *end != '\0') return -1;
            *start_time = value;
            return 0;
        }
    }
    return -1;
}

static int read_process_parent(int pid, int *parent_pid) {
    char path[64];
    char line[4096];
    snprintf(path, sizeof(path), "/proc/%d/stat", pid);
    FILE *file = fopen(path, "r");
    if (file == NULL || fgets(line, sizeof(line), file) == NULL) {
        if (file != NULL) fclose(file);
        return -1;
    }
    fclose(file);

    char *fields = strrchr(line, ')');
    if (fields == NULL) return -1;
    fields += 2;
    // The substring starts with field 3; field 4 (ppid) is index 1.
    char *save_pointer = NULL;
    char *state = strtok_r(fields, " ", &save_pointer);
    char *parent = strtok_r(NULL, " ", &save_pointer);
    if (state == NULL || parent == NULL) return -1;
    char *end = NULL;
    long value = strtol(parent, &end, 10);
    if (end == parent || *end != '\0' || value <= 0 || value > INT32_MAX) return -1;
    *parent_pid = (int)value;
    return 0;
}

static int process_directory_name(const char *name) {
    if (*name == '\0') return 0;
    for (const char *cursor = name; *cursor != '\0'; cursor++) {
        if (!isdigit((unsigned char)*cursor)) return 0;
    }
    char *end = NULL;
    long value = strtol(name, &end, 10);
    return end != name && *end == '\0' && value > 0 && value <= INT32_MAX ? (int)value : 0;
}

static int is_descendant_of_supervisor(int pid) {
    int current = pid;
    for (int depth = 0; depth < 64 && current > 1; depth++) {
        if (current == getpid()) return 1;
        int parent = -1;
        if (read_process_parent(current, &parent) != 0) return 0;
        if (parent == current) return 0;
        current = parent;
    }
    return current == getpid();
}

static size_t collect_descendants(pid_t *pids, size_t capacity) {
    DIR *directory = opendir("/proc");
    if (directory == NULL) return 0;

    size_t count = 0;
    struct dirent *entry;
    while ((entry = readdir(directory)) != NULL && count < capacity) {
        int pid = process_directory_name(entry->d_name);
        if (pid <= 0 || pid == getpid() || !is_descendant_of_supervisor(pid)) continue;
        pids[count++] = (pid_t)pid;
    }
    closedir(directory);
    return count;
}

static void terminate_descendants(void) {
    pid_t pids[128];
    // A subreaper may acquire grandchildren after their immediate parent
    // exits. Repeat collection so each generation remains within the
    // supervisor-owned tree; never use kill(-1) or a process-group broadcast.
    for (int pass = 0; pass < 4; pass++) {
        size_t count = collect_descendants(pids, sizeof(pids) / sizeof(pids[0]));
        if (count == 0) return;
        for (size_t index = 0; index < count; index++) kill(pids[index], SIGTERM);

        for (int wait_pass = 0; wait_pass < 10; wait_pass++) {
            reap_children();
            int any_alive = 0;
            for (size_t index = 0; index < count; index++) {
                if (kill(pids[index], 0) == 0 || errno == EPERM) {
                    any_alive = 1;
                    break;
                }
            }
            if (!any_alive) break;
            usleep(20000);
        }

        count = collect_descendants(pids, sizeof(pids) / sizeof(pids[0]));
        for (size_t index = 0; index < count; index++) kill(pids[index], SIGKILL);
        reap_children();
    }
}

static int read_wire_u32(const unsigned char **cursor, size_t *remaining, uint32_t *value) {
    if (*remaining < 4) return -EINVAL;
    uint32_t network_value;
    memcpy(&network_value, *cursor, sizeof(network_value));
    *value = ntohl(network_value);
    *cursor += 4;
    *remaining -= 4;
    return 0;
}

static int path_has_parent_component(const char *path) {
    const char *cursor = path;
    while (*cursor != '\0') {
        while (*cursor == '/') cursor++;
        const char *component = cursor;
        while (*cursor != '\0' && *cursor != '/') cursor++;
        size_t length = (size_t)(cursor - component);
        if (length == 2 && component[0] == '.' && component[1] == '.') return 1;
    }
    return 0;
}

static int clear_process_capabilities(void) {
    struct __user_cap_header_struct header;
    struct __user_cap_data_struct data[2];
    memset(&header, 0, sizeof(header));
    memset(data, 0, sizeof(data));
    header.version = _LINUX_CAPABILITY_VERSION_3;
    header.pid = 0;
    if (syscall(SYS_capset, &header, data) != 0) return -errno;

    for (int capability = 0; capability < 64; capability++) {
        if (prctl(PR_CAPBSET_DROP, capability, 0L, 0L, 0L) != 0 && errno != EINVAL && errno != EPERM) {
            return -errno;
        }
    }
    prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_CLEAR_ALL, 0L, 0L, 0L);
    return 0;
}

static int drop_to_guest_identity(void) {
    if (expected_uid < 0 || expected_gid < 0 || expected_group_count == 0) return -EINVAL;
    if (setgroups(expected_group_count, expected_groups) != 0) return -errno;
    if (prctl(PR_SET_SECUREBITS,
              SECBIT_NOROOT | SECBIT_NOROOT_LOCKED |
              SECBIT_NO_SETUID_FIXUP | SECBIT_NO_SETUID_FIXUP_LOCKED |
              SECBIT_KEEP_CAPS_LOCKED, 0L, 0L, 0L) != 0) return -errno;
    /*
     * Drop the capability bounding set while the trampoline still has the
     * setup privilege needed to do so. Once setresuid() changes away from
     * root, CAP_SETPCAP is no longer available and a late bounding-set
     * drop would silently leave the Android root domain's bounding set in
     * place.
     */
    for (int capability = 0; capability < 64; capability++) {
        if (prctl(PR_CAPBSET_DROP, capability, 0L, 0L, 0L) != 0 && errno != EINVAL) {
            return -errno;
        }
    }
    if (setresgid((gid_t)expected_gid, (gid_t)expected_gid, (gid_t)expected_gid) != 0) return -errno;
    if (setresuid((uid_t)expected_uid, (uid_t)expected_uid, (uid_t)expected_uid) != 0) return -errno;
    if (clear_process_capabilities() != 0) return -errno;
    if (prctl(PR_SET_NO_NEW_PRIVS, 1L, 0L, 0L, 0L) != 0) return -errno;
    return getuid() == (uid_t)expected_uid && getgid() == (gid_t)expected_gid ? 0 : -EPERM;
}

static int parse_native_argv(const unsigned char *payload, size_t payload_length,
                             char ***argv_out, size_t *argc_out) {
    const unsigned char *cursor = payload;
    size_t remaining = payload_length;
    uint32_t magic;
    uint32_t version;
    uint32_t argc;
    if (read_wire_u32(&cursor, &remaining, &magic) != 0 ||
        read_wire_u32(&cursor, &remaining, &version) != 0 ||
        read_wire_u32(&cursor, &remaining, &argc) != 0 ||
        magic != NATIVE_EXEC_MAGIC || version != NATIVE_EXEC_VERSION ||
        argc == 0 || argc > MAX_NATIVE_ARGUMENTS) return -EINVAL;

    char **argv = calloc((size_t)argc + 1, sizeof(char *));
    if (argv == NULL) return -ENOMEM;
    size_t total = 0;
    for (uint32_t index = 0; index < argc; index++) {
        uint32_t length;
        if (read_wire_u32(&cursor, &remaining, &length) != 0 ||
            length == 0 || length > MAX_NATIVE_ARGUMENT_LENGTH || length > remaining) {
            for (uint32_t free_index = 0; free_index < index; free_index++) free(argv[free_index]);
            free(argv);
            return -EINVAL;
        }
        total += length;
        if (total > MAX_PAYLOAD) {
            for (uint32_t free_index = 0; free_index < index; free_index++) free(argv[free_index]);
            free(argv);
            return -E2BIG;
        }
        argv[index] = malloc((size_t)length + 1);
        if (argv[index] == NULL) {
            for (uint32_t free_index = 0; free_index <= index; free_index++) free(argv[free_index]);
            free(argv);
            return -ENOMEM;
        }
        memcpy(argv[index], cursor, length);
        argv[index][length] = '\0';
        cursor += length;
        remaining -= length;
    }
    if (remaining != 0 || argv[0][0] != '/' || path_has_parent_component(argv[0])) {
        for (uint32_t index = 0; index < argc; index++) free(argv[index]);
        free(argv);
        return -EPERM;
    }
    *argv_out = argv;
    *argc_out = argc;
    return 0;
}

static void free_native_argv(char **argv, size_t argc) {
    if (argv == NULL) return;
    for (size_t index = 0; index < argc; index++) free(argv[index]);
    free(argv);
}

static void native_steam_child(char **argv) {
    if (holo_root_path == NULL || chroot(holo_root_path) != 0 ||
        chdir("/home/steam/.local/share/Steam") != 0) _exit(126);
    int log_fd = open("/tmp/steamdroid-native-steam.log", O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
    if (log_fd >= 0) {
        dup2(log_fd, STDOUT_FILENO);
        dup2(log_fd, STDERR_FILENO);
        close(log_fd);
    }
    /*
     * The supervisor is launched from Java with inherited pipe descriptors.
     * Give the session launcher a stable null stdin, matching a normal
     * headless Steam service and avoiding dbus-daemon interpreting the
     * supervisor control plumbing as an interactive standard fd.
     */
    int null_fd = open("/dev/null", O_RDWR | O_CLOEXEC);
    if (null_fd < 0 || dup2(null_fd, STDIN_FILENO) < 0) {
        dprintf(STDERR_FILENO, "steamdroid: unable to open /dev/null before identity drop: %s\n",
                strerror(errno));
        _exit(126);
    }
    if (null_fd != STDIN_FILENO) close(null_fd);
    char runtime_directory[64];
    snprintf(runtime_directory, sizeof(runtime_directory), "/run/user/%d", expected_uid);
    if (make_directory_path(runtime_directory) != 0 ||
        chown(runtime_directory, (uid_t)expected_uid, (gid_t)expected_gid) != 0 ||
        chmod(runtime_directory, 0700) != 0) _exit(126);

    /*
     * Keep the native ARM client launch environment equivalent to the
     * validated Steam Runtime launch used by the sibling research harness.
     * In particular, the Steam Runtime-first search path is required for the
     * vgui2_s -> SteamUI handoff.  Some of these directories are populated by
     * Steam later (Runtime 4); retaining them in the path is intentional and
     * lets the same supervisor work before and after tool installation.
     */
    clearenv();
    if (setenv("HOME", "/home/steam", 1) != 0 ||
        setenv("USER", "steam", 1) != 0 ||
        setenv("LOGNAME", "steam", 1) != 0 ||
        setenv("DISPLAY", ":0", 1) != 0 ||
        setenv("PULSE_SERVER", "unix:/tmp/.sound/PS0", 1) != 0 ||
        setenv("XDG_RUNTIME_DIR", runtime_directory, 1) != 0 ||
        setenv("PATH", "/home/steam/.local/share/Steam/steam-runtime-steamrt-arm64/bin:/home/steam/.local/share/Steam/steam-runtime-steamrt-arm64/steamrt4_platform_4.0.20260805.254769/files/bin:/usr/bin:/bin", 1) != 0 ||
        setenv("LD_LIBRARY_PATH", "/home/steam/.local/share/Steam/steamrtarm64:/home/steam/.local/share/Steam/lib/aarch64-linux-gnu:/home/steam/.local/share/Steam/steamrtarm64/libs:/usr/lib:/home/steam/.local/share/Steam/steam-runtime-steamrt-arm64/steamrt4_platform_4.0.20260805.254769/files/lib/aarch64-linux-gnu:/home/steam/.local/share/Steam/steam-runtime-steamrt-arm64/steamrt4_platform_4.0.20260805.254769/files/lib/aarch64-linux-gnu/pulseaudio:/lib", 1) != 0 ||
        setenv("LD_PRELOAD", "/home/steam/.local/share/Steam/steamdroid/libsteamdroid_sysv_sem_shim.so", 1) != 0 ||
        setenv("TMPDIR", "/tmp", 1) != 0 ||
        setenv("XKB_CONFIG_ROOT", "/usr/share/X11/xkb", 1) != 0 ||
        setenv("LANG", "C.UTF-8", 1) != 0 ||
        setenv("LC_ALL", "C.UTF-8", 1) != 0) _exit(126);
    int identity_status = drop_to_guest_identity();
    if (identity_status != 0) {
        dprintf(STDERR_FILENO, "steamdroid: identity transition failed: %d (%s)\n",
                identity_status, strerror(-identity_status));
        _exit(126);
    }
    if (access("/dev/null", R_OK | W_OK) != 0) {
        dprintf(STDERR_FILENO, "steamdroid: /dev/null unavailable after identity drop: %s\n",
                strerror(errno));
        _exit(126);
    }
    execv(argv[0], argv);
    _exit(errno == ENOENT ? 127 : 126);
}

static int execute_native_steam(const unsigned char *payload, size_t payload_length,
                                char *response, size_t response_capacity, size_t *response_length) {
    char **argv = NULL;
    size_t argc = 0;
    int parse_status = parse_native_argv(payload, payload_length, &argv, &argc);
    if (parse_status != 0) return parse_status;
    if (holo_root_path == NULL || access(holo_root_path, F_OK) != 0) {
        free_native_argv(argv, argc);
        return -ENOENT;
    }

    pid_t child = fork();
    if (child < 0) {
        int error = -errno;
        free_native_argv(argv, argc);
        return error;
    }
    if (child == 0) {
        prctl(PR_SET_PDEATHSIG, SIGTERM, 0L, 0L, 0L);
        native_steam_child(argv);
    }
    native_steam_pid = child;
    *response_length = (size_t)snprintf(response, response_capacity, "pid=%d\n", child);
    free_native_argv(argv, argc);
    return 0;
}

static int package_name_compare(const void *left, const void *right) {
    const char *const *left_name = (const char *const *)left;
    const char *const *right_name = (const char *const *)right;
    return strcmp(*left_name, *right_name);
}

static int install_holo_packages(char *response, size_t response_capacity, size_t *response_length) {
    if (!prepared || holo_root_path == NULL || holo_package_dir_path == NULL) return -EPERM;

    DIR *directory = opendir(holo_package_dir_path);
    if (directory == NULL) return -errno;
    char package_names[128][NAME_MAX + 1];
    char *sorted_names[128];
    size_t package_count = 0;
    struct dirent *entry;
    while ((entry = readdir(directory)) != NULL) {
        if (entry->d_name[0] == '.' ||
            (strstr(entry->d_name, ".pkg.tar.zst") == NULL &&
             strstr(entry->d_name, ".pkg.tar.xz") == NULL) ||
            strlen(entry->d_name) >= sizeof(package_names[0])) continue;
        char package_path[PATH_MAX];
        snprintf(package_path, sizeof(package_path), "%s/%s", holo_package_dir_path, entry->d_name);
        struct stat package_stat;
        if (stat(package_path, &package_stat) != 0 || !S_ISREG(package_stat.st_mode)) continue;
        if (package_count >= sizeof(package_names) / sizeof(package_names[0])) {
            closedir(directory);
            return -E2BIG;
        }
        strcpy(package_names[package_count], entry->d_name);
        sorted_names[package_count] = package_names[package_count];
        package_count++;
    }
    closedir(directory);
    if (package_count == 0) return -ENOENT;
    qsort(sorted_names, package_count, sizeof(sorted_names[0]), package_name_compare);

    char target_dev[PATH_MAX];
    char target_sys[PATH_MAX];
    char target_packages[PATH_MAX];
    snprintf(target_dev, sizeof(target_dev), "%s/dev", holo_root_path);
    snprintf(target_sys, sizeof(target_sys), "%s/sys", holo_root_path);
    snprintf(target_packages, sizeof(target_packages), "%s/tmp/holo-pkgs", holo_root_path);
    if (make_directory_path(target_dev) != 0 || make_directory_path(target_sys) != 0 ||
        make_directory_path(target_packages) != 0) return -EIO;

    int mounted_dev = 0;
    int mounted_sys = 0;
    int mounted_packages = 0;
    int status = 0;
    if (mount("/dev", target_dev, NULL, MS_BIND | MS_REC, NULL) != 0) status = -errno;
    else mounted_dev = 1;
    if (status == 0 && mount("/sys", target_sys, NULL, MS_BIND | MS_REC, NULL) != 0) status = -errno;
    else if (status == 0) mounted_sys = 1;
    if (status == 0 && mount(holo_package_dir_path, target_packages, NULL, MS_BIND | MS_REC, NULL) != 0) status = -errno;
    else if (status == 0) mounted_packages = 1;

    char **arguments = NULL;
    if (status == 0) {
        arguments = calloc(package_count + 4, sizeof(char *));
        if (arguments == NULL) status = -ENOMEM;
    }
    if (status == 0) {
        arguments[0] = "/usr/bin/pacman";
        arguments[1] = "--noconfirm";
        arguments[2] = "-U";
        for (size_t index = 0; index < package_count; index++) {
            arguments[index + 3] = malloc(PATH_MAX);
            if (arguments[index + 3] == NULL) {
                status = -ENOMEM;
                break;
            }
            snprintf(arguments[index + 3], PATH_MAX, "/tmp/holo-pkgs/%s", sorted_names[index]);
        }
        arguments[package_count + 3] = NULL;
    }

    int exit_status = -1;
    if (status == 0) {
        pid_t child = fork();
        if (child < 0) status = -errno;
        else if (child == 0) {
            if (chroot(holo_root_path) != 0 || chdir("/") != 0) _exit(126);
            setenv("PATH", "/usr/bin:/bin", 1);
            setenv("HOME", "/root", 1);
            int log_fd = open("/tmp/steamdroid-pacman.log",
                              O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
            if (log_fd >= 0) {
                dup2(log_fd, STDOUT_FILENO);
                dup2(log_fd, STDERR_FILENO);
                close(log_fd);
            }
            execv(arguments[0], arguments);
            _exit(127);
        }
        else {
            if (waitpid(child, &exit_status, 0) < 0) status = -errno;
            else if (!WIFEXITED(exit_status) || WEXITSTATUS(exit_status) != 0) status = -EIO;
        }
    }

    if (mounted_packages) umount2(target_packages, MNT_DETACH);
    if (mounted_sys) umount2(target_sys, MNT_DETACH);
    if (mounted_dev) umount2(target_dev, MNT_DETACH);
    if (arguments != NULL) {
        for (size_t index = 3; index < package_count + 3; index++) free(arguments[index]);
        free(arguments);
    }
    if (status != 0) return status;
    *response_length = (size_t)snprintf(response, response_capacity,
                                        "packages=%zu\nexit_status=%d\n", package_count,
                                        WIFEXITED(exit_status) ? WEXITSTATUS(exit_status) : -1);
    return 0;
}

static int owner_is_alive(void) {
    if (owner_pid <= 0) return 0;
    if (kill(owner_pid, 0) != 0 && errno != EPERM) return 0;
    unsigned long long current_start_time;
    return read_process_start_time(owner_pid, &current_start_time) == 0 &&
           current_start_time == owner_start_time;
}

static int make_directory_path(const char *path) {
    char copy[PATH_MAX];
    if (path == NULL || strlen(path) >= sizeof(copy)) return -ENAMETOOLONG;
    strcpy(copy, path);
    for (char *cursor = copy + 1; *cursor != '\0'; cursor++) {
        if (*cursor != '/') continue;
        *cursor = '\0';
        if (mkdir(copy, 0755) != 0 && errno != EEXIST) return -errno;
        *cursor = '/';
    }
    if (mkdir(copy, 0755) != 0 && errno != EEXIST) return -errno;
    return 0;
}

static int append_mount(const char *target) {
    if (mounted_path_count >= sizeof(mounted_paths) / sizeof(mounted_paths[0]) ||
        strlen(target) >= PATH_MAX) return -ENOSPC;
    strcpy(mounted_paths[mounted_path_count++], target);
    return 0;
}

static int bind_mount(const char *source, const char *target) {
    struct stat source_stat;
    if (stat(source, &source_stat) != 0) return -errno;
    int directory_status = make_directory_path(target);
    if (directory_status != 0) return directory_status;
    if (mount(source, target, NULL, MS_BIND | MS_REC, NULL) != 0) return -errno;
    if (append_mount(target) != 0) {
        umount2(target, MNT_DETACH);
        return -ENOSPC;
    }
    return 0;
}

static int bind_file_mount(const char *source, const char *target) {
    struct stat source_stat;
    if (stat(source, &source_stat) != 0 || !S_ISREG(source_stat.st_mode)) return -errno;

    struct stat target_stat;
    if (lstat(target, &target_stat) != 0) {
        if (errno != ENOENT) return -errno;
        int file = open(target, O_WRONLY | O_CREAT | O_CLOEXEC, 0644);
        if (file < 0) return -errno;
        close(file);
    } else if (!S_ISREG(target_stat.st_mode)) {
        return -EINVAL;
    }
    if (mount(source, target, NULL, MS_BIND, NULL) != 0) return -errno;
    if (append_mount(target) != 0) {
        umount2(target, MNT_DETACH);
        return -ENOSPC;
    }
    return 0;
}

static int mount_proc(const char *target) {
    int directory_status = make_directory_path(target);
    if (directory_status != 0) return directory_status;
    if (mount("proc", target, "proc", MS_NOSUID | MS_NODEV | MS_NOEXEC, NULL) != 0) return -errno;
    if (append_mount(target) != 0) {
        umount2(target, MNT_DETACH);
        return -ENOSPC;
    }
    return 0;
}

static int mount_tmpfs(const char *target) {
    int directory_status = make_directory_path(target);
    if (directory_status != 0) return directory_status;
    if (mount("tmpfs", target, "tmpfs", MS_NOSUID | MS_NODEV | MS_NOEXEC,
              "mode=1777,size=64m") != 0) return -errno;
    if (append_mount(target) != 0) {
        umount2(target, MNT_DETACH);
        return -ENOSPC;
    }
    return 0;
}

static int prepare_holo_mounts(void) {
    if (holo_root_path == NULL || holo_root_path[0] == '\0') return 0;
    struct stat root_stat;
    if (stat(holo_root_path, &root_stat) != 0 || !S_ISDIR(root_stat.st_mode)) return 0;
    if (substrate_root_path == NULL || substrate_root_path[0] == '\0') return -EINVAL;

    char source[PATH_MAX];
    char target[PATH_MAX];
    /*
     * Holo is a real chroot, so it needs the host device and sysfs trees for
     * /dev/null, input, DRM/KGSL and the Vulkan provider. Keep these binds
     * inside this supervisor's private mount namespace and make them
     * recursive so device submounts remain visible to the guest.
     */
    snprintf(target, sizeof(target), "%s/dev", holo_root_path);
    int status = bind_mount("/dev", target);
    if (status != 0) return status;
    snprintf(target, sizeof(target), "%s/dev/shm", holo_root_path);
    status = mount_tmpfs(target);
    if (status != 0) return status;
    snprintf(target, sizeof(target), "%s/sys", holo_root_path);
    status = bind_mount("/sys", target);
    if (status != 0) return status;

    const char *socket_directories[] = {"tmp/.X11-unix", "tmp/.sound", "tmp/.sysvshm"};
    for (size_t index = 0; index < sizeof(socket_directories) / sizeof(socket_directories[0]); index++) {
        snprintf(source, sizeof(source), "%s/%s", substrate_root_path, socket_directories[index]);
        snprintf(target, sizeof(target), "%s/%s", holo_root_path, socket_directories[index]);
        status = bind_mount(source, target);
        if (status != 0) return status;
    }

    snprintf(source, sizeof(source), "%s/etc/hosts", substrate_root_path);
    snprintf(target, sizeof(target), "%s/etc/hosts", holo_root_path);
    struct stat hosts_stat;
    if (stat(source, &hosts_stat) == 0 && S_ISREG(hosts_stat.st_mode)) {
        status = bind_file_mount(source, target);
        if (status != 0) return status;
    }

    snprintf(source, sizeof(source), "%s/etc/resolv.conf", substrate_root_path);
    snprintf(target, sizeof(target), "%s/etc/resolv.conf", holo_root_path);
    struct stat resolver_stat;
    if (stat(source, &resolver_stat) == 0 && S_ISREG(resolver_stat.st_mode)) {
        status = bind_file_mount(source, target);
        if (status != 0) return status;
    }

    snprintf(target, sizeof(target), "%s/proc", holo_root_path);
    return mount_proc(target);
}

static void unmount_holo_mounts(void) {
    while (mounted_path_count > 0) {
        mounted_path_count--;
        umount2(mounted_paths[mounted_path_count], MNT_DETACH);
    }
}

static int open_endpoint(const char *socket_path, int abstract_namespace) {
    int server_fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct sockaddr_un address;
    socklen_t address_length;

    if (server_fd < 0) return -errno;
    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    size_t path_length = strlen(socket_path);
    if (path_length >= sizeof(address.sun_path) - (abstract_namespace ? 1 : 0)) {
        close(server_fd);
        return -ENAMETOOLONG;
    }
    if (abstract_namespace) {
        // The Android control plane must remain reachable after the helper
        // enters its private mount namespace.  An abstract AF_UNIX address
        // has no pathname to disappear when /data/user/0 is privatized.
        address.sun_path[0] = '\0';
        memcpy(address.sun_path + 1, socket_path, path_length);
        address_length = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + path_length);
    }
    else {
        strcpy(address.sun_path, socket_path);
        unlink(socket_path);
        address_length = sizeof(address);
    }

    // Both endpoints are created by the root supervisor but are private to
    // the package UID.  The guest endpoint is only made visible after the
    // private session has been prepared.
    if (bind(server_fd, (struct sockaddr *)&address, address_length) != 0 ||
        (!abstract_namespace &&
         (chown(socket_path, (uid_t)expected_uid, (gid_t)expected_gid) != 0 ||
          chmod(socket_path, 0600) != 0)) ||
        listen(server_fd, 4) != 0) {
        int error = errno;
        close(server_fd);
        if (!abstract_namespace) unlink(socket_path);
        return -error;
    }
    return server_fd;
}

static void close_endpoint(int *server_fd, const char *socket_path, int abstract_namespace) {
    if (*server_fd >= 0) {
        close(*server_fd);
        *server_fd = -1;
    }
    if (!abstract_namespace && socket_path != NULL) unlink(socket_path);
}

static int activate_guest_endpoint(void) {
    if (guest_server_fd >= 0) return 0;
    if (guest_socket_path == NULL) return -EINVAL;
    guest_server_fd = open_endpoint(guest_socket_path, 0);
    return guest_server_fd >= 0 ? 0 : guest_server_fd;
}

static int send_response(int fd, const struct request_header *request, int status,
                         const void *payload, size_t payload_length) {
    struct response_header response;
    if (payload_length > MAX_PAYLOAD) return -E2BIG;
    response.magic = htonl(STEAMDROID_MAGIC);
    response.version = htons(STEAMDROID_VERSION);
    response.opcode = htons(request->opcode);
    response.request_id = htonl(request->request_id);
    response.status = htonl((uint32_t)status);
    response.payload_length = htonl((uint32_t)payload_length);
    if (write_full(fd, &response, sizeof(response)) != 0) return -EIO;
    if (payload_length > 0 && write_full(fd, payload, payload_length) != 0) return -EIO;
    return 0;
}

static int process_request(int client_fd, const struct request_header *request,
                           const unsigned char *payload, int guest_endpoint) {
    (void)payload;
    char response[512];
    int status = 0;
    size_t response_length = 0;

    // The Android control socket and the guest proxy socket are separate
    // capabilities.  The guest side must never gain access to lifecycle,
    // uinput, or namespace preparation operations, even if a guest process
    // can reach the proxy path.
    if (guest_endpoint && request->opcode != OP_GET_SESSION_STATUS &&
        request->opcode != OP_EXEC_RUNTIME_BWRAP) {
        status = -EPERM;
    }
    if (!guest_endpoint && request->opcode == OP_EXEC_RUNTIME_BWRAP) {
        status = -EPERM;
    }

    if (status != 0) return send_response(client_fd, request, status, NULL, 0);

    switch (request->opcode) {
        case OP_GET_CONTROL_STATUS:
            response_length = snprintf(response, sizeof(response),
                                       "state=%s\nsupervisor_pid=%d\nuid=%d\nguest_proxy=%s\n",
                                       prepared ? "prepared" : "ready", getpid(), getuid(),
                                       guest_server_fd >= 0 ? "active" : "inactive");
            break;
        case OP_PREPARE_SESSION:
            if (request->payload_length != 0) {
                status = -EINVAL;
                break;
            }
            status = prepare_private_namespace();
            if (status == 0) status = activate_guest_endpoint();
            if (status == 0) {
                response_length = snprintf(response, sizeof(response),
                                           "session_id=%u\nmount_namespace=%s\n"
                                           "guest_proxy_socket=%s\n",
                                           session_id, mount_namespace,
                                           guest_socket_path != NULL ? guest_socket_path : "none");
            }
            break;
        case OP_GET_SESSION_STATUS:
            response_length = snprintf(response, sizeof(response),
                                       "state=%s\nsession_id=%u\nmount_namespace=%s\n",
                                       prepared ? "prepared" : "ready", session_id, mount_namespace);
            break;
        case OP_CREATE_UINPUT:
        case OP_DESTROY_UINPUT:
            // The opcode is reserved in M2. Real uinput ownership is added in
            // the substrate milestone; never accept an unimplemented request.
            status = -ENOSYS;
            break;
        case OP_EXEC_RUNTIME_BWRAP:
            // M7 supplies the authenticated argv/FD-preserving Runtime 4
            // proxy.  Keep this endpoint fail-closed until that implementation
            // is present; never turn a guest request into a shell command.
            status = -ENOSYS;
            break;
        case OP_INSTALL_HOLO_PACKAGES:
            if (request->payload_length != 0 || !prepared) {
                status = -EINVAL;
                break;
            }
            status = install_holo_packages(response, sizeof(response), &response_length);
            break;
        case OP_EXEC_NATIVE_STEAM:
            if (guest_endpoint || !prepared) {
                status = -EPERM;
                break;
            }
            status = execute_native_steam(payload, request->payload_length,
                                          response, sizeof(response), &response_length);
            break;
        case OP_DESTROY_SESSION:
            if (request->payload_length != 0) {
                status = -EINVAL;
                break;
            }
            response_length = snprintf(response, sizeof(response),
                                       "state=destroying\nsession_id=%u\n", session_id);
            stop_requested = 1;
            break;
        default:
            status = -ENOTSUP;
            break;
    }

    return send_response(client_fd, request, status, response, status == 0 ? response_length : 0);
}

static void serve_client(int client_fd, int guest_endpoint) {
    struct timeval receive_timeout = {1, 0};
    setsockopt(client_fd, SOL_SOCKET, SO_RCVTIMEO, &receive_timeout, sizeof(receive_timeout));
    struct ucred credentials;
    socklen_t credentials_length = sizeof(credentials);
    if (getsockopt(client_fd, SOL_SOCKET, SO_PEERCRED, &credentials, &credentials_length) != 0 ||
        (expected_uid >= 0 && (int)credentials.uid != expected_uid)) {
        close(client_fd);
        return;
    }

    while (!stop_requested) {
        struct request_header wire_request;
        int read_status = read_full(client_fd, &wire_request, sizeof(wire_request));
        if (read_status <= 0) break;

        struct request_header request;
        request.magic = ntohl(wire_request.magic);
        request.version = ntohs(wire_request.version);
        request.opcode = ntohs(wire_request.opcode);
        request.request_id = ntohl(wire_request.request_id);
        request.payload_length = ntohl(wire_request.payload_length);

        if (request.magic != STEAMDROID_MAGIC || request.version != STEAMDROID_VERSION ||
            request.payload_length > MAX_PAYLOAD) break;

        unsigned char *payload = NULL;
        if (request.payload_length > 0) {
            payload = (unsigned char *)malloc(request.payload_length);
            if (payload == NULL || read_full(client_fd, payload, request.payload_length) <= 0) {
                free(payload);
                break;
            }
        }
        int response_status = process_request(client_fd, &request, payload, guest_endpoint);
        free(payload);
        if (response_status != 0 || request.opcode == OP_DESTROY_SESSION) break;
    }
    close(client_fd);
}

static int serve(const char *socket_path, int control_abstract) {
    int control_fd = -1;
    struct sigaction action;

    memset(&action, 0, sizeof(action));
    action.sa_handler = handle_signal;
    sigemptyset(&action.sa_mask);
    sigaction(SIGTERM, &action, NULL);
    sigaction(SIGINT, &action, NULL);
    prctl(PR_SET_PDEATHSIG, SIGTERM, 0L, 0L, 0L);
    prctl(PR_SET_CHILD_SUBREAPER, 1L, 0L, 0L, 0L);

    control_fd = open_endpoint(socket_path, control_abstract);
    if (control_fd < 0) return 10;

    while (!stop_requested) {
        reap_children();
        struct pollfd poll_fds[2];
        nfds_t poll_count = 1;
        poll_fds[0].fd = control_fd;
        poll_fds[0].events = POLLIN;
        poll_fds[0].revents = 0;
        if (guest_server_fd >= 0) {
            poll_fds[1].fd = guest_server_fd;
            poll_fds[1].events = POLLIN;
            poll_fds[1].revents = 0;
            poll_count = 2;
        }

        int poll_status = poll(poll_fds, poll_count, 1000);
        if (poll_status < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (!owner_is_alive()) {
            stop_requested = 1;
            break;
        }
        if (poll_status == 0) continue;

        if (poll_fds[0].revents & POLLIN) {
            int client_fd = accept4(control_fd, NULL, NULL, SOCK_CLOEXEC);
            if (client_fd >= 0) serve_client(client_fd, 0);
        }
        if (guest_server_fd >= 0 && poll_count == 2 && (poll_fds[1].revents & POLLIN)) {
            int client_fd = accept4(guest_server_fd, NULL, NULL, SOCK_CLOEXEC);
            if (client_fd >= 0) serve_client(client_fd, 1);
        }
        reap_children();
    }

    terminate_descendants();
    close_endpoint(&guest_server_fd, guest_socket_path, 0);
    close_endpoint(&control_fd, socket_path, control_abstract);
    unmount_holo_mounts();
    reap_children();
    return 0;
}

static int self_test(void) {
    char executable[PATH_MAX];
    char link_path[64];
    snprintf(link_path, sizeof(link_path), "/proc/%d/exe", getpid());
    ssize_t length = readlink(link_path, executable, sizeof(executable) - 1);
    if (length < 0) return 20;
    executable[length] = '\0';
    printf("steamdroid-supervisor\n");
    printf("pid=%d\n", getpid());
    printf("uid=%d\n", getuid());
    printf("executable=%s\n", executable);
    printf("protocol=%u\n", STEAMDROID_VERSION);
    fflush(stdout);
    return 0;
}

static int parse_expected_groups(const char *value) {
    if (value == NULL || *value == '\0') return -EINVAL;
    char copy[4096];
    if (strlen(value) >= sizeof(copy)) return -E2BIG;
    strcpy(copy, value);
    char *save_pointer = NULL;
    for (char *token = strtok_r(copy, ",", &save_pointer);
         token != NULL;
         token = strtok_r(NULL, ",", &save_pointer)) {
        if (expected_group_count >= sizeof(expected_groups) / sizeof(expected_groups[0])) return -E2BIG;
        char *end = NULL;
        unsigned long group = strtoul(token, &end, 10);
        if (end == token || *end != '\0' || group > UINT32_MAX) return -EINVAL;
        expected_groups[expected_group_count++] = (gid_t)group;
    }
    return expected_group_count > 0 ? 0 : -EINVAL;
}

int main(int argc, char **argv) {
    const char *socket_path = NULL;
    int serving = 0;
    int control_abstract = 0;

    if (argc == 2 && strcmp(argv[1], "--self-test") == 0) return self_test();

    for (int index = 1; index < argc; index++) {
        if (strcmp(argv[index], "--serve") == 0) serving = 1;
        else if (strcmp(argv[index], "--control-socket") == 0 && index + 1 < argc) socket_path = argv[++index];
        else if (strcmp(argv[index], "--control-abstract") == 0 && index + 1 < argc) {
            socket_path = argv[++index];
            control_abstract = 1;
        }
        else if (strcmp(argv[index], "--guest-proxy-socket") == 0 && index + 1 < argc) guest_socket_path = argv[++index];
        else if (strcmp(argv[index], "--uid") == 0 && index + 1 < argc) expected_uid = atoi(argv[++index]);
        else if (strcmp(argv[index], "--gid") == 0 && index + 1 < argc) expected_gid = atoi(argv[++index]);
        else if (strcmp(argv[index], "--groups") == 0 && index + 1 < argc) {
            if (parse_expected_groups(argv[++index]) != 0) return 4;
        }
        else if (strcmp(argv[index], "--holo-root") == 0 && index + 1 < argc) holo_root_path = argv[++index];
        else if (strcmp(argv[index], "--substrate-root") == 0 && index + 1 < argc) substrate_root_path = argv[++index];
        else if (strcmp(argv[index], "--holo-package-dir") == 0 && index + 1 < argc) holo_package_dir_path = argv[++index];
        else if (strcmp(argv[index], "--owner-pid") == 0 && index + 1 < argc) owner_pid = atoi(argv[++index]);
        else if (strcmp(argv[index], "--owner-start-time") == 0 && index + 1 < argc) owner_start_time = strtoull(argv[++index], NULL, 10);
        else return 2;
    }

    if (!serving || socket_path == NULL || guest_socket_path == NULL || expected_uid < 0 || expected_gid < 0 ||
        expected_group_count == 0 || holo_root_path == NULL || substrate_root_path == NULL ||
        holo_package_dir_path == NULL ||
        owner_pid <= 0 || owner_start_time == 0) return 3;
    return serve(socket_path, control_abstract);
}
