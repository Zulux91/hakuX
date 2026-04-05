/*
 * Xbox Game Compatibility Quirks
 *
 * Data-driven system for game-specific workarounds. Each quirk is a bool
 * in GameQuirks, looked up by XBE title ID when a game loads.
 *
 * To add a quirk: add a bool here, an apply function + table entry in
 * game-compat.c, and guard the fix code with g_game_quirks.your_field.
 *
 * Copyright (c) 2025 rfandango
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

#ifndef HW_XBOX_GAME_COMPAT_H
#define HW_XBOX_GAME_COMPAT_H

#include <stdbool.h>
#include <stdint.h>

/*
 * Active game quirk flags.
 *
 * All default to false. Set by game_compat_check() when the XBE title
 * ID becomes known. Read from hot paths — a single LDRB + CBZ on ARM64.
 */
struct GameQuirks {
    /*
     * Fable: The Lost Chapters — scene graph cycle breaker.
     *
     * The game's spatial query linked list can become circular, causing
     * an infinite matching loop at guest EIP 0xbadc0. When detected,
     * set field_0f = 1 on cycling nodes to trigger the skip path.
     */
    bool fable_scene_graph_cycle_breaker;
};

extern struct GameQuirks g_game_quirks;

/* Poll for title ID and apply quirks if a new game is detected.
 * Idempotent — no-ops if title unchanged. Call from display update. */
void game_compat_check(void);

/* Reset all quirks to defaults. Call on disc eject or reset. */
void game_compat_reset(void);

#endif /* HW_XBOX_GAME_COMPAT_H */
