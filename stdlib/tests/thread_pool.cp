comptime import "stdlib:/concurrency/thread_pool.cp";

typedef struct thread_pool_count_context_t {
    int remaining;
    int calls;
} thread_pool_count_context_t;

thread_step_result_t thread_pool_count_step(thread_task_t* task, void* raw_context) {
    (void)task;
    thread_pool_count_context_t* context = (thread_pool_count_context_t*)raw_context;
    context->calls++;
    if (--context->remaining > 0) return thread_task_t.yield();
    return thread_task_t.done();
}

typedef struct thread_pool_fair_context_t {
    thread_pool_t* pool;
    thread_task_t* second_task;
    int first_calls;
    int submit_result;
    int order[2];
    size_t order_count;
} thread_pool_fair_context_t;

thread_step_result_t thread_pool_first_fair_step(thread_task_t* task, void* raw_context) {
    (void)task;
    thread_pool_fair_context_t* context = (thread_pool_fair_context_t*)raw_context;
    if (context->first_calls++ == 0) {
        thread_pool_t* pool = context->pool;
        context->submit_result = pool->submit(context->second_task);
        if (context->submit_result != THREAD_POOL_OK) return thread_task_t.failed(context->submit_result);
        return thread_task_t.yield();
    }
    context->order[context->order_count++] = 2;
    return thread_task_t.done();
}

thread_step_result_t thread_pool_second_fair_step(thread_task_t* task, void* raw_context) {
    (void)task;
    thread_pool_fair_context_t* context = (thread_pool_fair_context_t*)raw_context;
    context->order[context->order_count++] = 1;
    return thread_task_t.done();
}

typedef struct thread_pool_queue_context_t {
    thread_pool_t* pool;
    thread_task_t* queued_tasks[3];
    int submit_results[3];
    int cancel_result;
    int order[2];
    size_t order_count;
} thread_pool_queue_context_t;

typedef struct thread_pool_queue_item_t {
    thread_pool_queue_context_t* shared;
    int id;
} thread_pool_queue_item_t;

thread_step_result_t thread_pool_queue_cancel_step(thread_task_t* task, void* raw_context) {
    (void)task;
    thread_pool_queue_context_t* context = (thread_pool_queue_context_t*)raw_context;
    thread_pool_t* pool = context->pool;
    for (size_t index = 0; index < 3; index++) {
        context->submit_results[index] = pool->submit(context->queued_tasks[index]);
    }
    context->cancel_result = pool->cancel(context->queued_tasks[1]);
    return thread_task_t.done();
}

thread_step_result_t thread_pool_queue_record_step(thread_task_t* task, void* raw_context) {
    (void)task;
    thread_pool_queue_item_t* item = (thread_pool_queue_item_t*)raw_context;
    item->shared->order[item->shared->order_count++] = item->id;
    return thread_task_t.done();
}

typedef struct thread_pool_wait_context_t {
    thread_pool_t* pool;
    thread_task_t* waiting_task;
    thread_task_t* controller_task;
    int should_cancel;
    int operation_result;
    int calls;
} thread_pool_wait_context_t;

thread_step_result_t thread_pool_wait_step(thread_task_t* task, void* raw_context) {
    (void)task;
    thread_pool_wait_context_t* context = (thread_pool_wait_context_t*)raw_context;
    context->calls++;
    if (context->calls == 1) {
        thread_pool_t* pool = context->pool;
        context->operation_result = pool->submit(context->controller_task);
        if (context->operation_result != THREAD_POOL_OK) return thread_task_t.failed(context->operation_result);
        return thread_task_t.wait();
    }
    return thread_task_t.done();
}

thread_step_result_t thread_pool_control_step(thread_task_t* task, void* raw_context) {
    (void)task;
    thread_pool_wait_context_t* context = (thread_pool_wait_context_t*)raw_context;
    thread_pool_t* pool = context->pool;
    if (context->should_cancel) {
        context->operation_result = pool->cancel(context->waiting_task);
    } else {
        context->operation_result = pool->wake(context->waiting_task);
    }
    return context->operation_result == THREAD_POOL_OK
        ? thread_task_t.done()
        : thread_task_t.failed(context->operation_result);
}

thread_step_result_t thread_pool_fail_step(thread_task_t* task, void* context) {
    (void)task;
    (void)context;
    return thread_task_t.failed(73);
}

typedef struct thread_pool_cancel_context_t {
    thread_pool_t* pool;
    int cancel_result;
    int calls;
} thread_pool_cancel_context_t;

typedef struct thread_pool_early_wake_context_t {
    thread_pool_t* pool;
    int wake_result;
    int calls;
} thread_pool_early_wake_context_t;

