# Game Freeze & Crash Root Cause Analysis

## Freeze Type 1: NOP Fence Timing Mismatch (PARTIALLY FIXED)

**Root cause:** The NV2A NOP fence mechanism (`NV097_NO_OPERATION` with non-zero parameter) is a GPU-CPU synchronization primitive. PGRAPH sets `waiting_for_nop=true`, stalling PFIFO until the CPU acknowledges the ERROR interrupt by writing to `NV_PGRAPH_INTR`. On real Xbox hardware this takes ~1µs. On Android ARM, the CPU can have IF=0 for milliseconds.

**Current fix (two-layer):**
1. **PFIFO 1ms auto-unstall** (`pfifo.c:pfifo_puller_should_stall`): After 1ms, clears `waiting_for_nop` only — PFIFO resumes. The ERROR interrupt stays pending so the CPU handler can process it properly when IF=1.
2. **VBLANK interrupt delivery assist** (`nv2a.c:nv2a_vblank_timer_cb`): Every VBLANK (~16ms), if ERROR is pending and IF=0, forces IF=1 + exit_request to deliver the interrupt through the game's handler.
3. **CPU heartbeat FORCED IF=1** (`tcg-accel-ops-mttcg.c`): Every 6s, if IF=0 with pending IRQ for 2+ heartbeats, forces IF=1.

**Status:** Partially fixed. The 1ms unstall prevents permanent PFIFO deadlock. The VBLANK assist delivers the interrupt within ~16ms. Some games still freeze because the handler runs too late — the game's rendering state machine has already timed out. The fundamental timing mismatch (1µs vs 16ms) cannot be fully bridged with safety valves alone.

**Key locations:**
- `hw/xbox/nv2a/pgraph/pgraph.c:1690-1731` — NOP handler, sets ERROR + waiting_for_nop
- `hw/xbox/nv2a/pfifo.c:238-265` — 1ms auto-unstall in puller stall check
- `hw/xbox/nv2a/nv2a.c:353-385` — VBLANK interrupt delivery assist
- `hw/xbox/nv2a/pgraph/pgraph.c:762-773` — CPU clears interrupt via MMIO write

## Freeze Type 2: `waiting_for_flip` / `flip_active` Stall (FIXED)

**Root cause:** `is_flip_stall_complete()` returns false because `SURFACE_READ_3D == SURFACE_WRITE_3D` on Android/Vulkan.

**Fix:** Safety valve clears BOTH `flip_active` AND `waiting_for_flip` after 500ms, kicks PFIFO.

**Location:** `hw/xbox/nv2a/nv2a.c` — `nv2a_vblank_timer_cb()`

## Freeze Type 3: Post-Flush GPU Idle (OPEN)

**Symptoms:** After a TB flush, `get=put` (PFIFO idle), all stall flags clear, CPU stuck at `eip=0x800151ed` with `IF=0, irq=2`. FORCED IF=1 fires but the kernel re-enters the spin loop.

**Analysis:** The kernel at 0x800151ed polls an NV2A register via MMIO. The value never satisfies the exit condition. MMIO read instrumentation added to PMC, PGRAPH, PFIFO for the range 0x80014000-0x80016000 but hasn't captured data yet (need more testing).

**Status:** Open. Need MMIO read logs to identify which register is polled.

## Crash: VK Pipeline Eviction While In Use (OPEN)

**Symptoms:** `pipeline_cache_entry_post_evict` assert at `draw.c:359` — pipeline evicted from LRU cache while still referenced by an active command buffer.

**Stack:** `pfifo_thread → pgraph_method → SET_BEGIN_END → pgraph_vk_draw_end → pgraph_vk_flush_draw → pipeline eviction → assert`

**Status:** Open. Pre-existing VK renderer bug, not related to NOP/TCG changes.

## Crash: VK NULL Pipeline Bind (OPEN)

**Symptoms:** SIGSEGV in `tu_CmdBindPipeline+124` called from `begin_draw+1124`. A NULL or invalid pipeline handle is passed to `vkCmdBindPipeline`.

**Stack:** `pfifo_thread → pgraph_method → SET_BEGIN_END → pgraph_vk_draw_end → pgraph_vk_flush_draw → begin_draw → tu_CmdBindPipeline → SIGSEGV`

**Status:** Open. Pre-existing VK renderer bug.

## Crash: Hint System Out-of-Bounds (FIXED)

**Root cause:** `dedup_table` entries pointed to stale indices in `recorded_hints` after qsort reordering.

**Fix:** Added bounds checks (`val <= recorded_count`) in `tb_cache_lookup_tier`, `tb_cache_record_hint`, and `dedup_contains_or_insert`. Also added `rebuild_dedup_table()` after qsort.

## TCG/Tier-1 Fixes Applied

- **Soft invalidation:** CF_INVALID without jump unlinking
- **Kernel-space promotion skip** (pc >= 0x80000000)
- **CF_INVALID guarded by slot availability** + logging when full
- **tier1_requests cleared on flush**
- **Dedup table rebuild after qsort** (fixes corrupted indices)
- **4x hash table buckets** (25% load factor)
- **Dedup update before max-hints cap check**
- **Tier lookup from hints** (`tb_cache_lookup_tier`)
- **Rewarm disabled on Android** (sort + dedup rebuild only)
- **Disabled option in settings** (threshold=0 → INT_MAX)
