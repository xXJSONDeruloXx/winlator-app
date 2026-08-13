#define _GNU_SOURCE

/*
 * Android's kernel configuration does not expose the System V semaphore
 * syscalls used by Steam's tier0 named-event implementation. This preload
 * implements the one-semaphore subset Steam uses on top of POSIX named
 * semaphores in the session-local /dev/shm mount.
 *
 * It is deliberately scoped to the Steam process tree through LD_PRELOAD.
 * It is not a general System V IPC implementation and must not be loaded
 * into unrelated Android or Holo processes.
 */

#include <errno.h>
#include <fcntl.h>
#include <semaphore.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ipc.h>
#include <sys/sem.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

static int trace_enabled(void) {
    const char *value = getenv("STEAMDROID_SYSV_SEM_TRACE");
    return value != NULL && value[0] == '1';
}

static void trace_operation(const char *operation, int result, int error_number) {
    if (!trace_enabled()) return;
    char line[256];
    int length = snprintf(line, sizeof(line),
                          "steamdroid_sysv_sem uid=%d %s result=%d errno=%d error=%s\n",
                          (int)getuid(), operation, result, error_number,
                          strerror(error_number));
    if (length > 0) {
        if (length >= (int)sizeof(line)) length = (int)sizeof(line) - 1;
        (void)write(STDERR_FILENO, line, (size_t)length);
    }
}

static int make_id(key_t key) {
    int id = (int)(0x40000000u | ((uint32_t)key & 0x3fffffffu));
    return id == 0x40000000 ? 0x40000001 : id;
}

static int semaphore_name(int id, char *buffer, size_t length) {
    int written = snprintf(buffer, length, "/steamdroid-sysv-%08x",
                           (unsigned int)id);
    if (written < 0 || (size_t)written >= length) {
        errno = ENAMETOOLONG;
        return -1;
    }
    return 0;
}

static sem_t *open_id(int id, char *name, size_t name_length) {
    if (semaphore_name(id, name, name_length) < 0) return SEM_FAILED;
    return sem_open(name, 0);
}

static void relax_backing_permissions(const char *name) {
    char path[128];
    int written = snprintf(path, sizeof(path), "/dev/shm/sem.%s", name + 1);
    if (written >= 0 && (size_t)written < sizeof(path)) (void)chmod(path, 0666);
}

static int adjust_value(sem_t *semaphore, int target) {
    if (target < 0) {
        errno = EINVAL;
        return -1;
    }
    for (;;) {
        int current = 0;
        if (sem_getvalue(semaphore, &current) < 0) return -1;
        if (current == target) return 0;
        if (current < target) {
            if (sem_post(semaphore) < 0) return -1;
        }
        else if (sem_trywait(semaphore) < 0 && errno != EINTR) {
            return -1;
        }
    }
}

static int wait_zero(sem_t *semaphore, int nowait) {
    for (;;) {
        int current = 0;
        if (sem_getvalue(semaphore, &current) < 0) return -1;
        if (current == 0) return 0;
        if (nowait) {
            errno = EAGAIN;
            return -1;
        }
        struct timespec delay = {.tv_sec = 0, .tv_nsec = 1000000};
        if (nanosleep(&delay, NULL) < 0 && errno != EINTR) return -1;
    }
}

int semget(key_t key, int count, int flags) {
    char operation[96];
    (void)snprintf(operation, sizeof(operation), "semget key=%d flags=0x%x",
                   (int)key, flags);
    if (count != 1) {
        errno = EINVAL;
        trace_operation(operation, -1, errno);
        return -1;
    }

    char name[64];
    int id = make_id(key);
    if (semaphore_name(id, name, sizeof(name)) < 0) {
        trace_operation(operation, -1, errno);
        return -1;
    }
    int open_flags = 0;
    if (flags & IPC_CREAT) open_flags |= O_CREAT;
    if (flags & IPC_EXCL) open_flags |= O_EXCL;
    sem_t *semaphore = sem_open(name, open_flags, flags & 0777, 0);
    if (semaphore == SEM_FAILED) {
        int error_number = errno;
        trace_operation(operation, -1, error_number);
        errno = error_number;
        return -1;
    }
    relax_backing_permissions(name);
    sem_close(semaphore);
    errno = 0;
    trace_operation(operation, id, 0);
    return id;
}

int semctl(int id, int semnum, int command, ...) {
    if (semnum != 0) {
        errno = EINVAL;
        trace_operation("semctl", -1, errno);
        return -1;
    }

    char name[64];
    if (semaphore_name(id, name, sizeof(name)) < 0) {
        trace_operation("semctl", -1, errno);
        return -1;
    }
    if (command == IPC_RMID) {
        sem_t *semaphore = sem_open(name, 0);
        if (semaphore == SEM_FAILED) {
            int error_number = errno;
            trace_operation("semctl_rmid", -1, error_number);
            errno = error_number;
            return -1;
        }
        int result = sem_unlink(name);
        int error_number = errno;
        sem_close(semaphore);
        trace_operation("semctl_rmid", result, error_number);
        errno = error_number;
        return result;
    }

    sem_t *semaphore = sem_open(name, 0);
    if (semaphore == SEM_FAILED) {
        int error_number = errno;
        trace_operation("semctl", -1, error_number);
        errno = error_number;
        return -1;
    }

    int result = 0;
    errno = 0;
    if (command == SETVAL) {
        va_list arguments;
        va_start(arguments, command);
        unsigned long value = va_arg(arguments, unsigned long);
        va_end(arguments);
        result = adjust_value(semaphore, (int)value);
    }
    else if (command == GETVAL) {
        if (sem_getvalue(semaphore, &result) < 0) result = -1;
    }
    else if (command == GETPID) {
        result = (int)getpid();
    }
    else if (command == GETNCNT || command == GETZCNT ||
             command == IPC_STAT || command == IPC_SET) {
        result = 0;
    }
    else {
        errno = EINVAL;
        result = -1;
    }
    int error_number = errno;
    sem_close(semaphore);
    trace_operation("semctl", result, error_number);
    errno = error_number;
    return result;
}

int semop(int id, struct sembuf *operations, size_t count) {
    if (operations == NULL || count == 0) {
        errno = EINVAL;
        trace_operation("semop", -1, errno);
        return -1;
    }
    char name[64];
    sem_t *semaphore = open_id(id, name, sizeof(name));
    if (semaphore == SEM_FAILED) {
        int error_number = errno;
        trace_operation("semop", -1, error_number);
        errno = error_number;
        return -1;
    }

    int result = 0;
    errno = 0;
    for (size_t index = 0; index < count; index++) {
        struct sembuf operation = operations[index];
        int nowait = (operation.sem_flg & IPC_NOWAIT) != 0;
        if (operation.sem_op > 0) {
            for (int repeat = 0; repeat < operation.sem_op; repeat++) {
                if (sem_post(semaphore) < 0) {
                    result = -1;
                    break;
                }
            }
        }
        else if (operation.sem_op < 0) {
            for (int repeat = 0; repeat < -operation.sem_op; repeat++) {
                int wait_result = nowait ? sem_trywait(semaphore) : sem_wait(semaphore);
                if (wait_result < 0) {
                    result = -1;
                    break;
                }
            }
        }
        else {
            result = wait_zero(semaphore, nowait);
        }
        if (result < 0) break;
    }
    int error_number = errno;
    sem_close(semaphore);
    trace_operation("semop", result, error_number);
    errno = error_number;
    return result;
}
