/*
 * Run with: cpc run stdlib/examples/thread_pool.cp
 * Each task processes a small slice, saves progress in its stable context,
 * and yields so the pool can schedule another task.
 */
comptime import "stdlib:/concurrency/thread_pool.cp";

#include <stdio.h>

typedef struct work_state_t {
    int task_number;
    int total_steps;
    int completed_steps;
} work_state_t;

thread_step_result_t do_work(thread_task_t* task, void* context) {
    (void)task;
    work_state_t* work = (work_state_t*)context;
    int budget = 3;

    while (budget > 0 && work->completed_steps < work->total_steps) {
        work->completed_steps++;
        budget--;
    }

    if (work->completed_steps < work->total_steps) return thread_task_t.yield();
    return thread_task_t.done();
}

int main(void) {
    thread_pool_t pool = {0};
    thread_task_t tasks[3];
    work_state_t work[3] = {
        {1, 8, 0},
        {2, 5, 0},
        {3, 11, 0}
    };

    if (pool.init(2) != THREAD_POOL_OK) {
        fprintf(stderr, "could not start the thread pool\n");
        return 1;
    }

    int failed = 0;
    for (size_t index = 0; index < 3; index++) {
        thread_task_t.init(&tasks[index], do_work, &work[index]);
        if (pool.submit(&tasks[index]) != THREAD_POOL_OK) {
            fprintf(stderr, "could not submit task %zu\n", index + 1);
            failed = 1;
            break;
        }
    }

    if (!failed) {
        for (size_t index = 0; index < 3; index++) {
            if (pool.wait(&tasks[index]) != THREAD_POOL_OK ||
                tasks[index].state != THREAD_TASK_COMPLETED) {
                fprintf(stderr, "task %zu did not complete successfully\n", index + 1);
                failed = 1;
            }
        }
    }

    if (pool.destroy() != THREAD_POOL_OK) {
        fprintf(stderr, "could not shut down the thread pool cleanly\n");
        failed = 1;
    }
    if (failed) return 1;

    for (size_t index = 0; index < 3; index++) {
        printf("task %d completed %d cooperative steps\n",
               work[index].task_number, work[index].completed_steps);
    }
    return 0;
}
