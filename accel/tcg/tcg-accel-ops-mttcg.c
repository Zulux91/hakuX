/*
 * QEMU TCG Multi Threaded vCPUs implementation
 *
 * Copyright (c) 2003-2008 Fabrice Bellard
 * Copyright (c) 2014 Red Hat Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

#include "qemu/osdep.h"
#include "qemu/error-report.h"
#include "system/tcg.h"
#include "system/replay.h"
#include "exec/icount.h"
#include "qemu/main-loop.h"
#include "qemu/notify.h"
#include "qemu/guest-random.h"
#include "hw/boards.h"
#include "tcg/startup.h"
#include "tcg-accel-ops.h"
#include "tcg-accel-ops-mttcg.h"
#ifdef XBOX
#include "cpu.h"
#include "hw/xbox/nv2a/nv2a_int.h"
#endif

typedef struct MttcgForceRcuNotifier {
    Notifier notifier;
    CPUState *cpu;
} MttcgForceRcuNotifier;

static void do_nothing(CPUState *cpu, run_on_cpu_data d)
{
}

static void mttcg_force_rcu(Notifier *notify, void *data)
{
    CPUState *cpu = container_of(notify, MttcgForceRcuNotifier, notifier)->cpu;

    /*
     * Called with rcu_registry_lock held, using async_run_on_cpu() ensures
     * that there are no deadlocks.
     */
    async_run_on_cpu(cpu, do_nothing, RUN_ON_CPU_NULL);
}

/*
 * In the multi-threaded case each vCPU has its own thread. The TLS
 * variable current_cpu can be used deep in the code to find the
 * current CPUState for a given thread.
 */

