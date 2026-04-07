/*
 * Geforce NV2A PGRAPH Vulkan Renderer - Texture Dump
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

#ifndef HW_XBOX_NV2A_PGRAPH_VK_TEXTURE_DUMP_H
#define HW_XBOX_NV2A_PGRAPH_VK_TEXTURE_DUMP_H

#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>

void pgraph_vk_texture_dump_init(void);
void pgraph_vk_texture_dump_shutdown(void);
void pgraph_vk_texture_dump_set_enabled(bool enabled);
bool pgraph_vk_texture_dump_is_enabled(void);
void pgraph_vk_texture_dump_set_path(const char *path);
void pgraph_vk_texture_dump_reset(void);
void pgraph_vk_texture_dump_maybe_enqueue(uint64_t content_hash,
                                           const void *data, size_t size,
                                           uint32_t width, uint32_t height,
                                           uint32_t vk_format,
                                           unsigned int color_format);

#endif
