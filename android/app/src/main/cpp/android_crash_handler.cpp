#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/ucontext.h>
#include <unistd.h>
#include <unwind.h>

namespace {
constexpr const char* kCrashTag = "hakuX";
constexpr size_t kPathMax = 512;

static char g_inline_aio_flag_path[kPathMax];

static int GetTid() {
  return static_cast<int>(syscall(SYS_gettid));
}

struct BacktraceState {
  void** addrs;
  int count;
  int max;
};

static _Unwind_Reason_Code UnwindCallback(struct _Unwind_Context* ctx, void* arg) {
  BacktraceState* state = static_cast<BacktraceState*>(arg);
  if (state->count >= state->max) {
    return _URC_END_OF_STACK;
  }
  uintptr_t pc = _Unwind_GetIP(ctx);
  if (pc != 0) {
    state->addrs[state->count++] = reinterpret_cast<void*>(pc);
  }
  return _URC_NO_REASON;
}

static void LogBacktrace() {
  void* addrs[64];
  BacktraceState state{addrs, 0, static_cast<int>(sizeof(addrs) / sizeof(addrs[0]))};
  _Unwind_Backtrace(UnwindCallback, &state);
  for (int i = 0; i < state.count; ++i) {
    Dl_info info;
    if (dladdr(addrs[i], &info) && info.dli_fname) {
      uintptr_t base = reinterpret_cast<uintptr_t>(info.dli_fbase);
      uintptr_t pc = reinterpret_cast<uintptr_t>(addrs[i]);
      uintptr_t rel = (base != 0 && pc >= base) ? (pc - base) : 0;
      const char* sym = info.dli_sname ? info.dli_sname : "?";
      __android_log_print(ANDROID_LOG_ERROR, kCrashTag,
                          "  #%02d pc %p %s (%s+0x%zx)",
                          i, addrs[i], info.dli_fname, sym,
                          static_cast<size_t>(rel));
    } else {
      __android_log_print(ANDROID_LOG_ERROR, kCrashTag, "  #%02d pc %p", i, addrs[i]);
    }
  }
}

static void MarkInlineAioRequired() {
  if (g_inline_aio_flag_path[0] == '\0') {
    return;
  }

  int fd = open(g_inline_aio_flag_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
  if (fd < 0) {
    return;
  }

  static const char kValue[] = "1\n";
  ssize_t ignored = write(fd, kValue, sizeof(kValue) - 1);
  (void)ignored;
  close(fd);
}

static void CrashHandler(int sig, siginfo_t* info, void* ucontext) {
  if (sig == SIGILL) {
    MarkInlineAioRequired();
  }

  uintptr_t fault_pc = 0;
  if (ucontext) {
    ucontext_t* uc = static_cast<ucontext_t*>(ucontext);
    fault_pc = static_cast<uintptr_t>(uc->uc_mcontext.pc);
  }

  __android_log_print(ANDROID_LOG_ERROR, kCrashTag,
                      "Caught signal %d in tid %d fault_pc=%p si_addr=%p",
                      sig, GetTid(),
                      reinterpret_cast<void*>(fault_pc),
                      info ? info->si_addr : nullptr);

  /* Resolve the faulting function via dladdr */
  if (fault_pc) {
    Dl_info dl;
    if (dladdr(reinterpret_cast<void*>(fault_pc), &dl) && dl.dli_fname) {
      uintptr_t base = reinterpret_cast<uintptr_t>(dl.dli_fbase);
      __android_log_print(ANDROID_LOG_ERROR, kCrashTag,
                          "fault in %s (%s+0x%zx) base=%p",
                          dl.dli_sname ? dl.dli_sname : "?",
                          dl.dli_fname,
                          static_cast<size_t>(fault_pc - base),
                          reinterpret_cast<void*>(base));
    }
  }

  /* Log faulting instruction bytes if we have a PC */
  if (fault_pc) {
    uint8_t* code = reinterpret_cast<uint8_t*>(fault_pc);
    char hex[200];
    int pos = 0;
    for (int i = -8; i < 24 && pos < 190; i += 4) {
      if (i == 0) pos += snprintf(hex + pos, 200 - pos, ">> ");
      pos += snprintf(hex + pos, 200 - pos, "%02x%02x%02x%02x ",
                      code[i], code[i+1], code[i+2], code[i+3]);
    }
    __android_log_print(ANDROID_LOG_ERROR, kCrashTag,
                        "CODE[-8..+24]: %s", hex);
  }

  /* Log saved LR (x30) from the signal context */
  if (ucontext) {
    ucontext_t* uc = static_cast<ucontext_t*>(ucontext);
    uintptr_t lr = static_cast<uintptr_t>(uc->uc_mcontext.regs[30]);
    uintptr_t sp = static_cast<uintptr_t>(uc->uc_mcontext.sp);
    uintptr_t fp = static_cast<uintptr_t>(uc->uc_mcontext.regs[29]);
    __android_log_print(ANDROID_LOG_ERROR, kCrashTag,
                        "LR=%p SP=%p FP=%p",
                        reinterpret_cast<void*>(lr),
                        reinterpret_cast<void*>(sp),
                        reinterpret_cast<void*>(fp));

    /* Walk frame pointers manually for a reliable backtrace */
    __android_log_print(ANDROID_LOG_ERROR, kCrashTag, "=== FP backtrace ===");
    uintptr_t frame = fp;
    for (int i = 0; i < 30 && frame; i++) {
      uintptr_t* fp_ptr = reinterpret_cast<uintptr_t*>(frame);
      uintptr_t ret_addr = fp_ptr[1];
      Dl_info di;
      if (dladdr(reinterpret_cast<void*>(ret_addr), &di) && di.dli_fname) {
        uintptr_t base2 = reinterpret_cast<uintptr_t>(di.dli_fbase);
        __android_log_print(ANDROID_LOG_ERROR, kCrashTag,
                            "  #%02d %p %s (%s+0x%zx)", i,
                            reinterpret_cast<void*>(ret_addr),
                            di.dli_sname ? di.dli_sname : "?",
                            di.dli_fname,
                            static_cast<size_t>(ret_addr - base2));
      } else {
        __android_log_print(ANDROID_LOG_ERROR, kCrashTag,
                            "  #%02d %p (unknown)", i,
                            reinterpret_cast<void*>(ret_addr));
      }
      uintptr_t next_fp = fp_ptr[0];
      if (next_fp <= frame || next_fp == 0) break;
      frame = next_fp;
    }
    __android_log_print(ANDROID_LOG_ERROR, kCrashTag, "=== END ===");
  }

  /* Give logcat time to flush */
  usleep(200000);

  signal(sig, SIG_DFL);
  raise(sig);
}

static void InstallCrashHandlers() {
  struct sigaction sa;
  memset(&sa, 0, sizeof(sa));
  sa.sa_sigaction = CrashHandler;
  sa.sa_flags = SA_SIGINFO | SA_RESETHAND;
  sigaction(SIGABRT, &sa, nullptr);
  sigaction(SIGILL, &sa, nullptr);
  sigaction(SIGSEGV, &sa, nullptr);
}
}  // namespace

extern "C" void xemu_android_set_inline_aio_crash_flag_path(const char* path) {
  if (!path) {
    g_inline_aio_flag_path[0] = '\0';
    return;
  }

  size_t len = strnlen(path, sizeof(g_inline_aio_flag_path) - 1);
  memcpy(g_inline_aio_flag_path, path, len);
  g_inline_aio_flag_path[len] = '\0';
}

__attribute__((constructor)) static void InstallCrashHandlersOnLoad() {
  InstallCrashHandlers();
}
