/*
 * Geforce NV2A PGRAPH Vulkan Renderer - Texture Dump
 *
 * Asynchronous texture dumping to PNG files for texture replacement workflows.
 * Uses a dedicated background worker thread to avoid impacting render performance.
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

#include "qemu/osdep.h"
#include "qemu/thread.h"
#include "qemu/queue.h"
#include "qemu/atomic.h"

#include "texture_dump.h"
#include "hw/xbox/nv2a/pgraph/s3tc.h"
#include "hw/xbox/nv2a/nv2a_regs.h"
#include "xemu-xbe.h"

#include <sys/stat.h>

#define STB_IMAGE_WRITE_IMPLEMENTATION
#define STBI_WRITE_NO_STDIO
#include "stb_image_write.h"

#include <glib.h>

/* Vulkan format enum values we care about for pixel conversion */
#define VK_FMT_R8_UNORM              9
#define VK_FMT_R8G8_UNORM            16
#define VK_FMT_R8G8B8_SNORM          25
#define VK_FMT_R8G8B8A8_UNORM        37
#define VK_FMT_B8G8R8A8_UNORM        44
#define VK_FMT_A8B8G8R8_UNORM_PACK32 50
#define VK_FMT_R5G6B5_UNORM_PACK16   84
#define VK_FMT_A1R5G5B5_UNORM_PACK16 86
#define VK_FMT_A4R4G4B4_UNORM_PACK16 105
#define VK_FMT_R16_UNORM             70
#define VK_FMT_BC1_RGBA_UNORM        131
#define VK_FMT_BC2_UNORM             135
#define VK_FMT_BC3_UNORM             137

#define DUMP_QUEUE_MAX 256
#define MIN_TEXTURE_DIM 4

typedef struct TextureDumpJob {
    QSIMPLEQ_ENTRY(TextureDumpJob) entry;
    void *pixel_data;
    size_t pixel_data_size;
    uint32_t width;
    uint32_t height;
    uint32_t vk_format;
    unsigned int color_format;
    uint64_t content_hash;
} TextureDumpJob;

static struct {
    QemuThread thread;
    QemuMutex lock;
    QemuCond cond;
    QSIMPLEQ_HEAD(, TextureDumpJob) queue;
    int queue_depth;
    bool shutdown;
    bool initialized;

    /* Deduplication: set of content hashes already dumped this session */
    GHashTable *dumped_hashes;

    /* Configuration (protected by lock) */
    char dump_path[4096];

    /* Atomic enable flag for zero-cost check when disabled */
    bool enabled;
} td_state;

/* stb_image_write callback: write PNG data to FILE* */
static void stbiw_file_write(void *context, void *data, int size)
{
    FILE *f = (FILE *)context;
    fwrite(data, 1, size, f);
}

/*
 * Convert decoded texture pixels to RGBA8 for PNG output.
 * Returns a newly allocated RGBA8 buffer, or NULL if format is unsupported.
 * The caller must g_free() the returned buffer.
 */