static void *mttcg_cpu_thread_fn(void *arg)
{
    MttcgForceRcuNotifier force_rcu;
    CPUState *cpu = arg;

    assert(tcg_enabled());
    g_assert(!icount_enabled());

    rcu_register_thread();
    force_rcu.notifier.notify = mttcg_force_rcu;
    force_rcu.cpu = cpu;
    rcu_add_force_rcu_notifier(&force_rcu.notifier);
    tcg_register_thread();

    bql_lock();
    qemu_thread_get_self(cpu->thread);

    cpu->thread_id = qemu_get_thread_id();
    cpu->neg.can_do_io = true;
    current_cpu = cpu;
    cpu_thread_signal_created(cpu);
    qemu_guest_random_seed_thread_part2(cpu->random_seed);

    do {
        qemu_process_cpu_events(cpu);

#ifdef __ANDROID__
        {
            static int64_t last_hb = 0;
            int64_t now_hb = qemu_clock_get_ns(QEMU_CLOCK_REALTIME);
            if (last_hb == 0) last_hb = now_hb;
            if (now_hb - last_hb > 3000000000LL) {
                CPUX86State *env = cpu_env(cpu);
                extern int __android_log_print(int, const char*, const char*, ...);
                {
                    int if_flag = (env->eflags & 0x200) ? 1 : 0;
                    int irq = cpu->interrupt_request;
                    extern NV2AState *g_nv2a;
                    __android_log_print(4, "hakuX-cpu-thread",
                        "hb: can_run=%d halted=%d eip=0x%x eflags=0x%x "
                        "cr0=0x%x irq=%d IF=%d "
                        "pg_intr=0x%x pmc_intr=0x%x",
                        cpu_can_run(cpu), cpu->halted,
                        (uint32_t)env->eip,
                        (uint32_t)env->eflags,
                        (uint32_t)env->cr[0],
                        irq, if_flag,
                        g_nv2a ? g_nv2a->pgraph.pending_interrupts : 0,
                        g_nv2a ? g_nv2a->pmc.pending_interrupts : 0);

                    /* Track IF=0 with pending IRQ. If this persists
                     * across multiple heartbeats, the kernel may be
                     * spin-waiting for hardware (e.g. GPU) with
                     * interrupts disabled. We force IF=1 so the
                     * VBLANK/timer interrupt can fire, but note that
                     * the kernel will re-enter its spin loop with CLI
                     * if the hardware condition hasn't resolved. */
                    static int if0_irq_count = 0;
                    if (!if_flag && irq) {
                        if0_irq_count++;
                        if (if0_irq_count >= 2) {
                            env->eflags |= 0x200; /* Set IF */
                            env->hflags &= ~HF_INHIBIT_IRQ_MASK;
                            __android_log_print(4, "hakuX-cpu-thread",
                                "FORCED IF=1 (stuck %d hb, eip=0x%x irq=%d)",
                                if0_irq_count, (uint32_t)env->eip, irq);

                            /* Dump guest x86 instructions at the stuck
                             * EIP to understand what the kernel is doing */
                            /* Generic Xbox kernel crash detection.
                             * The kernel's halt loop is CLI; HLT; JMP $-2
                             * (bytes: FA F4 EB FC).  Detect this pattern
                             * and dump full crash state. */
                            {
                                uint32_t eip = (uint32_t)env->eip;
                                if (eip >= 0x80000000) {
                                    uint32_t phys = eip & 0x0FFFFFFF;
                                    extern NV2AState *g_nv2a;
                                    if (g_nv2a && g_nv2a->vram_ptr &&
                                        phys < 64*1024*1024 - 4) {
                                        uint8_t *p = g_nv2a->vram_ptr + phys;
                                        /* Check for CLI;HLT;JMP $-2 pattern */
                                        if (p[0] == 0xFA && p[1] == 0xF4 &&
                                            p[2] == 0xEB && p[3] == 0xFC) {
                                            static uint32_t last_crash_eip = 0;
                                            if (eip != last_crash_eip) {
                                                last_crash_eip = eip;
                                                __android_log_print(2, "hakuX-crash",
                                                    "=== XBOX KERNEL CRASH (BugCheck) ===");
                                                __android_log_print(2, "hakuX-crash",
                                                    "Halt loop at EIP=0x%x", eip);
                                                __android_log_print(2, "hakuX-crash",
                                                    "BugCheck code: 0x%x",
                                                    (uint32_t)env->regs[R_EAX]);
                                                __android_log_print(2, "hakuX-crash",
                                                    "Exception: 0x%x",
                                                    (uint32_t)env->regs[R_ECX]);
                                                __android_log_print(2, "hakuX-crash",
                                                    "EAX=0x%08x EBX=0x%08x ECX=0x%08x EDX=0x%08x",
                                                    (uint32_t)env->regs[R_EAX],
                                                    (uint32_t)env->regs[R_EBX],
                                                    (uint32_t)env->regs[R_ECX],
                                                    (uint32_t)env->regs[R_EDX]);
                                                __android_log_print(2, "hakuX-crash",
                                                    "ESI=0x%08x EDI=0x%08x EBP=0x%08x ESP=0x%08x",
                                                    (uint32_t)env->regs[R_ESI],
                                                    (uint32_t)env->regs[R_EDI],
                                                    (uint32_t)env->regs[R_EBP],
                                                    (uint32_t)env->regs[R_ESP]);
                                                __android_log_print(2, "hakuX-crash",
                                                    "CR0=0x%08x CR2=0x%08x CR3=0x%08x CR4=0x%08x",
                                                    (uint32_t)env->cr[0],
                                                    (uint32_t)env->cr[2],
                                                    (uint32_t)env->cr[3],
                                                    (uint32_t)env->cr[4]);
                                                __android_log_print(2, "hakuX-crash",
                                                    "EFLAGS=0x%08x CS=0x%x DS=0x%x SS=0x%x",
                                                    (uint32_t)env->eflags,
                                                    env->segs[R_CS].selector,
                                                    env->segs[R_DS].selector,
                                                    env->segs[R_SS].selector);
                                                /* Code context: 16 bytes before + 48 after */
                                                if (phys >= 16) {
                                                    char hex[400];
                                                    int pos = 0;
                                                    uint8_t *ctx = g_nv2a->vram_ptr + phys - 16;
                                                    for (int i = 0; i < 64 && pos < 390; i++) {
                                                        if (i == 16) pos += snprintf(hex+pos, 400-pos, ">> ");
                                                        pos += snprintf(hex+pos, 400-pos, "%02x ", ctx[i]);
                                                    }
                                                    __android_log_print(2, "hakuX-crash",
                                                        "CODE[-16..+48]: %s", hex);
                                                }
                                                __android_log_print(2, "hakuX-crash",
                                                    "=== END CRASH DUMP ===");
                                            }
                                        }
                                    }
                                }
                            }

                            if0_irq_count = 0;
                        }
                    } else {
                        if0_irq_count = 0;
                    }
                }
                last_hb = now_hb;
            }
        }
#endif

        if (cpu_can_run(cpu)) {
            int r;
            bql_unlock();
            r = tcg_cpu_exec(cpu);
            bql_lock();
#ifdef XBOX
            {
                static int dbg_mttcg = 0;
                if (dbg_mttcg < 30) {
                    error_report("[MTTCG] cpu_exec returned r=%d "
                                 "halted=%d stop=%d exit_req=%d",
                                 r, cpu->halted, cpu->stop,
                                 qatomic_read(&cpu->exit_request));
                    dbg_mttcg++;
                }
            }
#endif
            switch (r) {
            case EXCP_DEBUG:
                cpu_handle_guest_debug(cpu);
                break;
            case EXCP_HALTED:
                /*
                 * Usually cpu->halted is set, but may have already been
                 * reset by another thread by the time we arrive here.
                 */
                break;
            case EXCP_ATOMIC:
                bql_unlock();
                cpu_exec_step_atomic(cpu);
                bql_lock();
            default:
                /* Ignore everything else? */
                break;
            }
        }
    } while (!cpu->unplug || cpu_can_run(cpu));

    tcg_cpu_destroy(cpu);
    bql_unlock();
    rcu_remove_force_rcu_notifier(&force_rcu.notifier);
    rcu_unregister_thread();
    return NULL;
}

void mttcg_start_vcpu_thread(CPUState *cpu)
{
    char thread_name[VCPU_THREAD_NAME_SIZE];

    g_assert(tcg_enabled());
    tcg_cpu_init_cflags(cpu, current_machine->smp.max_cpus > 1);

    /* create a thread per vCPU with TCG (MTTCG) */
    snprintf(thread_name, VCPU_THREAD_NAME_SIZE, "CPU %d/TCG",
             cpu->cpu_index);

    qemu_thread_create(cpu->thread, thread_name, mttcg_cpu_thread_fn,
                       cpu, QEMU_THREAD_JOINABLE);
}
