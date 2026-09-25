#ifndef CPLUS_THREAD_POOL_CP
#define CPLUS_THREAD_POOL_CP

#include <stddef.h>
#include <string.h>

comptime {
    @if (os == "linux") {
        comptime flags -lpthread;
    }
}

#ifndef CPLUS_THREAD_POOL_MAX_TASKS
#define CPLUS_THREAD_POOL_MAX_TASKS 64
#endif

#if CPLUS_THREAD_POOL_MAX_TASKS < 1
#error CPLUS_THREAD_POOL_MAX_TASKS must be at least 1
#endif

#define CPLUS_THREAD_POOL_MAX_WORKERS 2

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
typedef SRWLOCK thread_pool_mutex_t;
typedef CONDITION_VARIABLE thread_pool_condition_t;
typedef HANDLE thread_pool_native_thread_t;
#else
#include <pthread.h>
typedef pthread_mutex_t thread_pool_mutex_t;
typedef pthread_cond_t thread_pool_condition_t;
typedef pthread_t thread_pool_native_thread_t;
#endif

typedef enum thread_step_action_t {
    THREAD_STEP_YIELD = 0,
    THREAD_STEP_DONE = 2,
    THREAD_STEP_FAILED = 3
} thread_step_action_t;

typedef struct thread_step_result_t {
    thread_step_action_t action;
    int error_code;
} thread_step_result_t;

typedef enum thread_task_state_t {
    THREAD_TASK_IDLE = 0,
    THREAD_TASK_QUEUED = 1,
    THREAD_TASK_RUNNING = 2,
    THREAD_TASK_COMPLETED = 4,
    THREAD_TASK_FAILED = 5,
    THREAD_TASK_CANCELLED = 6
} thread_task_state_t;

typedef enum thread_pool_state_t {
    THREAD_POOL_STOPPED = 0,
    THREAD_POOL_RUNNING = 1,
    THREAD_POOL_STOPPING = 2
} thread_pool_state_t;

typedef enum thread_pool_error_t {
    THREAD_POOL_OK = 0,
    THREAD_POOL_INVALID_ARGUMENT = 1,
    THREAD_POOL_INVALID_STATE = 2,
    THREAD_POOL_FULL = 3,
    THREAD_POOL_NOT_FOUND = 4,
    THREAD_POOL_SYSTEM_ERROR = 5,
    THREAD_POOL_INVALID_STEP_RESULT = 6
} thread_pool_error_t;

struct thread_task_t;
struct thread_pool_t;
typedef thread_step_result_t (*thread_task_step_fn_t)(struct thread_task_t* task, void* context);

typedef struct thread_task_t {
    thread_task_step_fn_t step;
    void* context;
    struct thread_pool_t* owner;
    thread_task_state_t state;
    size_t step_count;
    int error_code;
    int cancel_requested;

    static pub void init(borrowed mut thread_task_t* self, thread_task_step_fn_t step, void* context) {
        if (self == NULL) return;
        self->step = step;
        self->context = context;
        self->owner = NULL;
        self->state = THREAD_TASK_IDLE;
        self->step_count = 0;
        self->error_code = 0;
        self->cancel_requested = 0;
    }

    static pub thread_step_result_t yield(void) {
        thread_step_result_t result = { THREAD_STEP_YIELD, 0 };
        return result;
    }

    static pub thread_step_result_t done(void) {
        thread_step_result_t result = { THREAD_STEP_DONE, 0 };
        return result;
    }

    static pub thread_step_result_t failed(int error_code) {
        thread_step_result_t result = { THREAD_STEP_FAILED, error_code };
        return result;
    }
} thread_task_t;

#if defined(_WIN32)
static DWORD WINAPI cplus_thread_pool_worker_entry(LPVOID argument);
#else
static void* cplus_thread_pool_worker_entry(void* argument);
#endif

