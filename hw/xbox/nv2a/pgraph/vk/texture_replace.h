/*
 * Geforce NV2A PGRAPH Vulkan Renderer - Texture Replacement
 *
 * Runtime texture replacement using memory-mapped pre-decoded raw files.
 *
 * Copyright (c) 2025
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see <http://www.gnu.org/licenses/>.
 */

#ifndef HW_XBOX_NV2A_PGRAPH_VK_TEXTURE_REPLACE_H
#define HW_XBOX_NV2A_PGRAPH_VK_TEXTURE_REPLACE_H

#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>

void pgraph_vk_texture_replace_init(void);
void pgraph_vk_texture_replace_shutdown(void);
void pgraph_vk_texture_replace_set_enabled(bool enabled);
bool pgraph_vk_texture_replace_is_enabled(void);
void pgraph_vk_texture_replace_set_path(const char *path);
void pgraph_vk_texture_replace_reload(void);
void pgraph_vk_texture_replace_set_progress_cb(void (*cb)(int current, int total));

/*
 * Look up a replacement texture by content hash.
 * Returns mmap'd RGBA8 data pointer if a replacement exists, NULL otherwise.
 * The caller must NOT free the returned pointer (owned by the mmap cache).
 * out_width/out_height are set to the replacement dimensions.
 * out_size is set to the data size (width * height * 4).
 */
const void *pgraph_vk_texture_replace_lookup(uint64_t content_hash,
                                              uint32_t *out_width,
                                              uint32_t *out_height,
                                              size_t *out_size);

/*
 * Pre-decode PNGs to .raw cache files. Called from Java BEFORE the renderer
 * starts. This is a synchronous, blocking call. The progress callback is
 * invoked on the calling thread for each texture decoded.
 * Returns the number of textures that needed decoding (0 = cache was fresh).
 */
int pgraph_vk_texture_replace_predecode(const char *replace_path,
                                         const char *title_id,
                                         void (*progress_cb)(int current,
                                                             int total));

/* Check if a replacement exists but hasn't been applied yet */
bool pgraph_vk_texture_replace_needs_upload(uint64_t content_hash);

/* Mark a replacement as applied (prevents re-upload every frame) */
void pgraph_vk_texture_replace_mark_applied(uint64_t content_hash);

#endif
