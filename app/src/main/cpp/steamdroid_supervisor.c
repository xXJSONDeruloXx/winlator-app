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
#include <sys/uio.h>
#include <unistd.h>
#include <sys/wait.h>
#include <grp.h>
#include <stddef.h>
#include <stdarg.h>

extern char **environ;

#define STEAMDROID_MAGIC 0x53445031u
#define STEAMDROID_VERSION 1u
#define MAX_PAYLOAD (1024u * 1024u)
#define MAX_NATIVE_ARGUMENTS 4096u
#define MAX_NATIVE_ARGUMENT_LENGTH (64u * 1024u)
#define NATIVE_EXEC_MAGIC 0x53444531u
#define NATIVE_EXEC_VERSION 1u
#define RUNTIME_BWRAP_MAGIC 0x53524231u
#define RUNTIME_BWRAP_VERSION 1u
#define MAX_RUNTIME_ARGUMENTS 4096u
#define MAX_RUNTIME_ENVIRONMENT 4096u
#define MAX_RUNTIME_FDS 256u
#define MAX_RUNTIME_ARGS_BYTES (16u * 1024u * 1024u)
#define MAX_RUNTIME_STRING_BYTES (64u * 1024u)
#define VALIDATED_RUNTIME_BWRAP_PATH \
    "/home/steam/.local/share/Steam/steamapps/common/SteamLinuxRuntime_4-arm64/pressure-vessel/libexec/steam-runtime-tools-0/srt-bwrap"
#define BOOTSTRAP_RUNTIME_BWRAP_PATH "/usr/bin/bwrap"
#define RUNTIME_BWRAP_FIXTURE_PATH "/usr/bin/steamdroid-bwrap-fixture"

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
    OP_RUNTIME_BWRAP_STATUS = 10,
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
static int guest_endpoint_ready;
static const char *guest_socket_path;
static const char *holo_root_path;
static const char *substrate_root_path;
static const char *holo_package_dir_path;
static char mounted_paths[16][PATH_MAX];
static size_t mounted_path_count;
static pid_t native_steam_pid = -1;
static pid_t system_dbus_pid = -1;
static pid_t runtime_bwrap_pid = -1;
static unsigned int runtime_job_id;
static int runtime_job_status;
static int runtime_job_complete;

static int make_directory_path(const char *path);
static int prepare_holo_mounts(void);
static void unmount_holo_mounts(void);
static int start_private_system_dbus(void);
static int install_guest_helpers(void);
static int execute_runtime_bwrap(const unsigned char *payload, size_t payload_length,
                                  const int *received_fds, size_t received_fd_count,
                                  char *response, size_t response_capacity,
                                  size_t *response_length);
