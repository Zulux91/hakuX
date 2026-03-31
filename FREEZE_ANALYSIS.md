# Game Freeze Root Cause Analysis

## Active Investigation: Location-Specific Freeze (master vs x1_box)

### Symptoms
- Game freezes at a **specific gameplay spot** (not time-dependent — same spot regardless of idle time)
- CPU stuck at `eip=0x800151ed`, IF=0, irq=2 (PCRTC/VBLANK only)
- `pg_intr=0x0` — NO PGRAPH interrupt pending, NOP is not involved
- `pmc_intr=0x1000000` — only PCRTC (VBLANK) pending
- `flip_active=1` in watchdog during stall
- PFIFO eventually idle (`get=put`)
- Sound continues playing, app responsive, last frame frozen
- Happens on **BOTH OpenGL and Vulkan** renderers
- Game targets **30fps** and uses **real-time clock** (not VBLANK count) for timing
- Game does NOT speed up with extra VBLANKs (unlike most other games)
- **Works perfectly on desktop xemu** at 60Hz
- **Works on x1_box** Android branch (which runs games too fast / fast-forward)

### Exhaustive Elimination (ALL tested, NONE fixed it)

| Feature | Test | Result |
|---------|------|--------|
| Adaptive VBLANK deferral | Disabled on Android (`if(0)`) | Still freezes |
| Lockless fast dispatch | Disabled on Android | Still freezes |
| PFIFO spin loop | Disabled on Android | Still freezes |
| PFIFO lock batching | Disabled on Android | Still freezes |
| Tier-1 recompilation | Fully disabled (`!defined(__ANDROID__)`) | Still freezes |
| NOP early-return | Removed (allow overwrite) | **Worse** — freezes sooner |
| Flip auto-completion | Removed | **Worse** — freezes sooner |
| NOP 1ms auto-unstall | Disabled in simple mode | Still freezes |
| NOP delivery assist | Disabled in simple mode | Still freezes |
| `flip_active` flag | Not set on Android | Still freezes |
| Aggressive IF=1 forcing | Every VBLANK for any irq | **Worse** — freezes sooner |
| TB cache hints (prewarm) | Disabled on Android | Still freezes |
| TB cache hints (record) | Disabled on Android | Still freezes |
| TB cache hints (lookup_tier) | Disabled on Android | Still freezes |
| Simple VBLANK (pure x1_box) | Timer fires PCRTC only, no safety valves | Still freezes |
| Simple VBLANK + gfx_update | Fire PCRTC from display refresh too | Renders, still freezes |
| Simple VBLANK + safety valves | Flip/NOP assists in simple callback | Still freezes |

### Key Insights

1. **Removing safety mechanisms makes it worse** — NOP early-return, flip auto-completion, and forced IF=1 are all actively helping. Without them the freeze triggers sooner.

2. **The game doesn't respond to VBLANK rate changes** — extra VBLANKs from gfx_update don't speed it up (real-time timing, not VBLANK counting). This rules out VBLANK timing as the cause.

3. **x1_box works because of overall faster execution** — no tier-1 overhead, no hint system, simpler PFIFO path. Even with all these disabled individually on master, the freeze persists — suggesting a cumulative effect or something else entirely.

4. **Pause/unpause temporarily unfreezes** — the display loop's "unsticking GPU" code clears stall flags and kicks PFIFO on resume, which breaks the deadlock. But it re-freezes shortly after.

### What x1_box Has That Master Doesn't
- VGA `gfx_update` called on every display refresh (fires PCRTC interrupt from display loop)
- No `flip_active` state variable
- No adaptive VBLANK deferral
- No VBLANK timer (uses VGA display update instead)
- No flip auto-completion / NOP assists
- No tier-1 recompilation / hint system
- No PFIFO spin loop / lock batching / lockless dispatch
- No frame timing tracking (last_flip_ns, avg_frame_ns)
- Simpler PGRAPH method dispatch (no fast-path table, no generation counters)

### What Master Has That x1_box Doesn't (ALL individually eliminated)
Every major feature was disabled individually without fixing the freeze. The cause is either:
1. A **combination** of small changes whose cumulative effect shifts CPU timing enough to trigger a game-specific bug
2. A subtle change in a file we haven't examined (GLSL shaders, translate-all.c non-hint code, system/physmem.c)
3. Something in the **build configuration** (compiler flags, optimization level)

### Remaining Untested Differences
- `accel/tcg/translator.c` — page crossing assert→graceful-return change
- `accel/tcg/translate-all.c` — tier1_consume_request/has_pending_request still runs (scans empty 64-slot table on every TB gen)
- `system/physmem.c` — memory access callback tracking (18 lines)
- `system/main.c` — exit status handling (11 lines)
- `hw/xbox/nv2a/debug.h` — profiling stats infrastructure (287 lines)
- `hw/xbox/nv2a/pgraph/glsl/psh.c` — pixel shader gen changes (473 lines)
- `hw/xbox/nv2a/pgraph/glsl/vsh.c` — vertex shader changes (40 lines)
- `hw/xbox/nv2a/pgraph/glsl/shaders.c` — shader state hashing (49 lines)
- `hw/xbox/nv2a/pgraph/pgraph.c` — register category tables, generation counters, masked method LUT (~1000 lines)

### Recommended Next Step
**git bisect** between x1_box and master to find the exact commit. ~100 commits = ~7 builds to test.

---

## Fixed Issues

### VK Pipeline Eviction Crash (FIXED)
- `pipeline_cache_entry_post_evict` assert at `draw.c:359`
- Pipeline evicted while in use by active command buffer
- **Fix:** `pre_evict` callback now checks `draw_time >= command_buffer_start_time`

### TB Cache Hint System Crashes (FIXED)
- SIGSEGV in `tb_cache_lookup_tier` and `tb_cache_record_hint`
- Stale dedup table indices after qsort reordering
- **Fix:** Bounds checks (`val <= recorded_count`), `rebuild_dedup_table()` after qsort, 4x hash buckets

### Tier-1 Promotion Churn (FIXED)
- 56000+ promotions with thresh=16, request table permanently full
- Dedup table corruption after rewarm qsort
- **Fix:** Dedup rebuild, 4x hash buckets, tier1_requests clear on flush, CF_INVALID guarded by slot, "Disabled" option in settings

### NOP Stall Timing (MITIGATED)
- `waiting_for_nop` stalls PFIFO, CPU can't acknowledge with IF=0
- **Mitigation:** 1ms PFIFO auto-unstall, VBLANK IF=1 assist, NOP early-return for duplicates

### Flip Stall (MITIGATED)
- `is_flip_stall_complete()` returns false (READ_3D == WRITE_3D)
- Guest VBLANK handler can't run with IF=0
- **Mitigation:** VBLANK auto-increments READ_3D, clears flags, kicks PFIFO

### Rewarm Freeze (FIXED)
- Inline rewarm blocks CPU 100-500ms → NOP deadlock
- **Fix:** Rewarm disabled on Android (sort + dedup rebuild only)

## Settings Added
- **Tier-1 threshold picker** with "Disabled" option (sets threshold to INT_MAX)
- **Simple VBLANK toggle** — x1_box-style VBLANK without adaptive deferral (debug/experimental)