static uint8_t *convert_to_rgba8(const void *data, size_t size,
                                  uint32_t width, uint32_t height,
                                  uint32_t vk_format)
{
    uint32_t num_pixels = width * height;
    uint8_t *rgba = g_malloc(num_pixels * 4);
    const uint8_t *src = (const uint8_t *)data;

    switch (vk_format) {
    case VK_FMT_R8G8B8A8_UNORM:
    case VK_FMT_A8B8G8R8_UNORM_PACK32:
        /* Already RGBA, direct copy */
        memcpy(rgba, src, num_pixels * 4);
        break;

    case VK_FMT_B8G8R8A8_UNORM:
        /* BGRA -> RGBA: swap R and B */
        for (uint32_t i = 0; i < num_pixels; i++) {
            rgba[i * 4 + 0] = src[i * 4 + 2]; /* R <- B */
            rgba[i * 4 + 1] = src[i * 4 + 1]; /* G */
            rgba[i * 4 + 2] = src[i * 4 + 0]; /* B <- R */
            rgba[i * 4 + 3] = src[i * 4 + 3]; /* A */
        }
        break;

    case VK_FMT_R8_UNORM:
        /* Grayscale -> RGBA */
        for (uint32_t i = 0; i < num_pixels; i++) {
            rgba[i * 4 + 0] = src[i];
            rgba[i * 4 + 1] = src[i];
            rgba[i * 4 + 2] = src[i];
            rgba[i * 4 + 3] = 255;
        }
        break;

    case VK_FMT_R8G8_UNORM:
        /* RG -> RGBA (R=R, G=G, B=0, A=255) */
        for (uint32_t i = 0; i < num_pixels; i++) {
            rgba[i * 4 + 0] = src[i * 2 + 0];
            rgba[i * 4 + 1] = src[i * 2 + 1];
            rgba[i * 4 + 2] = 0;
            rgba[i * 4 + 3] = 255;
        }
        break;

    case VK_FMT_R5G6B5_UNORM_PACK16: {
        const uint16_t *src16 = (const uint16_t *)data;
        for (uint32_t i = 0; i < num_pixels; i++) {
            uint16_t px = src16[i];
            rgba[i * 4 + 0] = (uint8_t)(((px >> 11) & 0x1F) * 255 / 31);
            rgba[i * 4 + 1] = (uint8_t)(((px >> 5) & 0x3F) * 255 / 63);
            rgba[i * 4 + 2] = (uint8_t)((px & 0x1F) * 255 / 31);
            rgba[i * 4 + 3] = 255;
        }
        break;
    }

    case VK_FMT_A1R5G5B5_UNORM_PACK16: {
        const uint16_t *src16 = (const uint16_t *)data;
        for (uint32_t i = 0; i < num_pixels; i++) {
            uint16_t px = src16[i];
            rgba[i * 4 + 0] = (uint8_t)(((px >> 10) & 0x1F) * 255 / 31);
            rgba[i * 4 + 1] = (uint8_t)(((px >> 5) & 0x1F) * 255 / 31);
            rgba[i * 4 + 2] = (uint8_t)((px & 0x1F) * 255 / 31);
            rgba[i * 4 + 3] = (px >> 15) ? 255 : 0;
        }
        break;
    }

    case VK_FMT_A4R4G4B4_UNORM_PACK16: {
        const uint16_t *src16 = (const uint16_t *)data;
        for (uint32_t i = 0; i < num_pixels; i++) {
            uint16_t px = src16[i];
            rgba[i * 4 + 0] = (uint8_t)(((px >> 8) & 0xF) * 17);
            rgba[i * 4 + 1] = (uint8_t)(((px >> 4) & 0xF) * 17);
            rgba[i * 4 + 2] = (uint8_t)((px & 0xF) * 17);
            rgba[i * 4 + 3] = (uint8_t)(((px >> 12) & 0xF) * 17);
        }
        break;
    }

    case VK_FMT_R8G8B8_SNORM: {
        /* R6G5B5 converted to R8G8B8_SNORM (3 bytes per pixel) */
        for (uint32_t i = 0; i < num_pixels; i++) {
            /* Treat as unsigned for dump purposes */
            rgba[i * 4 + 0] = src[i * 3 + 0] ^ 0x80;
            rgba[i * 4 + 1] = src[i * 3 + 1] ^ 0x80;
            rgba[i * 4 + 2] = src[i * 3 + 2] ^ 0x80;
            rgba[i * 4 + 3] = 255;
        }
        break;
    }

    default:
        /* Unsupported format: try to dump raw if it looks like 4 bpp */
        if (size == num_pixels * 4) {
            memcpy(rgba, src, num_pixels * 4);
        } else {
            g_free(rgba);
            return NULL;
        }
        break;
    }

    return rgba;
}

/*
 * Check if the color_format is a compressed DXT texture and decompress if so.
 * Returns newly allocated RGBA8 data, or NULL if not a DXT format.
 */
