# Cooperative Thread Pool Specification

Status: initial implementation available; Windows and POSIX backends are provided.

This is a living specification. Changes to scheduling, lifecycle, task states, or platform support must update this document and its fixtures.

## Purpose and execution model

`thread_pool_t` runs many logical tasks on a caller-selected one or two native system threads. Scheduling is cooperative and stackless: C cannot safely suspend an arbitrary call stack. Each task is therefore a resumable step callback with its continuation stored in caller-owned context. A callback performs one bounded unit of work and returns; the worker later calls it again to resume. Callbacks must not wait on I/O, sleeps, application locks, or long computations. Use `WAIT` and an external completion signal instead. The pool releases its lock before invoking callbacks, so callbacks may briefly call pool APIs such as `submit`, `wake`, or `cancel`.

The pool owns no task memory and performs no dynamic allocation. `CPLUS_THREAD_POOL_MAX_TASKS` sets the fixed active-task and ready-queue capacity (default 64). A full pool rejects submissions with `THREAD_POOL_FULL`. Each task and its context must remain at a stable address until it reaches a terminal state and the caller no longer uses it. Do not copy an active task or pool.

## Task contract

The step callback returns one of four actions:

| Action | Scheduler behavior |
| --- | --- |
| `THREAD_STEP_YIELD` | Requeue at the FIFO tail for a later time slice. |
| `THREAD_STEP_WAIT` | Remove from the ready queue but keep registered; an external caller must wake it. A wake received while its callback is still running is latched and schedules another step if that callback then returns `WAIT`. |
| `THREAD_STEP_DONE` | Mark complete and remove from the pool's active registry. |
| `THREAD_STEP_FAILED` | Mark failed and remove from the active registry; its `error_code` is available from `status`. |

The callback may be invoked repeatedly and must preserve its program counter/state in its context, not in local variables or borrowed stack addresses. It runs without the pool lock held. Pool/task status and queue mutation are synchronized; user context is not. Read or reuse context only after `wait` returns or after providing your own synchronization. A yield is a scheduling boundary, not an OS-thread suspension primitive.

Cancellation of a queued or waiting task takes effect immediately. Cancellation of a running task sets a request that takes effect when its callback next returns. `wake` is valid for a waiting task, or for a running task whose next result may be `WAIT`; in the latter case the wake is latched and consumed only if the step parks, preventing a readiness event from being lost during the RUNNING-to-WAITING transition. The pool cannot preempt or forcibly terminate a callback. Shutdown cancels queued/waiting tasks, requests cancellation of running tasks, wakes idle workers, and joins them; it therefore waits for every running callback to return. Do not call `wait`, `shutdown`, or `destroy` from a pool callback because they can wait for that same worker.

## API and lifecycle

The public module is `stdlib:/concurrency/thread_pool.cp`. Its C-plus structs own their methods. Call `thread_task_t.init(&task, callback, context)` to reset an inactive task; its `yield`, `wait`, `done`, and `failed(error_code)` factories return callback results. Pool methods are `init`, `submit`, `wake`, `cancel`, `status`, `wait`, `shutdown`, and `destroy`; each returns a `THREAD_POOL_*` integer status. `status(task, &state, optional_steps, optional_error)` exposes queued, running, waiting, completed, failed, or cancelled state, callback-step count, and task failure code. `wait(task)` blocks only its calling thread until a task is terminal; it must not be called from a pool callback.

| Status | Meaning |
| --- | --- |
| `THREAD_POOL_OK` | Operation succeeded. |
| `THREAD_POOL_INVALID_ARGUMENT` | Required pointer or worker-count argument is invalid. |
| `THREAD_POOL_INVALID_STATE` | Operation is not valid for this pool/task state. |
| `THREAD_POOL_FULL` | The active-task capacity is exhausted. |
| `THREAD_POOL_NOT_FOUND` | The task is not active in this pool. |
| `THREAD_POOL_SYSTEM_ERROR` | Native synchronization or thread lifecycle failed. |