static int parse_expected_groups(const char *value);
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
    int helper_status = install_guest_helpers();
    if (helper_status != 0) return helper_status;
    int mount_status = prepare_holo_mounts();
    if (mount_status != 0) {
        unmount_holo_mounts();
        return mount_status;
    }
    int dbus_status = start_private_system_dbus();
    if (dbus_status != 0) {
        unmount_holo_mounts();
        return dbus_status;
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
        if (child == native_steam_pid) {
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
        } else if (child == system_dbus_pid) {
            if (WIFEXITED(status)) {
                log_supervisor_message("private system dbus pid=%d exited status=%d\n",
                                       child, WEXITSTATUS(status));
            } else if (WIFSIGNALED(status)) {
                log_supervisor_message("private system dbus pid=%d killed signal=%d\n",
                                       child, WTERMSIG(status));
            }
            system_dbus_pid = -1;
        } else if (child == runtime_bwrap_pid) {
            runtime_bwrap_pid = -1;
            runtime_job_complete = 1;
            runtime_job_status = WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
            log_supervisor_message("runtime bwrap job=%u pid=%d exited status=%d\n",
                                   runtime_job_id, child, runtime_job_status);
        }
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

struct process_identity_state {
    uid_t real_uid;
    uid_t effective_uid;
    uid_t saved_uid;
    uid_t filesystem_uid;
    gid_t real_gid;
    gid_t effective_gid;
    gid_t saved_gid;
    gid_t filesystem_gid;
    gid_t groups[256];
    int group_count;
    unsigned long long cap_inheritable;
    unsigned long long cap_permitted;
    unsigned long long cap_effective;
    unsigned long long cap_bounding;
    unsigned long long cap_ambient;
    int no_new_privs;
    int securebits;
    char lsm_context[128];
};

static int capture_process_identity(struct process_identity_state *state) {
    memset(state, 0, sizeof(*state));
    if (getresuid(&state->real_uid, &state->effective_uid, &state->saved_uid) != 0 ||
        getresgid(&state->real_gid, &state->effective_gid, &state->saved_gid) != 0) {
        return -errno;
    }
    state->filesystem_uid = state->effective_uid;
    state->filesystem_gid = state->effective_gid;
    int group_count = getgroups(0, NULL);
    if (group_count < 0 || group_count > (int)(sizeof(state->groups) / sizeof(state->groups[0]))) {
        return group_count < 0 ? -errno : -E2BIG;
    }
    state->group_count = getgroups(group_count, state->groups);
    if (state->group_count < 0) return -errno;

    FILE *status_file = fopen("/proc/self/status", "r");
    if (status_file == NULL) return -errno;
    char line[512];
    int saw_caps = 0;
    int saw_no_new_privs = 0;
    int saw_ids = 0;
    while (fgets(line, sizeof(line), status_file) != NULL) {
        unsigned long long value;
        int integer_value;
        unsigned int real_id;
        unsigned int effective_id;
        unsigned int saved_id;
        unsigned int filesystem_id;
        if (sscanf(line, "Uid:\t%u\t%u\t%u\t%u", &real_id, &effective_id,
                   &saved_id, &filesystem_id) == 4) {
            state->real_uid = (uid_t)real_id;
            state->effective_uid = (uid_t)effective_id;
            state->saved_uid = (uid_t)saved_id;
            state->filesystem_uid = (uid_t)filesystem_id;
            saw_ids |= 1;
        } else if (sscanf(line, "Gid:\t%u\t%u\t%u\t%u", &real_id, &effective_id,
                          &saved_id, &filesystem_id) == 4) {
            state->real_gid = (gid_t)real_id;
            state->effective_gid = (gid_t)effective_id;
            state->saved_gid = (gid_t)saved_id;
            state->filesystem_gid = (gid_t)filesystem_id;
            saw_ids |= 2;
        } else if (sscanf(line, "CapInh:\t%llx", &value) == 1) {
            state->cap_inheritable = value;
            saw_caps |= 1;
        } else if (sscanf(line, "CapPrm:\t%llx", &value) == 1) {
            state->cap_permitted = value;
            saw_caps |= 2;
        } else if (sscanf(line, "CapEff:\t%llx", &value) == 1) {
            state->cap_effective = value;
            saw_caps |= 4;
        } else if (sscanf(line, "CapBnd:\t%llx", &value) == 1) {
            state->cap_bounding = value;
            saw_caps |= 8;
        } else if (sscanf(line, "CapAmb:\t%llx", &value) == 1) {
            state->cap_ambient = value;
            saw_caps |= 16;
        } else if (sscanf(line, "NoNewPrivs:\t%d", &integer_value) == 1) {
            state->no_new_privs = integer_value;
            saw_no_new_privs = 1;
        }
    }
    fclose(status_file);
    if (saw_ids != 3 || saw_caps != 31 || !saw_no_new_privs) return -EINVAL;

    state->securebits = prctl(PR_GET_SECUREBITS, 0L, 0L, 0L, 0L);
    if (state->securebits < 0) return -errno;
    int context_fd = open("/proc/self/attr/current", O_RDONLY | O_CLOEXEC);
    if (context_fd >= 0) {
        ssize_t length = read(context_fd, state->lsm_context, sizeof(state->lsm_context) - 1);
        close(context_fd);
        if (length > 0) {
            state->lsm_context[length] = '\0';
            char *newline = strchr(state->lsm_context, '\n');
            if (newline != NULL) *newline = '\0';
        }
    }
    if (state->lsm_context[0] == '\0') strcpy(state->lsm_context, "unavailable");
    return 0;
}

static void log_process_identity(const char *label, const struct process_identity_state *state) {
    dprintf(STDERR_FILENO,
            "steamdroid: %s uid=%u/%u/%u/%u gid=%u/%u/%u/%u groups=%d "
            "CapInh=%016llx CapPrm=%016llx CapEff=%016llx CapBnd=%016llx "
            "CapAmb=%016llx NoNewPrivs=%d SecureBits=0x%x LSM=%s\n",
            label,
            (unsigned int)state->real_uid, (unsigned int)state->effective_uid,
            (unsigned int)state->saved_uid, (unsigned int)state->filesystem_uid,
            (unsigned int)state->real_gid, (unsigned int)state->effective_gid,
            (unsigned int)state->saved_gid, (unsigned int)state->filesystem_gid,
            state->group_count,
            state->cap_inheritable, state->cap_permitted, state->cap_effective,
            state->cap_bounding, state->cap_ambient, state->no_new_privs,
            state->securebits, state->lsm_context);
}

static int compare_gids(const void *left, const void *right) {
    gid_t left_gid = *(const gid_t *)left;
    gid_t right_gid = *(const gid_t *)right;
    return left_gid < right_gid ? -1 : left_gid > right_gid ? 1 : 0;
}

static int same_group_set(const gid_t *actual, size_t actual_count,
                          const gid_t *expected, size_t expected_count) {
    if (actual_count != expected_count) return 0;
    gid_t actual_sorted[256];
    gid_t expected_sorted[256];
    memcpy(actual_sorted, actual, actual_count * sizeof(actual_sorted[0]));
    memcpy(expected_sorted, expected, expected_count * sizeof(expected_sorted[0]));
    qsort(actual_sorted, actual_count, sizeof(actual_sorted[0]), compare_gids);
    qsort(expected_sorted, expected_count, sizeof(expected_sorted[0]), compare_gids);
    return memcmp(actual_sorted, expected_sorted,
                  actual_count * sizeof(actual_sorted[0])) == 0;
}

static int verify_guest_identity(void) {
    struct process_identity_state state;
    int status = capture_process_identity(&state);
    if (status != 0) return status;
    log_process_identity("guest identity", &state);
    int ids_match = state.real_uid == (uid_t)expected_uid &&
        state.effective_uid == (uid_t)expected_uid &&
        state.saved_uid == (uid_t)expected_uid &&
        state.filesystem_uid == (uid_t)expected_uid &&
        state.real_gid == (gid_t)expected_gid &&
        state.effective_gid == (gid_t)expected_gid &&
        state.saved_gid == (gid_t)expected_gid &&
        state.filesystem_gid == (gid_t)expected_gid;
    int groups_match = same_group_set(state.groups, (size_t)state.group_count,
                                      expected_groups, expected_group_count);
    int caps_empty = state.cap_inheritable == 0 && state.cap_permitted == 0 &&
        state.cap_effective == 0 && state.cap_bounding == 0 && state.cap_ambient == 0;
    int locked_bits = SECBIT_NOROOT_LOCKED | SECBIT_NO_SETUID_FIXUP_LOCKED |
                      SECBIT_KEEP_CAPS_LOCKED;
    int securebits_locked = (state.securebits & locked_bits) == locked_bits;
    if (!ids_match || !groups_match || !caps_empty || state.no_new_privs != 1 ||
        !securebits_locked) {
        dprintf(STDERR_FILENO,
                "steamdroid: identity verification flags ids=%d groups=%d caps=%d nnp=%d secure=%d expected_groups=%zu\n",
                ids_match, groups_match, caps_empty, state.no_new_privs,
                securebits_locked, expected_group_count);
        return -EPERM;
    }
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
    int capability_status = clear_process_capabilities();
    if (capability_status != 0) return capability_status;
    if (prctl(PR_SET_NO_NEW_PRIVS, 1L, 0L, 0L, 0L) != 0) return -errno;
    return verify_guest_identity();
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

struct runtime_request {
    char **argv;
    size_t argc;
    char **environment;
    size_t environment_count;
    uint32_t *fd_numbers;
    size_t fd_count;
};

struct runtime_fd_map {
    uint32_t original;
    int received;
    int mapped;
};

static void free_runtime_request(struct runtime_request *request) {
    if (request == NULL) return;
    free_native_argv(request->argv, request->argc);
    free_native_argv(request->environment, request->environment_count);
    free(request->fd_numbers);
    memset(request, 0, sizeof(*request));
}

static int read_runtime_string(const unsigned char **cursor, size_t *remaining,
                               char **value_out) {
    uint32_t length;
    if (read_wire_u32(cursor, remaining, &length) != 0 || length == 0 ||
        length > MAX_RUNTIME_STRING_BYTES || length > *remaining) return -EINVAL;
    if (memchr(*cursor, '\0', length) != NULL) return -EINVAL;
    char *value = malloc((size_t)length + 1);
    if (value == NULL) return -ENOMEM;
    memcpy(value, *cursor, length);
    value[length] = '\0';
    *cursor += length;
    *remaining -= length;
    *value_out = value;
    return 0;
}

static int parse_runtime_request(const unsigned char *payload, size_t payload_length,
                                 struct runtime_request *request) {
    memset(request, 0, sizeof(*request));
    const unsigned char *cursor = payload;
    size_t remaining = payload_length;
    uint32_t magic;
    uint32_t version;
    uint32_t argc;
    uint32_t environment_count;
    uint32_t fd_count;
    if (read_wire_u32(&cursor, &remaining, &magic) != 0 ||
        read_wire_u32(&cursor, &remaining, &version) != 0 ||
        read_wire_u32(&cursor, &remaining, &argc) != 0 ||
        read_wire_u32(&cursor, &remaining, &environment_count) != 0 ||
        read_wire_u32(&cursor, &remaining, &fd_count) != 0 ||
        magic != RUNTIME_BWRAP_MAGIC || version != RUNTIME_BWRAP_VERSION ||
        argc == 0 || argc > MAX_RUNTIME_ARGUMENTS ||
        environment_count > MAX_RUNTIME_ENVIRONMENT || fd_count > MAX_RUNTIME_FDS) {
        return -EINVAL;
    }

    request->argc = argc;
    request->environment_count = environment_count;
    request->fd_count = fd_count;
    request->argv = calloc((size_t)argc + 1, sizeof(char *));
    request->environment = calloc((size_t)environment_count + 1, sizeof(char *));
    request->fd_numbers = calloc((size_t)fd_count, sizeof(uint32_t));
    if (request->argv == NULL || request->environment == NULL ||
        (fd_count > 0 && request->fd_numbers == NULL)) {
        free_runtime_request(request);
        return -ENOMEM;
    }

    size_t string_bytes = 0;
    for (size_t index = 0; index < request->argc; index++) {
        int status = read_runtime_string(&cursor, &remaining, &request->argv[index]);
        if (status != 0) {
            free_runtime_request(request);
            return status;
        }
        string_bytes += strlen(request->argv[index]);
        if (string_bytes > MAX_PAYLOAD) {
            free_runtime_request(request);
            return -E2BIG;
        }
    }
    for (size_t index = 0; index < request->environment_count; index++) {
        int status = read_runtime_string(&cursor, &remaining, &request->environment[index]);
        if (status != 0) {
            free_runtime_request(request);
            return status;
        }
        string_bytes += strlen(request->environment[index]);
        if (string_bytes > MAX_PAYLOAD) {
            free_runtime_request(request);
            return -E2BIG;
        }
    }
    for (size_t index = 0; index < request->fd_count; index++) {
        if (read_wire_u32(&cursor, &remaining, &request->fd_numbers[index]) != 0 ||
            request->fd_numbers[index] > 4096) {
            free_runtime_request(request);
            return -EINVAL;
        }
        for (size_t previous = 0; previous < index; previous++) {
            if (request->fd_numbers[previous] == request->fd_numbers[index]) {
                free_runtime_request(request);
                return -EINVAL;
            }
        }
    }
    if (remaining != 0 || request->argv[0][0] != '/' ||
        path_has_parent_component(request->argv[0])) {
        free_runtime_request(request);
        return -EPERM;
    }
    return 0;
}

static int parse_fd_number(const char *value, int *number_out) {
    if (value == NULL || value[0] == '\0') return -EINVAL;
    char *end = NULL;
    errno = 0;
    long number = strtol(value, &end, 10);
    if (errno != 0 || end == value || *end != '\0' || number < 0 || number > 4096) return -EINVAL;
    *number_out = (int)number;
    return 0;
}

static int validated_runtime_bwrap_path(const char *path) {
    return path != NULL &&
        (strcmp(path, VALIDATED_RUNTIME_BWRAP_PATH) == 0 ||
         strcmp(path, BOOTSTRAP_RUNTIME_BWRAP_PATH) == 0 ||
         strcmp(path, RUNTIME_BWRAP_FIXTURE_PATH) == 0);
}

static int find_runtime_fd(const struct runtime_fd_map *maps, size_t map_count,
                           int original, int *mapped_out) {
    for (size_t index = 0; index < map_count; index++) {
        if ((int)maps[index].original == original) {
            *mapped_out = maps[index].mapped;
            return 0;
        }
    }
    return -EBADF;
}

static int read_runtime_args_fd(int fd, unsigned char **data_out, size_t *size_out) {
    size_t capacity = 4096;
    size_t size = 0;
    unsigned char *data = malloc(capacity);
    if (data == NULL) return -ENOMEM;
    for (;;) {
        if (size == capacity) {
            if (capacity >= MAX_RUNTIME_ARGS_BYTES) {
                free(data);
                return -E2BIG;
            }
            size_t next_capacity = capacity * 2;
            if (next_capacity > MAX_RUNTIME_ARGS_BYTES) next_capacity = MAX_RUNTIME_ARGS_BYTES;
            unsigned char *expanded = realloc(data, next_capacity);
            if (expanded == NULL) {
                free(data);
                return -ENOMEM;
            }
            data = expanded;
            capacity = next_capacity;
        }
        ssize_t count = read(fd, data + size, capacity - size);
        if (count < 0) {
            if (errno == EINTR) continue;
            int status = -errno;
            free(data);
            return status;
        }
        if (count == 0) break;
        size += (size_t)count;
    }
    if (size == 0 || data[size - 1] != '\0') {
        free(data);
        return -EINVAL;
    }
    *data_out = data;
    *size_out = size;
    return 0;
}

static int make_runtime_args_fd(const unsigned char *data, size_t size) {
    char path[PATH_MAX];
    if (holo_root_path == NULL || holo_root_path[0] != '/') return -EINVAL;
    int length = snprintf(path, sizeof(path), "%s/tmp/steamdroid-bwrap-args.XXXXXX", holo_root_path);
    if (length <= 0 || (size_t)length >= sizeof(path)) return -ENAMETOOLONG;
    int fd = mkstemp(path);
    if (fd < 0) return -errno;
    unlink(path);
    if (fcntl(fd, F_SETFD, 0) != 0 || write_full(fd, data, size) != 0) {
        int status = -errno;
        close(fd);
        return status == 0 ? -EIO : status;
    }
    return fd;
}

static int append_runtime_identity_args(int fd) {
    char uid[32];
    char gid[32];
    char groups[4096];
    int uid_length = snprintf(uid, sizeof(uid), "%d", expected_uid);
    int gid_length = snprintf(gid, sizeof(gid), "%d", expected_gid);
    size_t group_offset = 0;
    for (size_t index = 0; index < expected_group_count; index++) {
        int written = snprintf(groups + group_offset, sizeof(groups) - group_offset,
                               "%s%d", index == 0 ? "" : ",", (int)expected_groups[index]);
        if (written <= 0 || (size_t)written >= sizeof(groups) - group_offset) return -E2BIG;
        group_offset += (size_t)written;
    }
    if (uid_length <= 0 || gid_length <= 0) return -E2BIG;
    const char *arguments[] = {
        "/usr/bin/steamdroid-identity", "--identity-trampoline", "--uid", uid,
        "--gid", gid, "--groups", groups, "--"
    };
    for (size_t index = 0; index < sizeof(arguments) / sizeof(arguments[0]); index++) {
        if (write_full(fd, arguments[index], strlen(arguments[index]) + 1) != 0) return -EIO;
    }
    return 0;
}

static int insert_identity_into_args_fd(int source_fd, int *replacement_fd_out) {
    unsigned char *data = NULL;
    size_t size = 0;
    int status = read_runtime_args_fd(source_fd, &data, &size);
    if (status != 0) return status;

    size_t offset = 0;
    size_t insertion_offset = 0;
    while (offset < size) {
        const unsigned char *end = memchr(data + offset, '\0', size - offset);
        if (end == NULL) {
            free(data);
            return -EINVAL;
        }
        size_t length = (size_t)(end - (data + offset));
        if (length == 2 && memcmp(data + offset, "--", 2) == 0) {
            insertion_offset = offset + length + 1;
            break;
        }
        offset += length + 1;
    }
    if (insertion_offset == 0) {
        free(data);
        return -EINVAL;
    }

    int replacement_fd = make_runtime_args_fd(data, insertion_offset);
    if (replacement_fd < 0) {
        free(data);
        return replacement_fd;
    }
    status = append_runtime_identity_args(replacement_fd);
    if (status == 0) status = write_full(replacement_fd, data + insertion_offset, size - insertion_offset) == 0 ? 0 : -EIO;
    free(data);
    if (status != 0 || lseek(replacement_fd, 0, SEEK_SET) < 0) {
        if (status == 0) status = -errno;
        close(replacement_fd);
        return status;
    }
    *replacement_fd_out = replacement_fd;
    return 0;
}

static int replace_runtime_argument(char **argument, const char *value) {
    char *replacement = strdup(value);
    if (replacement == NULL) return -ENOMEM;
    free(*argument);
    *argument = replacement;
    return 0;
}

static int prepare_runtime_argv(struct runtime_request *request,
                                const struct runtime_fd_map *maps, size_t map_count,
                                int *temporary_args_fd_out) {
    *temporary_args_fd_out = -1;
    for (size_t index = 0; index < request->argc; index++) {
        int args_fd = -1;
        if (strcmp(request->argv[index], "--args") == 0) {
            if (index + 1 >= request->argc || parse_fd_number(request->argv[index + 1], &args_fd) != 0) return -EINVAL;
            int mapped_fd;
            int status = find_runtime_fd(maps, map_count, args_fd, &mapped_fd);
            if (status != 0) {
                log_supervisor_message("runtime --args fd=%d not found map_count=%zu\n",
                                       args_fd, map_count);
                return status;
            }
            status = insert_identity_into_args_fd(mapped_fd, temporary_args_fd_out);
            if (status != 0) return status;
            char replacement[32];
            snprintf(replacement, sizeof(replacement), "%d", *temporary_args_fd_out);
            return replace_runtime_argument(&request->argv[index + 1], replacement);
        }
        if (strncmp(request->argv[index], "--args=", 7) == 0) {
            if (parse_fd_number(request->argv[index] + 7, &args_fd) != 0) return -EINVAL;
            int mapped_fd;
            int status = find_runtime_fd(maps, map_count, args_fd, &mapped_fd);
            if (status != 0) return status;
            status = insert_identity_into_args_fd(mapped_fd, temporary_args_fd_out);
            if (status != 0) return status;
            char replacement[64];
            snprintf(replacement, sizeof(replacement), "--args=%d", *temporary_args_fd_out);
            return replace_runtime_argument(&request->argv[index], replacement);
        }
    }

    size_t separator = request->argc;
    for (size_t index = 0; index < request->argc; index++) {
        if (strcmp(request->argv[index], "--") == 0) {
            separator = index;
            break;
        }
    }
    if (separator == request->argc) return -EINVAL;

    const char *identity[] = {
        "/usr/bin/steamdroid-identity", "--identity-trampoline", "--uid", NULL,
        "--gid", NULL, "--groups", NULL, "--"
    };
    char uid[32];
    char gid[32];
    char groups[4096];
    snprintf(uid, sizeof(uid), "%d", expected_uid);
    snprintf(gid, sizeof(gid), "%d", expected_gid);
    size_t group_offset = 0;
    for (size_t index = 0; index < expected_group_count; index++) {
        int written = snprintf(groups + group_offset, sizeof(groups) - group_offset,
                               "%s%d", index == 0 ? "" : ",", (int)expected_groups[index]);
        if (written <= 0 || (size_t)written >= sizeof(groups) - group_offset) return -E2BIG;
        group_offset += (size_t)written;
    }
    char **expanded = calloc(request->argc + 10, sizeof(char *));
    if (expanded == NULL) return -ENOMEM;
    size_t output = 0;
    for (size_t index = 0; index <= separator; index++) expanded[output++] = request->argv[index];
    for (size_t index = 0; index < sizeof(identity) / sizeof(identity[0]); index++) {
        if (index == 3) expanded[output++] = strdup(uid);
        else if (index == 5) expanded[output++] = strdup(gid);
        else if (index == 7) expanded[output++] = strdup(groups);
        else expanded[output++] = strdup(identity[index]);
        if (expanded[output - 1] == NULL) {
            for (size_t cleanup = separator + 1; cleanup < output; cleanup++) free(expanded[cleanup]);
            free(expanded);
            return -ENOMEM;
        }
    }
    for (size_t index = separator + 1; index < request->argc; index++) expanded[output++] = request->argv[index];
    free(request->argv);
    request->argv = expanded;
    request->argc = output;
    return 0;
}

static int runtime_args_start_with_identity(const unsigned char *data, size_t size) {
    size_t offset = 0;
    while (offset < size) {
        const unsigned char *end = memchr(data + offset, '\0', size - offset);
        if (end == NULL) return 0;
        size_t length = (size_t)(end - (data + offset));
        if (length == 2 && memcmp(data + offset, "--", 2) == 0) {
            size_t identity_offset = offset + length + 1;
            const char *identity = "/usr/bin/steamdroid-identity";
            size_t identity_length = strlen(identity);
            if (identity_offset + identity_length >= size ||
                memcmp(data + identity_offset, identity, identity_length) != 0 ||
                data[identity_offset + identity_length] != '\0') return 0;
            return 1;
        }
        offset += length + 1;
    }
    return 0;
}

static int runtime_argv_start_with_identity(int argc, char **argv) {
    for (int index = 0; index < argc; index++) {
        if (strcmp(argv[index], "--") != 0 || index + 1 >= argc) continue;
        return strcmp(argv[index + 1], "/usr/bin/steamdroid-identity") == 0;
    }
    return 0;
}

static int runtime_bwrap_fixture_main(int argc, char **argv) {
    for (int index = 1; index < argc; index++) {
        int args_fd = -1;
        if (strcmp(argv[index], "--args") == 0 && index + 1 < argc) {
            if (parse_fd_number(argv[index + 1], &args_fd) != 0) return 2;
        } else if (strncmp(argv[index], "--args=", 7) == 0) {
            if (parse_fd_number(argv[index] + 7, &args_fd) != 0) return 2;
        } else {
            continue;
        }
        unsigned char *data = NULL;
        size_t size = 0;
        int status = read_runtime_args_fd(args_fd, &data, &size);
        int valid = status == 0 && runtime_args_start_with_identity(data, size);
        dprintf(STDERR_FILENO, "steamdroid-bwrap-fixture: fd=%d read_status=%d size=%zu valid=%d\n",
                args_fd, status, size, valid);
        free(data);
        return valid ? 0 : 2;
    }
    int valid = runtime_argv_start_with_identity(argc, argv);
    dprintf(STDERR_FILENO, "steamdroid-bwrap-fixture: outer argv valid=%d argc=%d\n", valid, argc);
    return valid ? 0 : 2;
}

static int retain_bwrap_setup_capabilities(void) {
    struct __user_cap_header_struct header;
    struct __user_cap_data_struct data[2];
    uint64_t keep = (1ULL << CAP_DAC_OVERRIDE) | (1ULL << CAP_DAC_READ_SEARCH) |
                    (1ULL << CAP_FOWNER) | (1ULL << CAP_SETGID) |
                    (1ULL << CAP_SETUID) | (1ULL << CAP_SETPCAP) |
                    (1ULL << CAP_SYS_CHROOT) | (1ULL << CAP_SYS_ADMIN) |
                    (1ULL << CAP_MKNOD);
    memset(&header, 0, sizeof(header));
    memset(data, 0, sizeof(data));
    header.version = _LINUX_CAPABILITY_VERSION_3;
    data[0].permitted = (uint32_t)keep;
    data[0].effective = (uint32_t)keep;
    data[1].permitted = (uint32_t)(keep >> 32);
    data[1].effective = (uint32_t)(keep >> 32);
    for (int capability = 0; capability < 64; capability++) {
        if ((keep & (1ULL << capability)) != 0) continue;
        if (prctl(PR_CAPBSET_DROP, capability, 0L, 0L, 0L) != 0 && errno != EINVAL) {
            return -errno;
        }
    }
    if (prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_CLEAR_ALL, 0L, 0L, 0L) != 0 && errno != EINVAL) {
        return -errno;
    }
    if (syscall(SYS_capset, &header, data) != 0) return -errno;
    return 0;
}

static int runtime_fd_is_original(const struct runtime_fd_map *maps, size_t map_count, int fd) {
    for (size_t index = 0; index < map_count; index++) {
        if ((int)maps[index].original == fd) return 1;
    }
    return 0;
}

static void restore_runtime_fds(const struct runtime_fd_map *maps, size_t map_count,
                                const int *received_fds, size_t received_fd_count) {
    for (size_t index = 0; index < map_count; index++) {
        if (dup2(maps[index].mapped, (int)maps[index].original) < 0) _exit(126);
    }
    for (size_t index = 0; index < map_count; index++) close(maps[index].mapped);
    for (size_t index = 0; index < received_fd_count; index++) {
        if (!runtime_fd_is_original(maps, map_count, received_fds[index])) close(received_fds[index]);
    }
}

static void runtime_bwrap_child(struct runtime_request *request,
                                const struct runtime_fd_map *maps, size_t map_count,
                                const int *received_fds, size_t received_fd_count) {
    prctl(PR_SET_PDEATHSIG, SIGTERM, 0L, 0L, 0L);
    if (holo_root_path == NULL || chroot(holo_root_path) != 0 || chdir("/") != 0) _exit(126);
    restore_runtime_fds(maps, map_count, received_fds, received_fd_count);
    if (retain_bwrap_setup_capabilities() != 0) _exit(126);
    execve(request->argv[0], request->argv, request->environment);
    _exit(errno == ENOENT ? 127 : 126);
}

static int execute_runtime_bwrap(const unsigned char *payload, size_t payload_length,
                                 const int *received_fds, size_t received_fd_count,
                                 char *response, size_t response_capacity,
                                 size_t *response_length) {
    if (runtime_bwrap_pid > 0) return -EBUSY;

    struct runtime_request request;
    int status = parse_runtime_request(payload, payload_length, &request);
    if (status != 0) return status;
    if (!validated_runtime_bwrap_path(request.argv[0])) {
        log_supervisor_message("runtime rejected bwrap entrypoint=%s\n", request.argv[0]);
        free_runtime_request(&request);
        return -EPERM;
    }
    log_supervisor_message("runtime parsed argc=%zu env=%zu fds=%zu received=%zu\n",
                           request.argc, request.environment_count, request.fd_count,
                           received_fd_count);
    if (request.fd_count != received_fd_count) {
        free_runtime_request(&request);
        return -EBADF;
    }

    struct runtime_fd_map *maps = calloc(request.fd_count, sizeof(*maps));
    if (request.fd_count > 0 && maps == NULL) {
        free_runtime_request(&request);
        return -ENOMEM;
    }
    for (size_t index = 0; index < request.fd_count; index++) {
        maps[index].original = request.fd_numbers[index];
        maps[index].received = received_fds[index];
        maps[index].mapped = fcntl(received_fds[index], F_DUPFD, 10000 + (int)index);
        if (maps[index].mapped < 0) {
            status = -errno;
            for (size_t cleanup = 0; cleanup < index; cleanup++) close(maps[cleanup].mapped);
            free(maps);
            free_runtime_request(&request);
            return status;
        }
    }

    int temporary_args_fd = -1;
    status = prepare_runtime_argv(&request, maps, request.fd_count, &temporary_args_fd);
    if (status != 0) {
        if (temporary_args_fd >= 0) close(temporary_args_fd);
        for (size_t index = 0; index < request.fd_count; index++) close(maps[index].mapped);
        free(maps);
        free_runtime_request(&request);
        return status;
    }

    unsigned int next_job_id = runtime_job_id + 1;
    if (next_job_id == 0) next_job_id = 1;
    pid_t child = fork();
    if (child < 0) {
        status = -errno;
        if (temporary_args_fd >= 0) close(temporary_args_fd);
        for (size_t index = 0; index < request.fd_count; index++) close(maps[index].mapped);
        free(maps);
        free_runtime_request(&request);
        return status;
    }
    if (child == 0) runtime_bwrap_child(&request, maps, request.fd_count, received_fds, received_fd_count);

    runtime_job_id = next_job_id;
    runtime_job_status = 0;
    runtime_job_complete = 0;
    runtime_bwrap_pid = child;
    if (temporary_args_fd >= 0) close(temporary_args_fd);
    for (size_t index = 0; index < request.fd_count; index++) {
        close(maps[index].mapped);
    }
    free(maps);
    free_runtime_request(&request);
    uint32_t network_job_id = htonl(runtime_job_id);
    if (response_capacity < sizeof(network_job_id)) return -E2BIG;
    memcpy(response, &network_job_id, sizeof(network_job_id));
    *response_length = sizeof(network_job_id);
    log_supervisor_message("runtime bwrap job=%u pid=%d started\n", runtime_job_id, child);
    return 0;
}

static int identity_trampoline_main(int argc, char **argv) {
    if (argc < 3) return 2;
    expected_uid = -1;
    expected_gid = -1;
    expected_group_count = 0;
    int command_index = -1;
    for (int index = 2; index < argc; index++) {
        if (strcmp(argv[index], "--uid") == 0 && index + 1 < argc) {
            expected_uid = atoi(argv[++index]);
        } else if (strcmp(argv[index], "--gid") == 0 && index + 1 < argc) {
            expected_gid = atoi(argv[++index]);
        } else if (strcmp(argv[index], "--groups") == 0 && index + 1 < argc) {
            if (parse_expected_groups(argv[++index]) != 0) return 125;
        } else if (strcmp(argv[index], "--") == 0) {
            command_index = index + 1;
            break;
        } else {
            return 2;
        }
    }
    if (command_index < 0 || command_index >= argc || expected_uid < 0 || expected_gid < 0 ||
        expected_group_count == 0) return 2;
    int status = drop_to_guest_identity();
    if (status != 0) return 126;
    execvp(argv[command_index], &argv[command_index]);
    return errno == ENOENT ? 127 : 126;
}

struct runtime_payload_builder {
    unsigned char data[MAX_PAYLOAD];
    size_t length;
};

static int append_runtime_payload_u32(struct runtime_payload_builder *builder, uint32_t value) {
    if (builder->length + sizeof(value) > sizeof(builder->data)) return -E2BIG;
    uint32_t network_value = htonl(value);
    memcpy(builder->data + builder->length, &network_value, sizeof(network_value));
    builder->length += sizeof(network_value);
    return 0;
}

static int append_runtime_payload_string(struct runtime_payload_builder *builder, const char *value) {
    size_t length = strlen(value);
    if (length == 0 || length > MAX_RUNTIME_STRING_BYTES ||
        append_runtime_payload_u32(builder, (uint32_t)length) != 0 ||
        builder->length + length > sizeof(builder->data)) return -E2BIG;
    memcpy(builder->data + builder->length, value, length);
    builder->length += length;
    return 0;
}

static int collect_runtime_fds(int excluded_fd, int *fds, size_t *count_out) {
    size_t count = 0;
    for (int fd = 0; fd <= 1024; fd++) {
        if (fd == excluded_fd || fcntl(fd, F_GETFD) < 0) continue;
        if (count >= MAX_RUNTIME_FDS) return -E2BIG;
        fds[count++] = fd;
    }
    *count_out = count;
    return 0;
}

static int connect_runtime_proxy_socket(const char *path) {
    if (path == NULL || path[0] != '/' || strlen(path) >= sizeof(((struct sockaddr_un *)0)->sun_path)) return -EINVAL;
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -errno;
    struct sockaddr_un address;
    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    strcpy(address.sun_path, path);
    if (connect(fd, (struct sockaddr *)&address, sizeof(address)) != 0) {
        int status = -errno;
        char namespace_path[64];
        char namespace_name[64] = "unknown";
        snprintf(namespace_path, sizeof(namespace_path), "/proc/%d/ns/mnt", getpid());
        ssize_t namespace_length = readlink(namespace_path, namespace_name,
                                             sizeof(namespace_name) - 1);
        if (namespace_length > 0) namespace_name[namespace_length] = '\0';
        struct stat socket_stat;
        int socket_stat_status = stat(path, &socket_stat);
        dprintf(STDERR_FILENO, "steamdroid-bwrap-proxy: connect %s failed: %s\n",
                path, strerror(-status));
        dprintf(STDERR_FILENO, "steamdroid-bwrap-proxy: uid=%d mnt=%s stat=%s\n",
                getuid(), namespace_name,
                socket_stat_status == 0 ? "present" : strerror(errno));
        close(fd);
        return status;
    }
    return fd;
}

static int send_runtime_request(int socket_fd, uint16_t opcode,
                                const unsigned char *payload, size_t payload_length,
                                const int *fds, size_t fd_count) {
    struct request_header request;
    request.magic = htonl(STEAMDROID_MAGIC);
    request.version = htons(STEAMDROID_VERSION);
    request.opcode = htons(opcode);
    request.request_id = htonl(1);
    request.payload_length = htonl((uint32_t)payload_length);
    if (payload_length > MAX_PAYLOAD) return -E2BIG;

    if (fd_count == 0) {
        if (write_full(socket_fd, &request, sizeof(request)) != 0 ||
            (payload_length > 0 && write_full(socket_fd, payload, payload_length) != 0)) return -EIO;
        return 0;
    }

    size_t frame_length = sizeof(request) + payload_length;
    unsigned char *frame = malloc(frame_length);
    if (frame == NULL) return -ENOMEM;
    memcpy(frame, &request, sizeof(request));
    if (payload_length > 0) memcpy(frame + sizeof(request), payload, payload_length);
    char control_buffer[CMSG_SPACE(MAX_RUNTIME_FDS * sizeof(int))];
    struct iovec vector = {frame, frame_length};
    struct msghdr message;
    memset(&message, 0, sizeof(message));
    message.msg_iov = &vector;
    message.msg_iovlen = 1;
    message.msg_control = control_buffer;
    message.msg_controllen = CMSG_SPACE(fd_count * sizeof(int));
    struct cmsghdr *control = CMSG_FIRSTHDR(&message);
    control->cmsg_level = SOL_SOCKET;
    control->cmsg_type = SCM_RIGHTS;
    control->cmsg_len = CMSG_LEN(fd_count * sizeof(int));
    memcpy(CMSG_DATA(control), fds, fd_count * sizeof(int));
    ssize_t sent = sendmsg(socket_fd, &message, MSG_NOSIGNAL);
    free(frame);
    if (sent != (ssize_t)frame_length) {
        dprintf(STDERR_FILENO, "steamdroid-bwrap-proxy: sendmsg failed: %s\n",
                sent < 0 ? strerror(errno) : "partial write");
        return -EIO;
    }
    return 0;
}

static int receive_runtime_response(int socket_fd, int *status_out,
                                    unsigned char **payload_out, size_t *payload_length_out) {
    struct response_header wire_response;
    if (read_full(socket_fd, &wire_response, sizeof(wire_response)) <= 0) return -EIO;
    struct response_header response;
    response.magic = ntohl(wire_response.magic);
    response.version = ntohs(wire_response.version);
    response.opcode = ntohs(wire_response.opcode);
    response.request_id = ntohl(wire_response.request_id);
    response.status = (int32_t)ntohl((uint32_t)wire_response.status);
    response.payload_length = ntohl(wire_response.payload_length);
    if (response.magic != STEAMDROID_MAGIC || response.version != STEAMDROID_VERSION ||
        response.payload_length > MAX_PAYLOAD) return -EPROTO;
    unsigned char *payload = NULL;
    if (response.payload_length > 0) {
        payload = malloc(response.payload_length);
        if (payload == NULL || read_full(socket_fd, payload, response.payload_length) <= 0) {
            free(payload);
            return -EIO;
        }
    }
    *status_out = response.status;
    *payload_out = payload;
    *payload_length_out = response.payload_length;
    return 0;
}

static int runtime_proxy_main(int argc, char **argv) {
    const char *socket_path = getenv("STEAMDROID_BWRAP_SOCKET");
    const char *real_bwrap = getenv("STEAMDROID_REAL_BWRAP");
    if (argc < 1 || socket_path == NULL || real_bwrap == NULL || real_bwrap[0] != '/' ||
        path_has_parent_component(real_bwrap)) return 125;

    struct runtime_payload_builder builder;
    memset(&builder, 0, sizeof(builder));
    int fds[MAX_RUNTIME_FDS];
    size_t fd_count = 0;
    int socket_fd = connect_runtime_proxy_socket(socket_path);
    if (socket_fd < 0) return 125;
    if (collect_runtime_fds(socket_fd, fds, &fd_count) != 0) {
        dprintf(STDERR_FILENO, "steamdroid-bwrap-proxy: collecting inherited FDs failed\n");
        close(socket_fd);
        return 125;
    }

    if (append_runtime_payload_u32(&builder, RUNTIME_BWRAP_MAGIC) != 0 ||
        append_runtime_payload_u32(&builder, RUNTIME_BWRAP_VERSION) != 0 ||
        append_runtime_payload_u32(&builder, (uint32_t)argc) != 0) {
        close(socket_fd);
        return 125;
    }
    size_t environment_count = 0;
    while (environ[environment_count] != NULL) {
        if (++environment_count > MAX_RUNTIME_ENVIRONMENT) {
            close(socket_fd);
            return 125;
        }
    }
    if (append_runtime_payload_u32(&builder, (uint32_t)environment_count) != 0 ||
        append_runtime_payload_u32(&builder, (uint32_t)fd_count) != 0 ||
        append_runtime_payload_string(&builder, real_bwrap) != 0) {
        close(socket_fd);
        return 125;
    }
    for (int index = 1; index < argc; index++) {
        if (append_runtime_payload_string(&builder, argv[index]) != 0) {
            close(socket_fd);
            return 125;
        }
    }
    for (size_t index = 0; index < environment_count; index++) {
        if (append_runtime_payload_string(&builder, environ[index]) != 0) {
            close(socket_fd);
            return 125;
        }
    }
    for (size_t index = 0; index < fd_count; index++) {
        if (append_runtime_payload_u32(&builder, (uint32_t)fds[index]) != 0) {
            close(socket_fd);
            return 125;
        }
    }
    int status = send_runtime_request(socket_fd, OP_EXEC_RUNTIME_BWRAP,
                                      builder.data, builder.length, fds, fd_count);
    if (status != 0) {
        dprintf(STDERR_FILENO, "steamdroid-bwrap-proxy: EXEC_RUNTIME_BWRAP send failed: %d\n", status);
        close(socket_fd);
        return 125;
    }
    int response_status;
    unsigned char *response_payload = NULL;
    size_t response_length = 0;
    status = receive_runtime_response(socket_fd, &response_status,
                                      &response_payload, &response_length);
    close(socket_fd);
    if (status != 0 || response_status != 0 || response_length != sizeof(uint32_t)) {
        dprintf(STDERR_FILENO,
                "steamdroid-bwrap-proxy: EXEC_RUNTIME_BWRAP response failed transport=%d status=%d length=%zu\n",
                status, response_status, response_length);
        free(response_payload);
        return 125;
    }
    uint32_t network_job_id;
    memcpy(&network_job_id, response_payload, sizeof(network_job_id));
    unsigned int job_id = ntohl(network_job_id);
    free(response_payload);

    for (;;) {
        socket_fd = connect_runtime_proxy_socket(socket_path);
        if (socket_fd < 0) return 125;
        unsigned char status_payload[sizeof(uint32_t)];
        uint32_t network_id = htonl(job_id);
        memcpy(status_payload, &network_id, sizeof(network_id));
        status = send_runtime_request(socket_fd, OP_RUNTIME_BWRAP_STATUS,
                                      status_payload, sizeof(status_payload), NULL, 0);
        if (status == 0) {
            response_payload = NULL;
            response_length = 0;
            status = receive_runtime_response(socket_fd, &response_status,
                                              &response_payload, &response_length);
        }
        close(socket_fd);
        if (status != 0) {
            dprintf(STDERR_FILENO, "steamdroid-bwrap-proxy: status request failed: %d\n", status);
            free(response_payload);
            return 125;
        }
        if (response_status == -EINPROGRESS) {
            free(response_payload);
            usleep(100000);
            continue;
        }
        if (response_status != 0 || response_length != sizeof(uint32_t)) {
            dprintf(STDERR_FILENO,
                    "steamdroid-bwrap-proxy: runtime job failed status=%d length=%zu\n",
                    response_status, response_length);
            free(response_payload);
            return 125;
        }
        uint32_t network_exit_status;
        memcpy(&network_exit_status, response_payload, sizeof(network_exit_status));
        unsigned int exit_status = ntohl(network_exit_status);
        free(response_payload);
        return exit_status > 125 ? 125 : (int)exit_status;
    }
}

static int read_bus_address(int fd, char *address, size_t capacity) {
    if (capacity < 2) return -EINVAL;
    size_t length = 0;
    while (length + 1 < capacity) {
        char byte;
        ssize_t count = read(fd, &byte, 1);
        if (count == 0) break;
        if (count < 0) {
            if (errno == EINTR) continue;
            return -errno;
        }
        if (byte == '\n' || byte == '\r') {
            if (length != 0) break;
            continue;
        }
        address[length++] = byte;
    }
    address[length] = '\0';
    return length > 0 ? 0 : -EIO;
}

static int run_steam_with_private_dbus(char **steam_argv) {
    int address_pipe[2];
    if (pipe(address_pipe) != 0) return -errno;

    pid_t dbus_pid = fork();
    if (dbus_pid < 0) {
        int status = -errno;
        close(address_pipe[0]);
        close(address_pipe[1]);
        return status;
    }
    if (dbus_pid == 0) {
        close(address_pipe[0]);
        if (dup2(address_pipe[1], STDOUT_FILENO) < 0) _exit(126);
        if (address_pipe[1] != STDOUT_FILENO) close(address_pipe[1]);
        prctl(PR_SET_PDEATHSIG, SIGTERM, 0L, 0L, 0L);
        unsetenv("LD_PRELOAD");
        execl("/usr/bin/dbus-daemon", "dbus-daemon", "--session", "--nofork",
              "--print-address=1", (char *)NULL);
        _exit(127);
    }

    close(address_pipe[1]);
    char address[PATH_MAX];
    int status = read_bus_address(address_pipe[0], address, sizeof(address));
    close(address_pipe[0]);
    if (status != 0) {
        kill(dbus_pid, SIGTERM);
        waitpid(dbus_pid, NULL, 0);
        return status;
    }
    if (setenv("DBUS_SESSION_BUS_ADDRESS", address, 1) != 0) {
        status = -errno;
        kill(dbus_pid, SIGTERM);
        waitpid(dbus_pid, NULL, 0);
        return status;
    }
    dprintf(STDERR_FILENO, "steamdroid: private session dbus pid=%d address=%s\n",
            dbus_pid, address);

    pid_t steam_pid = fork();
    if (steam_pid < 0) {
        status = -errno;
        kill(dbus_pid, SIGTERM);
        waitpid(dbus_pid, NULL, 0);
        return status;
    }
    if (steam_pid == 0) {
        prctl(PR_SET_PDEATHSIG, SIGTERM, 0L, 0L, 0L);
        execv(steam_argv[0], steam_argv);
        _exit(errno == ENOENT ? 127 : 126);
    }

    int steam_status;
    do {
        status = waitpid(steam_pid, &steam_status, 0);
    } while (status < 0 && errno == EINTR);
    if (status < 0) steam_status = 126 << 8;

    kill(dbus_pid, SIGTERM);
    while (waitpid(dbus_pid, NULL, 0) < 0 && errno == EINTR) {}
    if (WIFEXITED(steam_status)) return WEXITSTATUS(steam_status);
    if (WIFSIGNALED(steam_status)) return 128 + WTERMSIG(steam_status);
    return 126;
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
    /* The validated ARM launcher uses a session-private /tmp runtime
     * directory. Steam's IPC and helper discovery treat it differently from
     * the conventional /run/user/<uid> path when the client is hosted inside
     * the Holo chroot. */
    snprintf(runtime_directory, sizeof(runtime_directory), "/tmp/steam-runtime");
    if (make_directory_path(runtime_directory) != 0 ||
        chown(runtime_directory, (uid_t)expected_uid, (gid_t)expected_gid) != 0 ||
        chmod(runtime_directory, 0700) != 0 ||
        make_directory_path("/run/steamdroid-tmp") != 0 ||
        chown("/run/steamdroid-tmp", (uid_t)expected_uid, (gid_t)expected_gid) != 0 ||
        chmod("/run/steamdroid-tmp", 0700) != 0 ||
        make_directory_path("/home/steam/.config/cef_user_data") != 0 ||
        chown("/home/steam/.config", (uid_t)expected_uid, (gid_t)expected_gid) != 0 ||
        chown("/home/steam/.config/cef_user_data", (uid_t)expected_uid, (gid_t)expected_gid) != 0 ||
        chmod("/home/steam/.config", 0700) != 0 ||
        chmod("/home/steam/.config/cef_user_data", 0700) != 0 ||
        make_directory_path("/home/steam/.cache/mesa_shader_cache") != 0 ||
        chown("/home/steam/.cache", (uid_t)expected_uid, (gid_t)expected_gid) != 0 ||
        chown("/home/steam/.cache/mesa_shader_cache", (uid_t)expected_uid, (gid_t)expected_gid) != 0 ||
        chmod("/home/steam/.cache", 0700) != 0 ||
        chmod("/home/steam/.cache/mesa_shader_cache", 0700) != 0) _exit(126);

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
        /* Winlator exposes the native X11 server directly.  Do not advertise
         * a synthetic Gamescope/Wayland display: Steam uses the presence of
         * this variable to select a different compositor handoff, while the
         * actual surface is owned by XServerCore. */
        setenv("PULSE_SERVER", "unix:/tmp/.sound/PS0", 1) != 0 ||
        setenv("DBUS_SYSTEM_BUS_ADDRESS", "unix:path=/run/dbus/system_bus_socket", 1) != 0 ||
        setenv("XDG_RUNTIME_DIR", runtime_directory, 1) != 0 ||
        setenv("XDG_CACHE_HOME", "/home/steam/.cache", 1) != 0 ||
        setenv("MESA_SHADER_CACHE_DIR", "/home/steam/.cache/mesa_shader_cache", 1) != 0 ||
        setenv("PATH", "/home/steam/.local/share/Steam/steam-runtime-steamrt-arm64/bin:/home/steam/.local/share/Steam/steam-runtime-steamrt-arm64/steamrt4_platform_4.0.20260805.254769/files/bin:/usr/bin:/bin", 1) != 0 ||
        setenv("LD_LIBRARY_PATH", "/home/steam/.local/share/Steam/steamrtarm64:/home/steam/.local/share/Steam/lib/aarch64-linux-gnu:/home/steam/.local/share/Steam/steamrtarm64/libs:/usr/lib:/home/steam/.local/share/Steam/steam-runtime-steamrt-arm64/steamrt4_platform_4.0.20260805.254769/files/lib/aarch64-linux-gnu:/home/steam/.local/share/Steam/steam-runtime-steamrt-arm64/steamrt4_platform_4.0.20260805.254769/files/lib/aarch64-linux-gnu/pulseaudio:/lib", 1) != 0 ||
        /* Holo's packaged Mesa is built for the msm DRM backend. Thor exposes
         * the Android KGSL device, so select the separately provisioned
         * glibc Turnip/KGSL provider when it is present. The descriptor is
         * inside the immutable Holo session root and is never taken from
         * shared storage or the Android process environment. */
        setenv("VK_DRIVER_FILES", "/opt/steamdroid-kgsl-driver/freedreno-kgsl.icd.json", 1) != 0 ||
        setenv("LIBGL_DRIVERS_PATH", "/usr/lib/dri", 1) != 0 ||
        /* Thor's Holo Mesa package exposes the native Turnip path through
         * kgsl_dri.so. CEF remains software-disabled by its command-line
         * switch, but Steam's own GPU topology probe must see the real ARM
         * provider or the client stops before starting steamwebhelper. */
        setenv("MESA_LOADER_DRIVER_OVERRIDE", "kgsl", 1) != 0 ||
        setenv("TU_DEBUG", "noconform", 1) != 0 ||
        setenv("MESA_VK_WSI_PRESENT_MODE", "mailbox", 1) != 0 ||
        setenv("LIBGL_KOPPER_DISABLE", "true", 1) != 0 ||
        setenv("STEAM_LAUNCH_WRAPPER_SCOPE", "0", 1) != 0 ||
        setenv("STEAM_LAUNCH_WRAPPER_JOURNAL", "0", 1) != 0 ||
        setenv("STEAM_LAUNCH_WRAPPER_AUDIO_NAMESPACE", "0", 1) != 0 ||
        /* The native ARM client needs Holo's XRandR client ABI to resolve its
         * output probe; XServerCore remains the owner of the protocol side.
         * Keep the preload scoped to the Holo ABI and the Android SysV
         * semaphore shim rather than inheriting arbitrary app libraries. */
        setenv("LD_PRELOAD", "/usr/lib/libXrandr.so.2:/home/steam/.local/share/Steam/steamdroid/libsteamdroid_sysv_sem_shim.so", 1) != 0 ||
        /* Chromium's ProcessSingleton creates its private socket directory
         * below TMPDIR. Android app-data filesystems can reject that
         * operation, while the session-private /run tmpfs has normal Unix
         * directory semantics. */
        setenv("TMPDIR", "/run/steamdroid-tmp", 1) != 0 ||
        setenv("XKB_CONFIG_ROOT", "/usr/share/X11/xkb", 1) != 0 ||
        setenv("LANG", "C.UTF-8", 1) != 0 ||
        setenv("LC_ALL", "C.UTF-8", 1) != 0 ||
        setenv("PRESSURE_VESSEL_BWRAP", "/usr/bin/steamdroid-bwrap-proxy", 1) != 0 ||
        setenv("STEAMDROID_BWRAP_SOCKET", "/tmp/steamdroid-runtime-bwrap.sock", 1) != 0) _exit(126);
    /* Steam needs a bwrap implementation before it has downloaded the
     * ARM64 Runtime 4 tool that normally supplies srt-bwrap. Use Holo's
     * native Bubblewrap only for that bootstrap window; after Runtime 4 is
     * present, route the same proxy to its validated srt-bwrap. */
    const char *runtime_bwrap = access(VALIDATED_RUNTIME_BWRAP_PATH, X_OK) == 0
        ? VALIDATED_RUNTIME_BWRAP_PATH : BOOTSTRAP_RUNTIME_BWRAP_PATH;
    if (setenv("STEAMDROID_REAL_BWRAP", runtime_bwrap, 1) != 0) _exit(126);
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
    /*
     * The public native-exec payload keeps the familiar dbus-run-session
     * argv shape, but the actual session bus is created here so the daemon
     * never inherits Steam's compatibility preload. Steam and all of its CEF
     * descendants retain the preload after this wrapper restores the bus
     * address and executes the requested command.
     */
    if (strcmp(argv[0], "/usr/bin/dbus-run-session") == 0 &&
        argv[1] != NULL && strcmp(argv[1], "--") == 0 && argv[2] != NULL) {
        _exit(run_steam_with_private_dbus(&argv[2]));
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

static int install_guest_helper(const char *name) {
    if (holo_root_path == NULL || name == NULL || strchr(name, '/') != NULL) return -EINVAL;

    char target[PATH_MAX];
    int path_length = snprintf(target, sizeof(target), "%s/usr/bin/%s", holo_root_path, name);
    if (path_length <= 0 || (size_t)path_length >= sizeof(target)) return -ENAMETOOLONG;

    int source_fd = open("/proc/self/exe", O_RDONLY | O_CLOEXEC);
    if (source_fd < 0) return -errno;
    int target_fd = open(target, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, 0755);
    if (target_fd < 0) {
        int error = -errno;
        close(source_fd);
        return error;
    }

    unsigned char buffer[64 * 1024];
    int status = 0;
    for (;;) {
        ssize_t count = read(source_fd, buffer, sizeof(buffer));
        if (count == 0) break;
        if (count < 0) {
            if (errno == EINTR) continue;
            status = -errno;
            break;
        }
        if (write_full(target_fd, buffer, (size_t)count) != 0) {
            status = -EIO;
            break;
        }
    }
    if (status == 0 && (fchmod(target_fd, 0755) != 0 || fchown(target_fd, 0, 0) != 0)) {
        status = -errno;
    }
    close(target_fd);
    close(source_fd);
    return status;
}

static int install_guest_helpers(void) {
    struct stat root_stat;
    if (holo_root_path == NULL || stat(holo_root_path, &root_stat) != 0 ||
        !S_ISDIR(root_stat.st_mode)) return -ENOENT;
    int status = install_guest_helper("steamdroid-bwrap-proxy");
    if (status != 0) return status;
    status = install_guest_helper("steamdroid-identity");
    if (status != 0) return status;
    return install_guest_helper("steamdroid-bwrap-fixture");
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

static int bind_socket_mount(const char *source, const char *target) {
    struct stat source_stat;
    if (stat(source, &source_stat) != 0 || !S_ISSOCK(source_stat.st_mode)) return -errno;

    struct stat target_stat;
    if (lstat(target, &target_stat) != 0) {
        if (errno != ENOENT) return -errno;
        int file = open(target, O_WRONLY | O_CREAT | O_CLOEXEC | O_NOFOLLOW, 0600);
        if (file < 0) return -errno;
        close(file);
    } else if (S_ISDIR(target_stat.st_mode)) {
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

static int mount_tmpfs_with_options(const char *target, const char *options) {
    int directory_status = make_directory_path(target);
    if (directory_status != 0) return directory_status;
    if (mount("tmpfs", target, "tmpfs", MS_NOSUID | MS_NODEV | MS_NOEXEC,
              options) != 0) return -errno;
    if (append_mount(target) != 0) {
        umount2(target, MNT_DETACH);
        return -ENOSPC;
    }
    return 0;
}

static int mount_tmpfs(const char *target) {
    return mount_tmpfs_with_options(target, "mode=1777,size=64m");
}

static int wait_for_path(const char *path, pid_t child) {
    for (int attempt = 0; attempt < 100; attempt++) {
        struct stat path_stat;
        if (stat(path, &path_stat) == 0) return 0;
        if (kill(child, 0) != 0 && errno != EPERM) return -errno;
        usleep(50000);
    }
    return -ETIMEDOUT;
}

static int start_private_system_dbus(void) {
    if (holo_root_path == NULL || system_dbus_pid > 0) return 0;

    char socket_path[PATH_MAX];
    int path_length = snprintf(socket_path, sizeof(socket_path),
                                "%s/run/dbus/system_bus_socket", holo_root_path);
    if (path_length <= 0 || (size_t)path_length >= sizeof(socket_path)) return -ENAMETOOLONG;
    char dbus_directory[PATH_MAX];
    path_length = snprintf(dbus_directory, sizeof(dbus_directory), "%s/run/dbus", holo_root_path);
    if (path_length <= 0 || (size_t)path_length >= sizeof(dbus_directory) ||
        make_directory_path(dbus_directory) != 0) return -EIO;

    pid_t child = fork();
    if (child < 0) return -errno;
    if (child == 0) {
        prctl(PR_SET_PDEATHSIG, SIGTERM, 0L, 0L, 0L);
        if (chroot(holo_root_path) != 0 || chdir("/") != 0) _exit(126);
        int log_fd = open("/tmp/steamdroid-dbus-system.log",
                          O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
        if (log_fd >= 0) {
            dup2(log_fd, STDOUT_FILENO);
            dup2(log_fd, STDERR_FILENO);
            close(log_fd);
        }
        int null_fd = open("/dev/null", O_RDWR | O_CLOEXEC);
        if (null_fd < 0 || dup2(null_fd, STDIN_FILENO) < 0) _exit(126);
        if (null_fd != STDIN_FILENO) close(null_fd);
        setenv("PATH", "/usr/bin:/bin", 1);
        setenv("HOME", "/root", 1);
        setenv("DBUS_FATAL_WARNINGS", "0", 1);
        execl("/usr/bin/dbus-daemon", "dbus-daemon", "--system", "--nofork",
              "--nopidfile", (char *)NULL);
        _exit(errno == ENOENT ? 127 : 126);
    }
    system_dbus_pid = child;
    int wait_status = wait_for_path(socket_path, child);
    if (wait_status != 0) {
        kill(child, SIGTERM);
        waitpid(child, NULL, 0);
        system_dbus_pid = -1;
        log_supervisor_message("private system dbus failed status=%d\n", wait_status);
        return wait_status;
    }
    log_supervisor_message("private system dbus pid=%d ready socket=%s\n", child, socket_path);
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
    snprintf(target, sizeof(target), "%s/run", holo_root_path);
    status = mount_tmpfs_with_options(target, "mode=755,size=16m");
    if (status != 0) return status;
    snprintf(target, sizeof(target), "%s/sys", holo_root_path);
    status = bind_mount("/sys", target);
    if (status != 0) return status;

    /*
     * The immutable helper is an Android PIE shipped in the APK.  Its
     * interpreter is /system/bin/linker64 (an APEX symlink), so expose only
     * the Android runtime APEX, linker, and library directory inside Holo;
     * Steam and Proton continue to resolve their own Linux userspace from
     * the Holo root.
     */
    snprintf(target, sizeof(target), "%s/linkerconfig", holo_root_path);
    status = bind_mount("/linkerconfig", target);
    if (status != 0) return status;
    snprintf(target, sizeof(target), "%s/apex/com.android.runtime", holo_root_path);
    status = bind_mount("/apex/com.android.runtime", target);
    if (status != 0) return status;
    snprintf(target, sizeof(target), "%s/system/lib64", holo_root_path);
    status = bind_mount("/system/lib64", target);
    if (status != 0) return status;
    snprintf(target, sizeof(target), "%s/system/bin/linker64", holo_root_path);
    char linker_directory[PATH_MAX];
    int linker_directory_length = snprintf(linker_directory, sizeof(linker_directory),
                                            "%s/system/bin", holo_root_path);
    if (linker_directory_length <= 0 || (size_t)linker_directory_length >= sizeof(linker_directory) ||
        make_directory_path(linker_directory) != 0) return -EIO;
    status = bind_file_mount("/apex/com.android.runtime/bin/linker64", target);
    if (status != 0) return status;

    const char *socket_directories[] = {"tmp/.X11-unix", "tmp/.sound", "tmp/.sysvshm"};
    for (size_t index = 0; index < sizeof(socket_directories) / sizeof(socket_directories[0]); index++) {
        snprintf(source, sizeof(source), "%s/%s", substrate_root_path, socket_directories[index]);
        snprintf(target, sizeof(target), "%s/%s", holo_root_path, socket_directories[index]);
        status = bind_mount(source, target);
        if (status != 0) return status;
    }

    snprintf(target, sizeof(target), "%s/tmp/steamdroid-runtime-bwrap.sock", holo_root_path);
    status = bind_socket_mount(guest_socket_path, target);
    if (status != 0) return status;

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
        int owns_path = 0;
        if (!abstract_namespace) {
            struct stat endpoint_stat;
            struct stat path_stat;
            if (fstat(server_fd, &endpoint_stat) == 0 && lstat(socket_path, &path_stat) == 0 &&
                endpoint_stat.st_dev == path_stat.st_dev && endpoint_stat.st_ino == path_stat.st_ino) {
                owns_path = 1;
            }
        }
        close(server_fd);
        if (owns_path) unlink(socket_path);
        return -error;
    }
    return server_fd;
}

static void close_endpoint(int *server_fd, const char *socket_path, int abstract_namespace) {
    int owns_path = 0;
    if (!abstract_namespace && *server_fd >= 0 && socket_path != NULL) {
        struct stat endpoint_stat;
        struct stat path_stat;
        if (fstat(*server_fd, &endpoint_stat) == 0 && lstat(socket_path, &path_stat) == 0 &&
            endpoint_stat.st_dev == path_stat.st_dev && endpoint_stat.st_ino == path_stat.st_ino) {
            owns_path = 1;
        }
    }
    if (*server_fd >= 0) {
        close(*server_fd);
        *server_fd = -1;
    }
    if (owns_path) unlink(socket_path);
}

static int activate_guest_endpoint(void) {
    if (guest_server_fd < 0 || guest_socket_path == NULL) return -EINVAL;
    guest_endpoint_ready = 1;
    return 0;
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
                           const unsigned char *payload, int guest_endpoint,
                           const int *received_fds, size_t received_fd_count) {
    char response[512];
    int status = 0;
    size_t response_length = 0;

    // The Android control socket and the guest proxy socket are separate
    // capabilities.  The guest side must never gain access to lifecycle,
    // uinput, or namespace preparation operations, even if a guest process
    // can reach the proxy path.
    if (guest_endpoint && request->opcode != OP_GET_SESSION_STATUS &&
        request->opcode != OP_EXEC_RUNTIME_BWRAP &&
        request->opcode != OP_RUNTIME_BWRAP_STATUS) {
        status = -EPERM;
    }
    if (!guest_endpoint && (request->opcode == OP_EXEC_RUNTIME_BWRAP ||
                            request->opcode == OP_RUNTIME_BWRAP_STATUS)) {
        status = -EPERM;
    }

    if (status != 0) return send_response(client_fd, request, status, NULL, 0);

    switch (request->opcode) {
        case OP_GET_CONTROL_STATUS:
            response_length = snprintf(response, sizeof(response),
                                       "state=%s\nsupervisor_pid=%d\nuid=%d\nguest_proxy=%s\n",
                                       prepared ? "prepared" : "ready", getpid(), getuid(),
                                       guest_endpoint_ready ? "active" : "inactive");
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
            if (!guest_endpoint || !prepared) {
                status = -EPERM;
                break;
            }
            status = execute_runtime_bwrap(payload, request->payload_length,
                                           received_fds, received_fd_count,
                                           response, sizeof(response), &response_length);
            log_supervisor_message("runtime bwrap request status=%d fds=%zu payload=%u\n",
                                   status, received_fd_count, request->payload_length);
            break;
        case OP_RUNTIME_BWRAP_STATUS: {
            if (!guest_endpoint || request->payload_length != sizeof(uint32_t)) {
                status = -EINVAL;
                break;
            }
            uint32_t network_job_id;
            memcpy(&network_job_id, payload, sizeof(network_job_id));
            unsigned int requested_job_id = ntohl(network_job_id);
            reap_children();
            if (requested_job_id != runtime_job_id || runtime_job_id == 0) {
                status = -ENOENT;
            } else if (!runtime_job_complete) {
                status = -EINPROGRESS;
            } else {
                uint32_t network_exit_status = htonl((uint32_t)runtime_job_status);
                memcpy(response, &network_exit_status, sizeof(network_exit_status));
                response_length = sizeof(network_exit_status);
            }
            log_supervisor_message("runtime bwrap status request job=%u status=%d complete=%d\n",
                                   requested_job_id, status, runtime_job_complete);
            break;
        }
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
        int response_status = process_request(client_fd, &request, payload, guest_endpoint, NULL, 0);
        free(payload);
        if (response_status != 0 || request.opcode == OP_DESTROY_SESSION) break;
    }
    close(client_fd);
}

static void serve_guest_client(int client_fd) {
    struct timeval receive_timeout = {1, 0};
    setsockopt(client_fd, SOL_SOCKET, SO_RCVTIMEO, &receive_timeout, sizeof(receive_timeout));
    struct ucred credentials;
    socklen_t credentials_length = sizeof(credentials);
    if (getsockopt(client_fd, SOL_SOCKET, SO_PEERCRED, &credentials, &credentials_length) != 0 ||
        (expected_uid >= 0 && (int)credentials.uid != expected_uid)) {
        close(client_fd);
        return;
    }

    unsigned char header_bytes[sizeof(struct request_header)];
    int received_fds[MAX_RUNTIME_FDS];
    size_t received_fd_count = 0;
    char control_buffer[CMSG_SPACE(MAX_RUNTIME_FDS * sizeof(int))];
    struct iovec vector = {header_bytes, sizeof(header_bytes)};
    struct msghdr message;
    memset(&message, 0, sizeof(message));
    message.msg_iov = &vector;
    message.msg_iovlen = 1;
    message.msg_control = control_buffer;
    message.msg_controllen = sizeof(control_buffer);
    ssize_t header_length = recvmsg(client_fd, &message, MSG_WAITALL);
    if (header_length != (ssize_t)sizeof(header_bytes) || (message.msg_flags & MSG_CTRUNC)) {
        close(client_fd);
        return;
    }
    for (struct cmsghdr *header = CMSG_FIRSTHDR(&message); header != NULL;
         header = CMSG_NXTHDR(&message, header)) {
        if (header->cmsg_level != SOL_SOCKET || header->cmsg_type != SCM_RIGHTS) continue;
        size_t count = (header->cmsg_len - CMSG_LEN(0)) / sizeof(int);
        if (received_fd_count + count > MAX_RUNTIME_FDS) {
            close(client_fd);
            return;
        }
        memcpy(received_fds + received_fd_count, CMSG_DATA(header), count * sizeof(int));
        received_fd_count += count;
    }

    struct request_header wire_request;
    memcpy(&wire_request, header_bytes, sizeof(wire_request));
    struct request_header request;
    request.magic = ntohl(wire_request.magic);
    request.version = ntohs(wire_request.version);
    request.opcode = ntohs(wire_request.opcode);
    request.request_id = ntohl(wire_request.request_id);
    request.payload_length = ntohl(wire_request.payload_length);
    if (request.magic != STEAMDROID_MAGIC || request.version != STEAMDROID_VERSION ||
        request.payload_length > MAX_PAYLOAD ||
        (request.opcode != OP_EXEC_RUNTIME_BWRAP && request.opcode != OP_RUNTIME_BWRAP_STATUS)) {
        for (size_t index = 0; index < received_fd_count; index++) close(received_fds[index]);
        close(client_fd);
        return;
    }

    unsigned char *payload = NULL;
    if (request.payload_length > 0) {
        payload = malloc(request.payload_length);
        if (payload == NULL || read_full(client_fd, payload, request.payload_length) <= 0) {
            free(payload);
            for (size_t index = 0; index < received_fd_count; index++) close(received_fds[index]);
            close(client_fd);
            return;
        }
    }
    int response_status = process_request(client_fd, &request, payload, 1,
                                          received_fds, received_fd_count);
    (void)response_status;
    free(payload);
    for (size_t index = 0; index < received_fd_count; index++) close(received_fds[index]);
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
    // Create the pathname endpoint before mount-namespace privatization so
    // the inode remains visible to the Android service and to the later Holo
    // chroot. Its listening FD is not polled until PREPARE_SESSION succeeds.
    guest_server_fd = open_endpoint(guest_socket_path, 0);
    if (guest_server_fd < 0) {
        close_endpoint(&control_fd, socket_path, control_abstract);
        return 11;
    }

    while (!stop_requested) {
        reap_children();
        struct pollfd poll_fds[2];
        nfds_t poll_count = 1;
        poll_fds[0].fd = control_fd;
        poll_fds[0].events = POLLIN;
        poll_fds[0].revents = 0;
        if (guest_endpoint_ready && guest_server_fd >= 0) {
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
        if (guest_endpoint_ready && guest_server_fd >= 0 && poll_count == 2 &&
            (poll_fds[1].revents & POLLIN)) {
            int client_fd = accept4(guest_server_fd, NULL, NULL, SOCK_CLOEXEC);
            if (client_fd >= 0) serve_guest_client(client_fd);
        }
        reap_children();
    }

    terminate_descendants();
    guest_endpoint_ready = 0;
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
    const char *program_name = strrchr(argv[0], '/');
    program_name = program_name != NULL ? program_name + 1 : argv[0];
    if (strcmp(program_name, "steamdroid-bwrap-proxy") == 0) {
        return runtime_proxy_main(argc, argv);
    }
    if (strcmp(program_name, "steamdroid-bwrap-fixture") == 0) {
        return runtime_bwrap_fixture_main(argc, argv);
    }
    if (strcmp(program_name, "steamdroid-identity") == 0 && argc >= 2 &&
        strcmp(argv[1], "--identity-trampoline") == 0) {
        return identity_trampoline_main(argc, argv);
    }

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
