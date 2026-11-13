/*
 * Two renamed imports used by JugglucoNG's pinned Libre 2 library.
 * Adapted from share/hooks.cpp, copyright (C) 2021 Jaap Korthals Altes.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include <atomic>
#include <pthread.h>
#include <sys/time.h>
#include <cerrno>
static std::atomic<bool> initializing{false};

extern "C" __attribute__((visibility("default")))
void libre2_set_initializing(bool value) { initializing.store(value); }

// This vendor-specific alias is not the application's/system's gettimeofday.
extern "C" __attribute__((visibility("default")))
int getTimeofday(timeval* value, struct timezone* zone) {
    if (!value) { errno = EINVAL; return -1; }
    value->tv_sec = 0;
    value->tv_usec = 0;
    if (zone) { zone->tz_minuteswest = 0; zone->tz_dsttime = 0; }
    return 0;
}
extern "C" __attribute__((visibility("default")))
int pthread_Detach(pthread_t thread) {
    if (initializing.exchange(false)) return pthread_join(thread, nullptr);
    return pthread_detach(thread);
}