thread_step_result_t thread_pool_early_wake_step(thread_task_t* task, void* raw_context) {
    thread_pool_early_wake_context_t* context = (thread_pool_early_wake_context_t*)raw_context;
    context->calls++;
    if (context->calls == 1) {
        thread_pool_t* pool = context->pool;
        context->wake_result = pool->wake(task);
        return thread_task_t.wait();
    }
    return thread_task_t.done();
}

thread_step_result_t thread_pool_cancel_self_step(thread_task_t* task, void* raw_context) {
    thread_pool_cancel_context_t* context = (thread_pool_cancel_context_t*)raw_context;
    context->calls++;
    thread_pool_t* pool = context->pool;
    context->cancel_result = pool->cancel(task);
    return thread_task_t.yield();
}

thread_step_result_t thread_pool_wait_forever_step(thread_task_t* task, void* context) {
    (void)task;
    (void)context;
    return thread_task_t.wait();
}

@test "thread pool validates limits and supports repeat-safe shutdown" {
    thread_pool_t pool = {0};
    int zero_workers = pool.init(0);
    int too_many_workers = pool.init(3);
    int initialized = pool.init(1);
    int shutdown = pool.shutdown();
    int shutdown_again = pool.shutdown();
    int destroyed = pool.destroy();
    int destroyed_again = pool.destroy();
    int reinitialized = pool.init(1);
    int redestroyed = pool.destroy();
    @assertEquals(THREAD_POOL_INVALID_ARGUMENT, zero_workers);
    @assertEquals(THREAD_POOL_INVALID_ARGUMENT, too_many_workers);
    @assertEquals(THREAD_POOL_OK, initialized);
    @assertEquals(THREAD_POOL_OK, shutdown);
    @assertEquals(THREAD_POOL_OK, shutdown_again);
    @assertEquals(THREAD_POOL_OK, destroyed);
    @assertEquals(THREAD_POOL_OK, destroyed_again);
    @assertEquals(THREAD_POOL_OK, reinitialized);
    @assertEquals(THREAD_POOL_OK, redestroyed);
}

@test "thread pool resumes yielded work and removes completed tasks" {
    thread_pool_t pool = {0};
    thread_task_t task = {0};
    thread_pool_count_context_t context = {3, 0};
    thread_task_state_t state = THREAD_TASK_IDLE;
    size_t steps = 0;
    size_t expected_steps = 3;
    int error_code = 0;
    int initialized = pool.init(1);
    thread_task_t.init(&task, thread_pool_count_step, &context);
    int submitted = initialized == THREAD_POOL_OK ? pool.submit(&task) : THREAD_POOL_INVALID_STATE;
    int waited = submitted == THREAD_POOL_OK ? pool.wait(&task) : THREAD_POOL_INVALID_STATE;
    int status_result = waited == THREAD_POOL_OK ? pool.status(&task, &state, &steps, &error_code) : THREAD_POOL_INVALID_STATE;
    int cancel_result = waited == THREAD_POOL_OK ? pool.cancel(&task) : THREAD_POOL_INVALID_STATE;
    int first_calls = context.calls;
    context.remaining = 1;
    context.calls = 0;
    thread_task_t.init(&task, thread_pool_count_step, &context);
    int resubmitted = waited == THREAD_POOL_OK ? pool.submit(&task) : THREAD_POOL_INVALID_STATE;
    int waited_again = resubmitted == THREAD_POOL_OK ? pool.wait(&task) : THREAD_POOL_INVALID_STATE;
    int destroyed = pool.destroy();
    @assertEquals(THREAD_POOL_OK, initialized);
    @assertEquals(THREAD_POOL_OK, submitted);
    @assertEquals(THREAD_POOL_OK, waited);
    @assertEquals(THREAD_POOL_OK, status_result);
    @assertEquals(THREAD_POOL_OK, resubmitted);
    @assertEquals(THREAD_POOL_OK, waited_again);
    @assertEquals(expected_steps, steps);
    @assertEquals(THREAD_TASK_COMPLETED, state);
    @assertEquals(3, first_calls);
    @assertEquals(1, context.calls);
    @assertEquals(0, error_code);
    @assertEquals(THREAD_POOL_NOT_FOUND, cancel_result);
    @assertEquals(THREAD_POOL_OK, destroyed);
}

