/*
 * Umbrella header for the nv2a_vsh_cpu subproject.
 *
 * pgraph.c includes this single header. The real implementation lives in
 * subprojects/nv2a_vsh_cpu/src/ which must be on the include path.
 */
#ifndef NV2A_VSH_EMULATOR_H
#define NV2A_VSH_EMULATOR_H

#include "nv2a_vsh_cpu.h"
#include "nv2a_vsh_disassembler.h"
#include "nv2a_vsh_emulator_execution_state.h"

#ifdef __cplusplus
extern "C" {
#endif

void nv2a_vsh_emu_execute(Nv2aVshExecutionState *state,
                          const Nv2aVshProgram *program);

void nv2a_vsh_emu_execute_track_context_writes(Nv2aVshExecutionState *state,
                                               const Nv2aVshProgram *program,
                                               bool *context_dirty);

void nv2a_vsh_emu_apply(Nv2aVshExecutionState *state, const Nv2aVshStep *step);

#ifdef __cplusplus
}
#endif

#endif /* NV2A_VSH_EMULATOR_H */