`THREAD_POOL_INVALID_STEP_RESULT` is stored as the task's failure code if a callback returns an unknown action. The task state constants are `THREAD_TASK_IDLE`, `QUEUED`, `RUNNING`, `WAITING`, `COMPLETED`, `FAILED`, and `CANCELLED` (all names use the `THREAD_TASK_` prefix).

Initialization accepts exactly one or two native workers and starts scheduling. Submission order determines FIFO dequeue order; each yield rejoins the tail, providing round-robin progress among ready tasks (with multiple workers, callback start/completion order may overlap). A waiting task is not polled and consumes no worker time. `CPLUS_THREAD_POOL_MAX_TASKS` defaults to 64; override it with a compiler definition such as `-DCPLUS_THREAD_POOL_MAX_TASKS=128`. `shutdown` and `destroy` are repeat-safe after successful completion; callers must serialize lifecycle calls and must not destroy a pool while other callers use it. Reinitialize task storage before submitting a terminal task again. Do not use a task or pool by-value copy.

Example of a resumable bounded task:

```c
comptime import "stdlib:/concurrency/thread_pool.cp";

typedef struct scan_state_t {
    borrowed const unsigned char* bytes;
    size_t length;
    size_t offset;
} scan_state_t;

thread_step_result_t scan_step(thread_task_t* task, void* raw_state) {
    (void)task;
    scan_state_t* state = (scan_state_t*)raw_state;
    size_t remaining = state->length - state->offset;
    size_t chunk = remaining < 128 ? remaining : 128;
    size_t end = state->offset + chunk;
    while (state->offset < end) {
        /* Process one byte; offset is the saved continuation. */
        state->offset++;
    }
    return state->offset == state->length ? thread_task_t.done() : thread_task_t.yield();
}

int scan_input(const unsigned char* input_bytes, size_t input_length) {
    thread_pool_t pool = {0};
    thread_task_t task = {0};
    scan_state_t state = {input_bytes, input_length, 0};
    if (pool.init(1) != THREAD_POOL_OK) return 1;
    thread_task_t.init(&task, scan_step, &state);
    int result = pool.submit(&task);
    if (result == THREAD_POOL_OK) result = pool.wait(&task);
    int destroy_result = pool.destroy();
    return result == THREAD_POOL_OK ? destroy_result : result;
}
```

## Platform and limitations

The implementation uses a fixed FIFO ring and active-task registry, with separate condition variables for waking workers and notifying task waiters. Keeping those notification channels distinct prevents a task waiter from consuming a worker's only wake-up. Native primitives are private: Windows uses Win32 threads/SRW locks/condition variables; POSIX targets use pthreads. Linux adds `-lpthread`; macOS uses pthreads through the system runtime.

Implementation proceeds in dependency order: define caller-owned resumable task/result types and capacity limits; add the platform thread/synchronization layer; implement the locked queue and state transitions; then validate yielding, waiting/waking, cancellation, completion, capacity, and shutdown through executable fixtures. These parts are implemented. The library does not provide timers, I/O polling, priorities, work stealing, futures, stackful coroutines, or allocator synchronization. Applications integrate readiness/timer events by calling `wake` from their event source. The pool does not make unrelated global state or allocators thread-safe; in particular, the current `xmem` global allocator remains single-threaded. A task's shared context is the application's responsibility when multiple tasks or other threads access it.

## Validation requirements

`stdlib/tests/thread_pool.cp` covers worker-count bounds, FIFO requeue and repeated yielding, completion and failure removal, explicit wait/wake including an early latched wake, queued/running/waiting cancellation, two-worker progress, capacity limits, and shutdown/reinitialization. The fixture compiles and runs the generated C through the configured TinyCC backend. CI should run equivalent native fixtures on each supported host platform; assertions use synchronization through the pool API rather than assumed scheduling delays.