@test "yielded tasks return to the FIFO tail" {
    thread_pool_t pool = {0};
    thread_task_t first = {0};
    thread_task_t second = {0};
    thread_pool_fair_context_t context = {&pool, &second, 0, 0, {0, 0}, 0};
    int initialized = pool.init(1);
    thread_task_t.init(&first, thread_pool_first_fair_step, &context);
    thread_task_t.init(&second, thread_pool_second_fair_step, &context);
    int submitted = initialized == THREAD_POOL_OK ? pool.submit(&first) : THREAD_POOL_INVALID_STATE;
    int waited = submitted == THREAD_POOL_OK ? pool.wait(&first) : THREAD_POOL_INVALID_STATE;
    int second_waited = waited == THREAD_POOL_OK ? pool.wait(&second) : THREAD_POOL_INVALID_STATE;
    int destroyed = pool.destroy();
    @assertEquals(THREAD_POOL_OK, initialized);
    @assertEquals(THREAD_POOL_OK, submitted);
    @assertEquals(THREAD_POOL_OK, waited);
    @assertEquals(THREAD_POOL_OK, second_waited);
    @assertEquals(THREAD_POOL_OK, context.submit_result);
    @assertEquals((size_t)2, context.order_count);
    @assertEquals(1, context.order[0]);
    @assertEquals(2, context.order[1]);
    @assertEquals(THREAD_POOL_OK, destroyed);
}

@test "cancelling a queued task preserves the remaining FIFO order" {
    thread_pool_t pool = {0};
    thread_task_t controller = {0};
    thread_task_t queued[3] = {0};
    thread_pool_queue_context_t context = {&pool, {&queued[0], &queued[1], &queued[2]}, {0, 0, 0}, 0, {0, 0}, 0};
    thread_pool_queue_item_t items[3] = {{&context, 1}, {&context, 2}, {&context, 3}};
    int initialized = pool.init(1);
    thread_task_t.init(&controller, thread_pool_queue_cancel_step, &context);
    for (size_t index = 0; index < 3; index++) {
        thread_task_t.init(&queued[index], thread_pool_queue_record_step, &items[index]);
    }
    int submitted = initialized == THREAD_POOL_OK ? pool.submit(&controller) : THREAD_POOL_INVALID_STATE;
    int controller_waited = submitted == THREAD_POOL_OK ? pool.wait(&controller) : THREAD_POOL_INVALID_STATE;
    int first_waited = controller_waited == THREAD_POOL_OK ? pool.wait(&queued[0]) : THREAD_POOL_INVALID_STATE;
    int cancelled_waited = controller_waited == THREAD_POOL_OK ? pool.wait(&queued[1]) : THREAD_POOL_INVALID_STATE;
    int last_waited = controller_waited == THREAD_POOL_OK ? pool.wait(&queued[2]) : THREAD_POOL_INVALID_STATE;
    int destroyed = pool.destroy();
    @assertEquals(THREAD_POOL_OK, initialized);
    @assertEquals(THREAD_POOL_OK, submitted);
    @assertEquals(THREAD_POOL_OK, controller_waited);
    @assertEquals(THREAD_POOL_OK, first_waited);
    @assertEquals(THREAD_POOL_OK, cancelled_waited);
    @assertEquals(THREAD_POOL_OK, last_waited);
    @assertEquals(THREAD_POOL_OK, context.submit_results[0]);
    @assertEquals(THREAD_POOL_OK, context.submit_results[1]);
    @assertEquals(THREAD_POOL_OK, context.submit_results[2]);
    @assertEquals(THREAD_POOL_OK, context.cancel_result);
    @assertEquals(THREAD_TASK_CANCELLED, queued[1].state);
    @assertEquals((size_t)2, context.order_count);
    @assertEquals(1, context.order[0]);
    @assertEquals(3, context.order[1]);
    @assertEquals(THREAD_POOL_OK, destroyed);
}

@test "thread pool parks tasks until woken by another task" {
    thread_pool_t pool = {0};
    thread_task_t parked = {0};
    thread_task_t controller = {0};
    thread_pool_wait_context_t context = {&pool, &parked, &controller, 0, 0, 0};
    thread_task_state_t state = THREAD_TASK_IDLE;
    int initialized = pool.init(1);
    thread_task_t.init(&parked, thread_pool_wait_step, &context);
    thread_task_t.init(&controller, thread_pool_control_step, &context);
    int submitted = initialized == THREAD_POOL_OK ? pool.submit(&parked) : THREAD_POOL_INVALID_STATE;
    int waited = submitted == THREAD_POOL_OK ? pool.wait(&parked) : THREAD_POOL_INVALID_STATE;
    int status_result = waited == THREAD_POOL_OK ? pool.status(&parked, &state, NULL, NULL) : THREAD_POOL_INVALID_STATE;
    int controller_waited = waited == THREAD_POOL_OK ? pool.wait(&controller) : THREAD_POOL_INVALID_STATE;
    int destroyed = pool.destroy();
    @assertEquals(THREAD_POOL_OK, initialized);
    @assertEquals(THREAD_POOL_OK, submitted);
    @assertEquals(THREAD_POOL_OK, waited);
    @assertEquals(THREAD_POOL_OK, status_result);
    @assertEquals(THREAD_TASK_COMPLETED, state);
    @assertEquals(THREAD_POOL_OK, context.operation_result);
    @assertEquals(2, context.calls);
    @assertEquals(THREAD_POOL_OK, controller_waited);
    @assertEquals(THREAD_POOL_OK, destroyed);
}