static uint8_t *try_decompress_dxt(const void *data, uint32_t width,
                                    uint32_t height, unsigned int color_format)
{
    enum S3TC_DECOMPRESS_FORMAT s3tc_fmt;

    switch (color_format) {
    case NV097_SET_TEXTURE_FORMAT_COLOR_L_DXT1_A1R5G5B5:
        s3tc_fmt = S3TC_DECOMPRESS_FORMAT_DXT1;
        break;
    case NV097_SET_TEXTURE_FORMAT_COLOR_L_DXT23_A8R8G8B8:
        s3tc_fmt = S3TC_DECOMPRESS_FORMAT_DXT3;
        break;
    case NV097_SET_TEXTURE_FORMAT_COLOR_L_DXT45_A8R8G8B8:
        s3tc_fmt = S3TC_DECOMPRESS_FORMAT_DXT5;
        break;
    default:
        return NULL;
    }

    return s3tc_decompress_2d(s3tc_fmt, (const uint8_t *)data, width, height);
}

static void process_dump_job(TextureDumpJob *job)
{
    uint8_t *rgba = NULL;

    /* Try DXT decompression first (for native BC / compute BC paths) */
    rgba = try_decompress_dxt(job->pixel_data, job->width, job->height,
                               job->color_format);

    /* Fall back to regular pixel format conversion */
    if (!rgba) {
        rgba = convert_to_rgba8(job->pixel_data, job->pixel_data_size,
                                 job->width, job->height, job->vk_format);
    }

    if (!rgba) {
        goto out;
    }

    /* Build per-game subfolder path using title ID from XBE certificate */
    char subdir[4200];
    qemu_mutex_lock(&td_state.lock);

    struct xbe *xbe = xemu_get_xbe_info();
    if (xbe && xbe->cert) {
        snprintf(subdir, sizeof(subdir), "%s/%08x",
                 td_state.dump_path, xbe->cert->m_titleid);
    } else {
        snprintf(subdir, sizeof(subdir), "%s/unknown",
                 td_state.dump_path);
    }

    qemu_mutex_unlock(&td_state.lock);

    /* Create subfolder if it doesn't exist */
    mkdir(subdir, 0755);

    char filename[4300];
    snprintf(filename, sizeof(filename), "%s/%016" PRIx64 ".png",
             subdir, job->content_hash);

    FILE *f = fopen(filename, "wb");
    if (f) {
        stbi_write_png_to_func(stbiw_file_write, f,
                                job->width, job->height, 4, rgba,
                                job->width * 4);
        fclose(f);
    }

    g_free(rgba);
out:
    g_free(job->pixel_data);
    g_free(job);
}

static void *texture_dump_worker_func(void *opaque)
{
    (void)opaque;

    while (true) {
        qemu_mutex_lock(&td_state.lock);

        while (QSIMPLEQ_EMPTY(&td_state.queue) && !td_state.shutdown) {
            qemu_cond_wait(&td_state.cond, &td_state.lock);
        }

        if (td_state.shutdown && QSIMPLEQ_EMPTY(&td_state.queue)) {
            qemu_mutex_unlock(&td_state.lock);
            break;
        }

        TextureDumpJob *job = QSIMPLEQ_FIRST(&td_state.queue);
        QSIMPLEQ_REMOVE_HEAD(&td_state.queue, entry);
        td_state.queue_depth--;

        qemu_mutex_unlock(&td_state.lock);

        process_dump_job(job);
    }

    return NULL;
}

void pgraph_vk_texture_dump_init(void)
{
    if (td_state.initialized) {
        return;
    }

    qemu_mutex_init(&td_state.lock);
    qemu_cond_init(&td_state.cond);
    QSIMPLEQ_INIT(&td_state.queue);
    td_state.queue_depth = 0;
    td_state.shutdown = false;
    td_state.enabled = false;
    td_state.dump_path[0] = '\0';
    td_state.dumped_hashes = g_hash_table_new(g_int64_hash, g_int64_equal);

    qemu_thread_create(&td_state.thread, "pgraph.vk.texdump",
                        texture_dump_worker_func, NULL, QEMU_THREAD_JOINABLE);

    td_state.initialized = true;

    /* Check environment variables for initial configuration */
    const char *env_enabled = getenv("XEMU_TEXTURE_DUMP");
    const char *env_path = getenv("XEMU_TEXTURE_DUMP_PATH");
    if (env_enabled && strcmp(env_enabled, "1") == 0 && env_path && env_path[0]) {
        pgraph_vk_texture_dump_set_path(env_path);
        pgraph_vk_texture_dump_set_enabled(true);
    }
}

