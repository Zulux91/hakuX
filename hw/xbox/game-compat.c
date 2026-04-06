/*
 * Xbox Game Compatibility Quirks
 *
 * Title ID lookup table and quirk activation logic.
 *
 * Copyright (c) 2025 rfandango
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

#include "qemu/osdep.h"
#include "hw/xbox/game-compat.h"
#include "xemu-xbe.h"

#ifdef __ANDROID__
#include <android/log.h>
#define COMPAT_LOG(fmt, ...) \
    __android_log_print(ANDROID_LOG_INFO, "hakuX-compat", fmt, ##__VA_ARGS__)
#else
#define COMPAT_LOG(fmt, ...) \
    qemu_log("game-compat: " fmt "\n", ##__VA_ARGS__)
#endif

/* Global quirk state — zero-initialized means all quirks off */
struct GameQuirks g_game_quirks;

/* Title ID of the currently active game, 0 = none detected yet */
static uint32_t s_active_title_id;

/* Throttle: only poll xemu_get_xbe_info() every N calls */
static int s_check_countdown;
#define CHECK_INTERVAL 120  /* ~2 seconds at 60fps */

/*
 * Quirk applicator functions — one per game (or game family).
 */
static void apply_fable_quirks(struct GameQuirks *q)
{
    q->fable_scene_graph_cycle_breaker = true;
}

/*
 * Quirk table: maps Xbox title IDs to quirk sets.
 *
 * Title IDs are uint32_t from the XBE certificate. The high 16 bits
 * are the publisher ID ("MS" = 0x4D53 for Microsoft Game Studios),
 * the low 16 bits are the game-specific ID.
 */
struct quirk_entry {
    uint32_t    title_id;
    const char *name;
    void      (*apply)(struct GameQuirks *q);
};

static const struct quirk_entry s_quirk_table[] = {
    { 0x4D530080, "Fable", apply_fable_quirks },
    { 0x4D53000D, "Fable", apply_fable_quirks },
    { 0x4D5300D1, "Fable: The Lost Chapters", apply_fable_quirks },
};

#define QUIRK_TABLE_SIZE (sizeof(s_quirk_table) / sizeof(s_quirk_table[0]))

void game_compat_check(void)
{
    /* Throttle: don't call xemu_get_xbe_info() every frame — it mallocs */
    if (s_active_title_id != 0) {
        return;  /* Already detected a game */
    }
    if (s_check_countdown-- > 0) {
        return;
    }
    s_check_countdown = CHECK_INTERVAL;

    struct xbe *xbe = xemu_get_xbe_info();
    if (!xbe || !xbe->cert) {
        return;  /* XBE not loaded yet */
    }

    uint32_t title_id = xbe->cert->m_titleid;
    if (title_id == 0) {
        return;  /* Invalid title ID */
    }

    s_active_title_id = title_id;

    for (size_t i = 0; i < QUIRK_TABLE_SIZE; i++) {
        if (s_quirk_table[i].title_id == title_id) {
            s_quirk_table[i].apply(&g_game_quirks);
            COMPAT_LOG("Detected '%s' (0x%08X) — quirks applied",
                       s_quirk_table[i].name, title_id);
            return;
        }
    }

    COMPAT_LOG("title_id=0x%08X — no quirks needed", title_id);
}

void game_compat_reset(void)
{
    if (s_active_title_id != 0) {
        COMPAT_LOG("Resetting quirks (was 0x%08X)", s_active_title_id);
    }
    memset(&g_game_quirks, 0, sizeof(g_game_quirks));
    s_active_title_id = 0;
    s_check_countdown = 0;
}
