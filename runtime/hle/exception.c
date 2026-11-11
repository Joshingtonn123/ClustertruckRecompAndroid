// Switch SDK user-exception stack bootstrap.
// The ABI contract is adapted from the MIT-licensed P5R Android runtime:
// x0 points to the size output (optional), and x0 returns the stack address.
#include "../hle.h"

static pthread_mutex_t g_user_exception_stack_lock = PTHREAD_MUTEX_INITIALIZER;
static uint64_t g_user_exception_stack;

RT_ON_RESET(user_exception_stack_reset) {
    pthread_mutex_lock(&g_user_exception_stack_lock);
    g_user_exception_stack = 0;  // the HLE heap is wiped on an in-place reboot
    pthread_mutex_unlock(&g_user_exception_stack_lock);
}

HLE_FUNC("nnosGetDefaultUserExceptionStackForApplication", get_default_user_exception_stack) {
    const uint64_t stack_size = 0x10000;  // 64 KiB, as returned by the reference Android runtime
    pthread_mutex_lock(&g_user_exception_stack_lock);
    if (!g_user_exception_stack) {
        // hle_alloc is 64-byte aligned; reserve one extra page and align the returned stack to 4 KiB.
        uintptr_t allocation = (uintptr_t)hle_alloc(stack_size + 0x1000);
        g_user_exception_stack = (uint64_t)((allocation + 0xFFFu) & ~(uintptr_t)0xFFFu);
    }
    uint64_t stack = g_user_exception_stack;
    pthread_mutex_unlock(&g_user_exception_stack_lock);

    if (c->x[0]) *(uint64_t *)(uintptr_t)c->x[0] = stack_size;
    c->x[0] = stack;
    LOG("[HLE] nnosGetDefaultUserExceptionStackForApplication -> %llx (64 KiB)\\n",
        (unsigned long long)stack);
}