typedef struct thread_pool_t {
    thread_pool_mutex_t mutex;
    thread_pool_condition_t work_condition;
    thread_pool_condition_t state_condition;
    thread_pool_native_thread_t workers[CPLUS_THREAD_POOL_MAX_WORKERS];
    int worker_joined[CPLUS_THREAD_POOL_MAX_WORKERS];
    thread_task_t* active[CPLUS_THREAD_POOL_MAX_TASKS];
    thread_task_t* ready[CPLUS_THREAD_POOL_MAX_TASKS];
    size_t active_count;
    size_t ready_head;
    size_t ready_tail;
    size_t ready_count;
    size_t worker_count;
    int state;
    int synchronization_ready;

    static priv int mutex_init(thread_pool_mutex_t* mutex) {
#if defined(_WIN32)
        InitializeSRWLock(mutex);
        return 1;
#else
        return pthread_mutex_init(mutex, NULL) == 0;
#endif
    }

    static priv int mutex_destroy(thread_pool_mutex_t* mutex) {
#if defined(_WIN32)
        (void)mutex;
        return 1;
#else
        return pthread_mutex_destroy(mutex) == 0;
#endif
    }

    static priv int mutex_lock(thread_pool_mutex_t* mutex) {
#if defined(_WIN32)
        AcquireSRWLockExclusive(mutex);
        return 1;
#else
        return pthread_mutex_lock(mutex) == 0;
#endif
    }

    static priv int mutex_unlock(thread_pool_mutex_t* mutex) {
#if defined(_WIN32)
        ReleaseSRWLockExclusive(mutex);
        return 1;
#else
        return pthread_mutex_unlock(mutex) == 0;
#endif
    }

    static priv int condition_init(thread_pool_condition_t* condition) {
#if defined(_WIN32)
        InitializeConditionVariable(condition);
        return 1;
#else
        return pthread_cond_init(condition, NULL) == 0;
#endif
    }

    static priv int condition_destroy(thread_pool_condition_t* condition) {
#if defined(_WIN32)
        (void)condition;
        return 1;
#else
        return pthread_cond_destroy(condition) == 0;
#endif
    }

    static priv int condition_wait(thread_pool_condition_t* condition, thread_pool_mutex_t* mutex) {
#if defined(_WIN32)
        return SleepConditionVariableSRW(condition, mutex, INFINITE, 0) != 0;
#else
        return pthread_cond_wait(condition, mutex) == 0;
#endif
    }

    static priv void condition_signal(thread_pool_condition_t* condition) {
#if defined(_WIN32)
        WakeConditionVariable(condition);
#else
        pthread_cond_signal(condition);
#endif
    }

    static priv void condition_broadcast(thread_pool_condition_t* condition) {
#if defined(_WIN32)
        WakeAllConditionVariable(condition);
#else
        pthread_cond_broadcast(condition);
#endif
    }

    static priv int create_worker(thread_pool_t* self, size_t index) {
#if defined(_WIN32)
        self->workers[index] = CreateThread(NULL, 0, cplus_thread_pool_worker_entry, self, 0, NULL);
        return self->workers[index] != NULL;
#else
        return pthread_create(&self->workers[index], NULL, cplus_thread_pool_worker_entry, self) == 0;
#endif
    }

    static priv int join_worker(thread_pool_native_thread_t worker) {
#if defined(_WIN32)
        DWORD result = WaitForSingleObject(worker, INFINITE);
        if (result != WAIT_OBJECT_0) return 0;
        return CloseHandle(worker) != 0;
#else
        return pthread_join(worker, NULL) == 0;
#endif
    }

    static priv int enqueue_locked(thread_pool_t* self, thread_task_t* task) {
        if (self->ready_count >= CPLUS_THREAD_POOL_MAX_TASKS) return 0;
        self->ready[self->ready_tail] = task;
        self->ready_tail = (self->ready_tail + 1) % CPLUS_THREAD_POOL_MAX_TASKS;
        self->ready_count++;
        task->state = THREAD_TASK_QUEUED;
        return 1;
    }

    static priv thread_task_t* dequeue_locked(thread_pool_t* self) {
        if (self->ready_count == 0) return NULL;
        thread_task_t* task = self->ready[self->ready_head];
        self->ready[self->ready_head] = NULL;
        self->ready_head = (self->ready_head + 1) % CPLUS_THREAD_POOL_MAX_TASKS;
        self->ready_count--;
        return task;
    }

    static priv int remove_ready_locked(thread_pool_t* self, thread_task_t* task) {
        size_t original_count = self->ready_count;
        size_t kept_count = 0;
        for (size_t index = 0; index < original_count; index++) {
            size_t slot = (self->ready_head + index) % CPLUS_THREAD_POOL_MAX_TASKS;
            thread_task_t* item = self->ready[slot];
            if (item != task) {
                size_t target = (self->ready_head + kept_count) % CPLUS_THREAD_POOL_MAX_TASKS;
                self->ready[target] = item;
                kept_count++;
            }
        }
        for (size_t index = kept_count; index < original_count; index++) {
            self->ready[(self->ready_head + index) % CPLUS_THREAD_POOL_MAX_TASKS] = NULL;
        }
        self->ready_count = kept_count;
        self->ready_tail = (self->ready_head + kept_count) % CPLUS_THREAD_POOL_MAX_TASKS;
        return kept_count != original_count;
    }

    static priv int find_active_locked(thread_pool_t* self, thread_task_t* task) {
        for (size_t index = 0; index < self->active_count; index++) {
            if (self->active[index] == task) return (int)index;
        }
        return -1;
    }

    static priv int remove_active_locked(thread_pool_t* self, thread_task_t* task) {
        int found = thread_pool_t.find_active_locked(self, task);
        if (found < 0) return 0;
        for (size_t index = (size_t)found + 1; index < self->active_count; index++) {
            self->active[index - 1] = self->active[index];
        }
        self->active[--self->active_count] = NULL;
        return 1;
    }

    static priv int is_terminal(thread_task_state_t state) {
        return state == THREAD_TASK_COMPLETED || state == THREAD_TASK_FAILED || state == THREAD_TASK_CANCELLED;
    }

    static priv void stop_locked(thread_pool_t* self) {
        self->state = THREAD_POOL_STOPPING;
        size_t kept_count = 0;
        for (size_t index = 0; index < self->active_count; index++) {
            thread_task_t* task = self->active[index];
            if (task->state == THREAD_TASK_RUNNING) {
                task->cancel_requested = 1;
                self->active[kept_count++] = task;
            } else {
                task->state = THREAD_TASK_CANCELLED;
            }
        }
        for (size_t index = kept_count; index < self->active_count; index++) self->active[index] = NULL;
        self->active_count = kept_count;
        self->ready_head = 0;
        self->ready_tail = 0;
        self->ready_count = 0;
        for (size_t index = 0; index < CPLUS_THREAD_POOL_MAX_TASKS; index++) self->ready[index] = NULL;
        thread_pool_t.condition_broadcast(&self->work_condition);
        thread_pool_t.condition_broadcast(&self->state_condition);
    }

    static priv void worker(thread_pool_t* self) {
        for (;;) {
            if (!thread_pool_t.mutex_lock(&self->mutex)) return;
            while (self->ready_count == 0 && self->state == THREAD_POOL_RUNNING) {
                if (!thread_pool_t.condition_wait(&self->work_condition, &self->mutex)) {
                    thread_pool_t.stop_locked(self);
                    thread_pool_t.mutex_unlock(&self->mutex);
                    return;
                }
            }
            if (self->state != THREAD_POOL_RUNNING) {
                thread_pool_t.mutex_unlock(&self->mutex);
                return;
            }

            thread_task_t* task = thread_pool_t.dequeue_locked(self);
            if (task == NULL) {
                thread_pool_t.mutex_unlock(&self->mutex);
                continue;
            }
            task->state = THREAD_TASK_RUNNING;
            thread_pool_t.mutex_unlock(&self->mutex);

            thread_task_step_fn_t callback = task->step;
            thread_step_result_t result = callback(task, task->context);

            if (!thread_pool_t.mutex_lock(&self->mutex)) return;
            task->step_count++;
            if (self->state == THREAD_POOL_STOPPING || task->cancel_requested) {
                task->state = THREAD_TASK_CANCELLED;
                thread_pool_t.remove_active_locked(self, task);
            } else if (result.action == THREAD_STEP_YIELD) {
                if (!thread_pool_t.enqueue_locked(self, task)) {
                    task->state = THREAD_TASK_FAILED;
                    task->error_code = THREAD_POOL_FULL;
                    thread_pool_t.remove_active_locked(self, task);
                } else {
                    thread_pool_t.condition_signal(&self->work_condition);
                }
            } else if (result.action == THREAD_STEP_DONE) {
                task->state = THREAD_TASK_COMPLETED;
                task->error_code = result.error_code;
                thread_pool_t.remove_active_locked(self, task);
            } else if (result.action == THREAD_STEP_FAILED) {
                task->state = THREAD_TASK_FAILED;
                task->error_code = result.error_code;
                thread_pool_t.remove_active_locked(self, task);
            } else {
                task->state = THREAD_TASK_FAILED;
                task->error_code = THREAD_POOL_INVALID_STEP_RESULT;
                thread_pool_t.remove_active_locked(self, task);
            }
            thread_pool_t.condition_broadcast(&self->state_condition);
            thread_pool_t.mutex_unlock(&self->mutex);
        }
    }

    pub int init(borrowed mut *self, size_t worker_count) {
        if (self == NULL || worker_count < 1 || worker_count > CPLUS_THREAD_POOL_MAX_WORKERS) {
            return THREAD_POOL_INVALID_ARGUMENT;
        }
        memset(self, 0, sizeof(*self));
        if (!thread_pool_t.mutex_init(&self->mutex)) return THREAD_POOL_SYSTEM_ERROR;
        if (!thread_pool_t.condition_init(&self->work_condition)) {
            thread_pool_t.mutex_destroy(&self->mutex);
            return THREAD_POOL_SYSTEM_ERROR;
        }
        if (!thread_pool_t.condition_init(&self->state_condition)) {
            thread_pool_t.condition_destroy(&self->work_condition);
            thread_pool_t.mutex_destroy(&self->mutex);
            return THREAD_POOL_SYSTEM_ERROR;
        }
        self->synchronization_ready = 1;
        self->state = THREAD_POOL_RUNNING;

        for (size_t index = 0; index < worker_count; index++) {
            if (!thread_pool_t.create_worker(self, index)) {
                thread_pool_t.mutex_lock(&self->mutex);
                self->state = THREAD_POOL_STOPPING;
                thread_pool_t.condition_broadcast(&self->work_condition);
                thread_pool_t.condition_broadcast(&self->state_condition);
                thread_pool_t.mutex_unlock(&self->mutex);
                for (size_t started = 0; started < self->worker_count; started++) {
                    thread_pool_t.join_worker(self->workers[started]);
                }
                thread_pool_t.condition_destroy(&self->state_condition);
                thread_pool_t.condition_destroy(&self->work_condition);
                thread_pool_t.mutex_destroy(&self->mutex);
                memset(self, 0, sizeof(*self));
                return THREAD_POOL_SYSTEM_ERROR;
            }
            self->worker_count++;
        }
        return THREAD_POOL_OK;
    }

    pub int submit(borrowed mut *self, borrowed mut thread_task_t* task) {
        if (self == NULL || task == NULL || task->step == NULL) return THREAD_POOL_INVALID_ARGUMENT;
        if (!self->synchronization_ready) return THREAD_POOL_INVALID_STATE;
        if (!thread_pool_t.mutex_lock(&self->mutex)) return THREAD_POOL_SYSTEM_ERROR;
        if (self->state != THREAD_POOL_RUNNING || task->state != THREAD_TASK_IDLE || task->owner != NULL) {
            thread_pool_t.mutex_unlock(&self->mutex);
            return THREAD_POOL_INVALID_STATE;
        }
        if (self->active_count >= CPLUS_THREAD_POOL_MAX_TASKS) {
            thread_pool_t.mutex_unlock(&self->mutex);
            return THREAD_POOL_FULL;
        }
        self->active[self->active_count++] = task;
        task->owner = self;
        task->step_count = 0;
        task->error_code = 0;
        task->cancel_requested = 0;
        if (!thread_pool_t.enqueue_locked(self, task)) {
            self->active[--self->active_count] = NULL;
            task->owner = NULL;
            task->state = THREAD_TASK_IDLE;
            thread_pool_t.mutex_unlock(&self->mutex);
            return THREAD_POOL_FULL;
        }
        thread_pool_t.condition_signal(&self->work_condition);
        thread_pool_t.mutex_unlock(&self->mutex);
        return THREAD_POOL_OK;
    }

    pub int cancel(borrowed mut *self, borrowed mut thread_task_t* task) {
        if (self == NULL || task == NULL || !self->synchronization_ready) return THREAD_POOL_INVALID_ARGUMENT;
        if (!thread_pool_t.mutex_lock(&self->mutex)) return THREAD_POOL_SYSTEM_ERROR;
        if (task->owner != self || thread_pool_t.find_active_locked(self, task) < 0) {
            thread_pool_t.mutex_unlock(&self->mutex);
            return THREAD_POOL_NOT_FOUND;
        }
        if (task->state == THREAD_TASK_RUNNING) {
            task->cancel_requested = 1;
        } else {
            if (task->state == THREAD_TASK_QUEUED) thread_pool_t.remove_ready_locked(self, task);
            task->state = THREAD_TASK_CANCELLED;
            thread_pool_t.remove_active_locked(self, task);
            thread_pool_t.condition_broadcast(&self->state_condition);
        }
        thread_pool_t.mutex_unlock(&self->mutex);
        return THREAD_POOL_OK;
    }

    pub int status(
        borrowed mut *self,
        borrowed thread_task_t* task,
        borrowed mut thread_task_state_t* state,
        borrowed mut size_t* step_count,
        borrowed mut int* error_code
    ) {
        if (self == NULL || task == NULL || state == NULL || !self->synchronization_ready) return THREAD_POOL_INVALID_ARGUMENT;
        if (!thread_pool_t.mutex_lock(&self->mutex)) return THREAD_POOL_SYSTEM_ERROR;
        if (task->owner != self) {
            thread_pool_t.mutex_unlock(&self->mutex);
            return THREAD_POOL_NOT_FOUND;
        }
        *state = task->state;
        if (step_count != NULL) *step_count = task->step_count;
        if (error_code != NULL) *error_code = task->error_code;
        thread_pool_t.mutex_unlock(&self->mutex);
        return THREAD_POOL_OK;
    }

    pub int wait(borrowed mut *self, borrowed mut thread_task_t* task) {
        if (self == NULL || task == NULL || !self->synchronization_ready) return THREAD_POOL_INVALID_ARGUMENT;
        if (!thread_pool_t.mutex_lock(&self->mutex)) return THREAD_POOL_SYSTEM_ERROR;
        if (task->state == THREAD_TASK_IDLE) {
            thread_pool_t.mutex_unlock(&self->mutex);
            return THREAD_POOL_INVALID_STATE;
        }
        if (task->owner != self) {
            thread_pool_t.mutex_unlock(&self->mutex);
            return THREAD_POOL_NOT_FOUND;
        }
        while (!thread_pool_t.is_terminal(task->state)) {
            if (thread_pool_t.find_active_locked(self, task) < 0) {
                thread_pool_t.mutex_unlock(&self->mutex);
                return THREAD_POOL_NOT_FOUND;
            }
            if (!thread_pool_t.condition_wait(&self->state_condition, &self->mutex)) {
                thread_pool_t.mutex_unlock(&self->mutex);
                return THREAD_POOL_SYSTEM_ERROR;
            }
        }
        thread_pool_t.mutex_unlock(&self->mutex);
        return THREAD_POOL_OK;
    }

    pub int shutdown(borrowed mut *self) {
        if (self == NULL) return THREAD_POOL_INVALID_ARGUMENT;
        if (!self->synchronization_ready) return THREAD_POOL_OK;
        if (!thread_pool_t.mutex_lock(&self->mutex)) return THREAD_POOL_SYSTEM_ERROR;
        if (self->state == THREAD_POOL_RUNNING) thread_pool_t.stop_locked(self);
        thread_pool_t.mutex_unlock(&self->mutex);

        int result = THREAD_POOL_OK;
        for (size_t index = 0; index < self->worker_count; index++) {
            if (!self->worker_joined[index]) {
                if (thread_pool_t.join_worker(self->workers[index])) {
                    self->worker_joined[index] = 1;
#if defined(_WIN32)
                    self->workers[index] = NULL;
#endif
                } else {
                    result = THREAD_POOL_SYSTEM_ERROR;
                }
            }
        }
        if (result != THREAD_POOL_OK) return result;
        if (!thread_pool_t.mutex_lock(&self->mutex)) return THREAD_POOL_SYSTEM_ERROR;
        self->state = THREAD_POOL_STOPPED;
        thread_pool_t.condition_broadcast(&self->state_condition);
        thread_pool_t.mutex_unlock(&self->mutex);
        return result;
    }

    pub int destroy(borrowed mut *self) {
        if (self == NULL) return THREAD_POOL_INVALID_ARGUMENT;
        if (!self->synchronization_ready) return THREAD_POOL_OK;
        int result = thread_pool_t.shutdown(self);
        if (result != THREAD_POOL_OK) return result;
        int state_condition_result = thread_pool_t.condition_destroy(&self->state_condition);
        int work_condition_result = thread_pool_t.condition_destroy(&self->work_condition);
        int mutex_result = thread_pool_t.mutex_destroy(&self->mutex);
        memset(self, 0, sizeof(*self));
        return state_condition_result && work_condition_result && mutex_result
            ? THREAD_POOL_OK
            : THREAD_POOL_SYSTEM_ERROR;
    }
} thread_pool_t;

#if defined(_WIN32)
static DWORD WINAPI cplus_thread_pool_worker_entry(LPVOID argument) {
    thread_pool_t* pool = (thread_pool_t*)argument;
    thread_pool_t.worker(pool);
    return 0;
}
#else
static void* cplus_thread_pool_worker_entry(void* argument) {
    thread_pool_t* pool = (thread_pool_t*)argument;
    thread_pool_t.worker(pool);
    return NULL;
}
#endif

#endif
