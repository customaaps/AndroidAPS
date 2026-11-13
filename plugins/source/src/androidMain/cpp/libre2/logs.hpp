#pragma once
#include <cstdio>
#include <cerrno>
#include <cstring>
#include <sys/syscall.h>
#include <unistd.h>
#include <stdexcept>
#define LOGGER(...) ((void)0)
#define LOGAR(...) ((void)0)
#define LOGSTRING(...) ((void)0)
#define lerror(...) ((void)0)
#define flerror(...) ((void)0)