void pgraph_vk_texture_dump_shutdown(void)
{
    if (!td_state.initialized) {
        return;
    }

    qemu_mutex_lock(&td_state.lock);
    td_state.shutdown = true;
    qemu_cond_signal(&td_state.cond);
    qemu_mutex_unlock(&td_state.lock);

    qemu_thread_join(&td_state.thread);

    /* Drain remaining jobs */
    TextureDumpJob *job;
    while ((job = QSIMPLEQ_FIRST(&td_state.queue)) != NULL) {
        QSIMPLEQ_REMOVE_HEAD(&td_state.queue, entry);
        g_free(job->pixel_data);
        g_free(job);
    }

    g_hash_table_destroy(td_state.dumped_hashes);
    qemu_mutex_destroy(&td_state.lock);
    qemu_cond_destroy(&td_state.cond);

    td_state.initialized = false;
}

void pgraph_vk_texture_dump_set_enabled(bool enabled)
{
    qatomic_set(&td_state.enabled, enabled);
}

bool pgraph_vk_texture_dump_is_enabled(void)
{
    return qatomic_read(&td_state.enabled);
}

void pgraph_vk_texture_dump_set_path(const char *path)
{
    if (!path) {
        return;
    }
    qemu_mutex_lock(&td_state.lock);
    snprintf(td_state.dump_path, sizeof(td_state.dump_path), "%s", path);
    qemu_mutex_unlock(&td_state.lock);
}

void pgraph_vk_texture_dump_reset(void)
{
    qemu_mutex_lock(&td_state.lock);
    g_hash_table_remove_all(td_state.dumped_hashes);
    qemu_mutex_unlock(&td_state.lock);
}

void pgraph_vk_texture_dump_maybe_enqueue(uint64_t content_hash,
                                           const void *data, size_t size,
                                           uint32_t width, uint32_t height,
                                           uint32_t vk_format,
                                           unsigned int color_format)
{
    if (!qatomic_read(&td_state.enabled) || content_hash == 0) {
        return;
    }

    /* Skip tiny textures (likely internal/dummy) */
    if (width < MIN_TEXTURE_DIM || height < MIN_TEXTURE_DIM) {
        return;
    }

    if (!data || size == 0) {
        return;
    }

    qemu_mutex_lock(&td_state.lock);

    /* Check if dump path is set */
    if (td_state.dump_path[0] == '\0') {
        qemu_mutex_unlock(&td_state.lock);
        return;
    }

    /* Deduplication: skip if already dumped */
    if (g_hash_table_contains(td_state.dumped_hashes,
                               &content_hash)) {
        qemu_mutex_unlock(&td_state.lock);
        return;
    }

    /* Queue overflow protection */
    if (td_state.queue_depth >= DUMP_QUEUE_MAX) {
        qemu_mutex_unlock(&td_state.lock);
        return;
    }

    /* Record hash as dumped (allocate key so it persists) */
    uint64_t *hash_key = g_malloc(sizeof(uint64_t));
    *hash_key = content_hash;
    g_hash_table_add(td_state.dumped_hashes, hash_key);

    qemu_mutex_unlock(&td_state.lock);

    /* Create job with a copy of the pixel data */
    TextureDumpJob *job = g_malloc(sizeof(TextureDumpJob));
    job->pixel_data = g_malloc(size);
    memcpy(job->pixel_data, data, size);
    job->pixel_data_size = size;
    job->width = width;
    job->height = height;
    job->vk_format = vk_format;
    job->color_format = color_format;
    job->content_hash = content_hash;

    qemu_mutex_lock(&td_state.lock);
    QSIMPLEQ_INSERT_TAIL(&td_state.queue, job, entry);
    td_state.queue_depth++;
    qemu_cond_signal(&td_state.cond);
    qemu_mutex_unlock(&td_state.lock);
}