@test "thread pool cancellation removes a parked task" {
    thread_pool_t pool = {0};
    thread_task_t parked = {0};
    thread_task_t controller = {0};
    thread_task_t running_cancelled = {0};
    thread_pool_wait_context_t context = {&pool, &parked, &controller, 1, 0, 0};
    thread_pool_cancel_context_t cancel_context = {&pool, 0, 0};
    thread_task_state_t state = THREAD_TASK_IDLE;
    thread_task_state_t running_state = THREAD_TASK_IDLE;
    int initialized = pool.init(1);
    thread_task_t.init(&parked, thread_pool_wait_step, &context);
    thread_task_t.init(&controller, thread_pool_control_step, &context);
    int submitted = initialized == THREAD_POOL_OK ? pool.submit(&parked) : THREAD_POOL_INVALID_STATE;
    int waited = submitted == THREAD_POOL_OK ? pool.wait(&parked) : THREAD_POOL_INVALID_STATE;
    int status_result = waited == THREAD_POOL_OK ? pool.status(&parked, &state, NULL, NULL) : THREAD_POOL_INVALID_STATE;
    int controller_waited = waited == THREAD_POOL_OK ? pool.wait(&controller) : THREAD_POOL_INVALID_STATE;
    int wake_result = waited == THREAD_POOL_OK ? pool.wake(&parked) : THREAD_POOL_INVALID_STATE;
    thread_task_t.init(&running_cancelled, thread_pool_cancel_self_step, &cancel_context);
    int running_submitted = waited == THREAD_POOL_OK ? pool.submit(&running_cancelled) : THREAD_POOL_INVALID_STATE;
    int running_waited = running_submitted == THREAD_POOL_OK ? pool.wait(&running_cancelled) : THREAD_POOL_INVALID_STATE;
    int running_status = running_waited == THREAD_POOL_OK
        ? pool.status(&running_cancelled, &running_state, NULL, NULL)
        : THREAD_POOL_INVALID_STATE;
    int destroyed = pool.destroy();
    @assertEquals(THREAD_POOL_OK, initialized);
    @assertEquals(THREAD_POOL_OK, submitted);
    @assertEquals(THREAD_POOL_OK, waited);
    @assertEquals(THREAD_POOL_OK, status_result);
    @assertEquals(THREAD_TASK_CANCELLED, state);
    @assertEquals(1, context.calls);
    @assertEquals(THREAD_POOL_OK, controller_waited);
    @assertEquals(THREAD_POOL_NOT_FOUND, wake_result);
    @assertEquals(THREAD_POOL_OK, running_submitted);
    @assertEquals(THREAD_POOL_OK, running_waited);
    @assertEquals(THREAD_POOL_OK, running_status);
    @assertEquals(THREAD_TASK_CANCELLED, running_state);
    @assertEquals(THREAD_POOL_OK, cancel_context.cancel_result);
    @assertEquals(1, cancel_context.calls);
    @assertEquals(THREAD_POOL_OK, destroyed);
}

@test "thread pool reports callback failure and its error code" {
    thread_pool_t pool = {0};
    thread_task_t task = {0};
    thread_task_state_t state = THREAD_TASK_IDLE;
    int error_code = 0;
    int initialized = pool.init(2);
    thread_task_t.init(&task, thread_pool_fail_step, NULL);
    int submitted = initialized == THREAD_POOL_OK ? pool.submit(&task) : THREAD_POOL_INVALID_STATE;
    int waited = submitted == THREAD_POOL_OK ? pool.wait(&task) : THREAD_POOL_INVALID_STATE;
    int status_result = waited == THREAD_POOL_OK ? pool.status(&task, &state, NULL, &error_code) : THREAD_POOL_INVALID_STATE;
    int destroyed = pool.destroy();
    @assertEquals(THREAD_POOL_OK, initialized);
    @assertEquals(THREAD_POOL_OK, submitted);
    @assertEquals(THREAD_POOL_OK, waited);
    @assertEquals(THREAD_POOL_OK, status_result);
    @assertEquals(THREAD_TASK_FAILED, state);
    @assertEquals(73, error_code);
    @assertEquals(THREAD_POOL_OK, destroyed);
}

@test "wake during a running step is retained when the step parks" {
    thread_pool_t pool = {0};
    thread_task_t task = {0};
    thread_pool_early_wake_context_t context = {&pool, THREAD_POOL_INVALID_STATE, 0};
    thread_task_state_t state = THREAD_TASK_IDLE;
    size_t steps = 0;
    int initialized = pool.init(1);
    thread_task_t.init(&task, thread_pool_early_wake_step, &context);
    int submitted = initialized == THREAD_POOL_OK ? pool.submit(&task) : THREAD_POOL_INVALID_STATE;
    int waited = submitted == THREAD_POOL_OK ? pool.wait(&task) : THREAD_POOL_INVALID_STATE;
    int status_result = waited == THREAD_POOL_OK ? pool.status(&task, &state, &steps, NULL) : THREAD_POOL_INVALID_STATE;
    int destroyed = pool.destroy();
    @assertEquals(THREAD_POOL_OK, initialized);
    @assertEquals(THREAD_POOL_OK, submitted);
    @assertEquals(THREAD_POOL_OK, waited);
    @assertEquals(THREAD_POOL_OK, status_result);
    @assertEquals(THREAD_POOL_OK, context.wake_result);
    @assertEquals(2, context.calls);
    @assertEquals(THREAD_TASK_COMPLETED, state);
    @assertEquals((size_t)2, steps);
    @assertEquals(THREAD_POOL_OK, destroyed);
}

@test "two worker threads make progress across independent resumable tasks" {
    thread_pool_t pool = {0};
    thread_task_t tasks[20] = {0};
    thread_pool_count_context_t contexts[20] = {0};
    int completed = 0;
    int expected_completed = 20;
    size_t submitted = 0;
    int initialized = pool.init(2);
    if (initialized == THREAD_POOL_OK) {
        for (size_t index = 0; index < 20; index++) {
            contexts[index].remaining = 4;
            thread_task_t.init(&tasks[index], thread_pool_count_step, &contexts[index]);
            if (pool.submit(&tasks[index]) != THREAD_POOL_OK) break;
            submitted++;
        }
        for (size_t index = 0; index < submitted; index++) {
            if (pool.wait(&tasks[index]) == THREAD_POOL_OK && contexts[index].calls == 4) completed++;
        }
    }
    int destroyed = pool.destroy();
    @assertEquals(THREAD_POOL_OK, initialized);
    @assertEquals((size_t)20, submitted);
    @assertEquals(THREAD_POOL_OK, destroyed);
    @assertEquals(expected_completed, completed);
}

@test "thread pool rejects submissions beyond its fixed capacity" {
    thread_pool_t pool = {0};
    thread_task_t tasks[CPLUS_THREAD_POOL_MAX_TASKS + 1] = {0};
    for (size_t index = 0; index < CPLUS_THREAD_POOL_MAX_TASKS + 1; index++) {
        thread_task_t.init(&tasks[index], thread_pool_wait_forever_step, NULL);
    }
    @assertEquals(THREAD_POOL_OK, pool.init(1));
    size_t submitted = 0;
    int initialized = pool.init(1);
    if (initialized == THREAD_POOL_OK) {
        for (size_t index = 0; index < CPLUS_THREAD_POOL_MAX_TASKS; index++) {
            if (pool.submit(&tasks[index]) == THREAD_POOL_OK) submitted++;
        }
    }
    size_t expected_capacity = CPLUS_THREAD_POOL_MAX_TASKS;
    int overflow_result = initialized == THREAD_POOL_OK
        ? pool.submit(&tasks[CPLUS_THREAD_POOL_MAX_TASKS])
        : THREAD_POOL_INVALID_STATE;
    int destroyed = pool.destroy();
    @assertEquals(THREAD_POOL_OK, initialized);
    @assertEquals(expected_capacity, submitted);
    @assertEquals(THREAD_POOL_FULL, overflow_result);
    @assertEquals(THREAD_POOL_OK, destroyed);
    @assertEquals(THREAD_TASK_CANCELLED, tasks[0].state);
    @assertEquals(THREAD_TASK_CANCELLED, tasks[CPLUS_THREAD_POOL_MAX_TASKS - 1].state);
    @assertEquals(THREAD_TASK_IDLE, tasks[CPLUS_THREAD_POOL_MAX_TASKS].state);
}
