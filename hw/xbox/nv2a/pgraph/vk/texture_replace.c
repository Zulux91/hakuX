/*
 * Geforce NV2A PGRAPH Vulkan Renderer - Texture Replacement
 *
 * Runtime texture replacement using memory-mapped pre-decoded raw files.
 * PNGs are decoded to .raw RGBA8 cache files on first use, then mmap'd
 * for zero-copy access. The OS pages data in on demand.
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

#include "texture_replace.h"
#include "xemu-xbe.h"

#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_PNG
#include "stb_image.h"

#include <glib.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <dirent.h>
#include <fcntl.h>
#include <unistd.h>
#include <inttypes.h>

#ifdef __ANDROID__
#include <android/log.h>
#define TR_LOG(...) __android_log_print(ANDROID_LOG_INFO, "hakuX-texreplace", __VA_ARGS__)
#else
#define TR_LOG(...) fprintf(stderr, __VA_ARGS__)
#endif

/* .raw file header: 8 bytes identifying dimensions */
#define RAW_HEADER_MAGIC 0x52415734  /* "RAW4" */
typedef struct RawFileHeader {
    uint32_t magic;
    uint16_t width;
    uint16_t height;
} RawFileHeader;

#define RAW_HEADER_SIZE sizeof(RawFileHeader)

typedef struct TextureReplaceEntry {
    void *mmap_addr;      /* full mmap including header */
    size_t mmap_size;
    uint8_t *pixel_data;  /* mmap_addr + RAW_HEADER_SIZE */
    uint32_t width;
    uint32_t height;
    size_t data_size;     /* width * height * 4 */
} TextureReplaceEntry;

static struct {
    QemuThread loader_thread;
    QemuMutex lock;
    QemuCond cond;
    bool shutdown;
    bool initialized;
    bool reload_requested;
    bool loading;      /* true while loader thread is working */
    bool loaded_once;  /* true after first successful load */
    QemuCond done_cond; /* signaled when loading completes */

    GHashTable *cache;    /* uint64_t hash -> TextureReplaceEntry* */
    GHashTable *applied;  /* set of hashes already uploaded with replacement */

    char replace_path[4096];
    bool enabled;

    /* Progress callback for decode dialog */
    void (*progress_cb)(int current, int total);
} tr_state;

static void free_replace_entry(gpointer data)
{
    TextureReplaceEntry *entry = data;
    if (entry->mmap_addr && entry->mmap_addr != MAP_FAILED) {
        munmap(entry->mmap_addr, entry->mmap_size);
    }
    g_free(entry);
}

static void clear_cache_locked(void)
{
    if (tr_state.cache) {
        g_hash_table_remove_all(tr_state.cache);
    }
    if (tr_state.applied) {
        g_hash_table_remove_all(tr_state.applied);
    }
}

/*
 * Check if the .raw cache needs rebuilding.
 * Returns true if the source folder is newer than the cache timestamp.
 */
static bool cache_is_stale(const char *subdir)
{
    char ts_path[4400];
    snprintf(ts_path, sizeof(ts_path), "%s/.cache_timestamp", subdir);

    struct stat ts_stat, dir_stat;
    if (stat(ts_path, &ts_stat) != 0) {
        return true;  /* No timestamp file — cache doesn't exist */
    }
    if (stat(subdir, &dir_stat) != 0) {
        return true;
    }

    /* Stale if directory was modified after cache was built */
    return dir_stat.st_mtime > ts_stat.st_mtime;
}

static void write_cache_timestamp(const char *subdir)
{
    char ts_path[4400];
    snprintf(ts_path, sizeof(ts_path), "%s/.cache_timestamp", subdir);
    FILE *f = fopen(ts_path, "w");
    if (f) {
        fprintf(f, "cache\n");
        fclose(f);
    }
}

/*
 * Decode a PNG file to a .raw cache file (RGBA8 with header).
 * Returns true on success.
 */
static bool decode_png_to_raw(const char *png_path, const char *raw_path)
{
    int w, h, channels;
    uint8_t *pixels = stbi_load(png_path, &w, &h, &channels, 4);
    if (!pixels) {
        return false;
    }

    bool ok = false;
    FILE *f = fopen(raw_path, "wb");
    if (f) {
        RawFileHeader hdr = {
            .magic = RAW_HEADER_MAGIC,
            .width = (uint16_t)w,
            .height = (uint16_t)h,
        };
        if (fwrite(&hdr, RAW_HEADER_SIZE, 1, f) == 1 &&
            fwrite(pixels, w * h * 4, 1, f) == 1) {
            ok = true;
        }
        fclose(f);
        if (!ok) {
            unlink(raw_path);
        }
    }

    stbi_image_free(pixels);
    return ok;
}

/*
 * Memory-map a .raw file and create a cache entry.
 * Returns the entry on success, NULL on failure.
 */
static TextureReplaceEntry *mmap_raw_file(const char *raw_path, uint64_t hash)
{
    int fd = open(raw_path, O_RDONLY);
    if (fd < 0) {
        return NULL;
    }

    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size < (off_t)RAW_HEADER_SIZE) {
        close(fd);
        return NULL;
    }

    void *addr = mmap(NULL, st.st_size, PROT_READ,
                       MAP_PRIVATE | MAP_NORESERVE, fd, 0);
    close(fd);

    if (addr == MAP_FAILED) {
        return NULL;
    }

    RawFileHeader *hdr = (RawFileHeader *)addr;
    if (hdr->magic != RAW_HEADER_MAGIC) {
        munmap(addr, st.st_size);
        return NULL;
    }

    size_t expected_size = RAW_HEADER_SIZE + (size_t)hdr->width * hdr->height * 4;
    if ((size_t)st.st_size < expected_size) {
        munmap(addr, st.st_size);
        return NULL;
    }

    TextureReplaceEntry *entry = g_malloc(sizeof(TextureReplaceEntry));
    entry->mmap_addr = addr;
    entry->mmap_size = st.st_size;
    entry->pixel_data = (uint8_t *)addr + RAW_HEADER_SIZE;
    entry->width = hdr->width;
    entry->height = hdr->height;
    entry->data_size = (size_t)hdr->width * hdr->height * 4;

    return entry;
}

/*
 * Scan the game's texture subfolder, decode PNGs if needed, and mmap all .raw files.
 */
static void load_replacements(const char *subdir)
{
    TR_LOG("load_replacements: opening %s", subdir);
    DIR *dir = opendir(subdir);
    if (!dir) {
        TR_LOG("load_replacements: opendir FAILED for %s (errno=%d)", subdir, errno);
        return;
    }

    bool needs_decode = true; /* Always rebuild — Java side already synced fresh PNGs */
    TR_LOG("load_replacements: always rebuilding cache");

    /* First pass: count PNGs and log samples */
    int total_pngs = 0;
    int total_files = 0;
    {
        struct dirent *ent;
        while ((ent = readdir(dir)) != NULL) {
            total_files++;
            size_t len = strlen(ent->d_name);
            if (total_files <= 5) {
                TR_LOG("  dir entry[%d]: '%s' (len=%zu)", total_files, ent->d_name, len);
            }
            if (len > 4 && strcmp(ent->d_name + len - 4, ".png") == 0) {
                total_pngs++;
            }
        }
        TR_LOG("first pass: total_files=%d total_pngs=%d needs_decode=%d",
               total_files, total_pngs, needs_decode);
        rewinddir(dir);
    }

    if (total_pngs == 0) {
        TR_LOG("no PNG files found, closing dir");
        closedir(dir);
        return;
    }

    /* Second pass: decode PNGs to .raw if stale, then mmap all .raw files */
    int decoded = 0;
    struct dirent *ent;
    while ((ent = readdir(dir)) != NULL) {
        size_t len = strlen(ent->d_name);
        if (len < 5 || strcmp(ent->d_name + len - 4, ".png") != 0) {
            continue;
        }

        /* Parse hash from filename: <16hex>.png */
        uint64_t hash = 0;
        char hash_str[17] = {0};
        size_t stem_len = len - 4;  /* filename without .png */
        if (stem_len != 16) {
            TR_LOG("skip: '%s' stem_len=%zu (expected 16)", ent->d_name, stem_len);
            continue;
        }
        memcpy(hash_str, ent->d_name, 16);
        if (sscanf(hash_str, "%016" SCNx64, &hash) != 1) {
            TR_LOG("skip: '%s' hash parse failed", ent->d_name);
            continue;
        }

        char png_path[4500], raw_path[4500];
        snprintf(png_path, sizeof(png_path), "%s/%s", subdir, ent->d_name);
        snprintf(raw_path, sizeof(raw_path), "%s/%.*s.raw",
                 subdir, (int)(len - 4), ent->d_name);

        /* Decode if .raw doesn't exist or cache is stale */
        if (needs_decode) {
            if (decoded == 0 && tr_state.progress_cb) {
                tr_state.progress_cb(0, total_pngs); /* signal start */
            }
            decode_png_to_raw(png_path, raw_path);
            decoded++;
            if (tr_state.progress_cb) {
                tr_state.progress_cb(decoded, total_pngs);
            }
        }

        /* mmap the .raw file */
        TextureReplaceEntry *entry = mmap_raw_file(raw_path, hash);
        if (entry) {
            uint64_t *key = g_malloc(sizeof(uint64_t));
            *key = hash;

            qemu_mutex_lock(&tr_state.lock);
            g_hash_table_insert(tr_state.cache, key, entry);
            qemu_mutex_unlock(&tr_state.lock);
            TR_LOG("loaded: hash=%016" PRIx64 " %ux%u from %s",
                   hash, entry->width, entry->height, raw_path);
        } else {
            TR_LOG("mmap FAILED for %s", raw_path);
        }
    }

    closedir(dir);

    TR_LOG("load_replacements: done, cache size=%u",
           g_hash_table_size(tr_state.cache));

    if (needs_decode && decoded > 0) {
        write_cache_timestamp(subdir);
    }
}

static void *texture_replace_loader_func(void *opaque)
{
    (void)opaque;

    while (true) {
        qemu_mutex_lock(&tr_state.lock);

        while (!tr_state.reload_requested && !tr_state.shutdown) {
            qemu_cond_wait(&tr_state.cond, &tr_state.lock);
        }

        if (tr_state.shutdown) {
            qemu_mutex_unlock(&tr_state.lock);
            break;
        }

        tr_state.reload_requested = false;
        tr_state.loading = true;
        char path_copy[4096];
        snprintf(path_copy, sizeof(path_copy), "%s", tr_state.replace_path);

        /* Clear existing cache */
        clear_cache_locked();
        qemu_mutex_unlock(&tr_state.lock);

        if (path_copy[0] == '\0') {
            TR_LOG("loader: path is empty, skipping");
            qemu_mutex_lock(&tr_state.lock);
            tr_state.loading = false;
            qemu_cond_broadcast(&tr_state.done_cond);
            qemu_mutex_unlock(&tr_state.lock);
            continue;
        }

        /* Build per-game subfolder path */
        char subdir[4200];
        struct xbe *xbe = xemu_get_xbe_info();
        if (xbe && xbe->cert) {
            snprintf(subdir, sizeof(subdir), "%s/%08x",
                     path_copy, xbe->cert->m_titleid);
            TR_LOG("loader: title_id=%08x subdir=%s", xbe->cert->m_titleid, subdir);
        } else {
            snprintf(subdir, sizeof(subdir), "%s/unknown", path_copy);
            TR_LOG("loader: no XBE info, using subdir=%s", subdir);
        }

        load_replacements(subdir);

        qemu_mutex_lock(&tr_state.lock);
        tr_state.loading = false;
        qemu_cond_broadcast(&tr_state.done_cond);
        qemu_mutex_unlock(&tr_state.lock);
    }

    return NULL;
}

void pgraph_vk_texture_replace_init(void)
{
    if (tr_state.initialized) {
        return;
    }

    qemu_mutex_init(&tr_state.lock);
    qemu_cond_init(&tr_state.cond);
    tr_state.shutdown = false;
    tr_state.reload_requested = false;
    tr_state.loading = false;
    tr_state.loaded_once = false;
    qemu_cond_init(&tr_state.done_cond);
    tr_state.enabled = false;
    tr_state.replace_path[0] = '\0';
    tr_state.progress_cb = NULL;
    tr_state.cache = g_hash_table_new_full(g_int64_hash, g_int64_equal,
                                            g_free, free_replace_entry);
    tr_state.applied = g_hash_table_new_full(g_int64_hash, g_int64_equal,
                                              g_free, NULL);

    qemu_thread_create(&tr_state.loader_thread, "pgraph.vk.texreplace",
                        texture_replace_loader_func, NULL, QEMU_THREAD_JOINABLE);

    tr_state.initialized = true;

#ifdef __ANDROID__
    /* Install JNI progress callback for texture decode dialog */
    extern void install_texture_decode_progress_callback(void);
    install_texture_decode_progress_callback();
#endif

    /* Check environment variables for initial configuration */
    const char *env_enabled = getenv("XEMU_TEXTURE_REPLACE");
    const char *env_path = getenv("XEMU_TEXTURE_REPLACE_PATH");
    TR_LOG("init: env XEMU_TEXTURE_REPLACE=%s XEMU_TEXTURE_REPLACE_PATH=%s",
           env_enabled ? env_enabled : "(null)",
           env_path ? env_path : "(null)");
    if (env_enabled && strcmp(env_enabled, "1") == 0 && env_path && env_path[0]) {
        TR_LOG("init: enabling replacement, path=%s (reload deferred until game loads)", env_path);
        pgraph_vk_texture_replace_set_path(env_path);
        pgraph_vk_texture_replace_set_enabled(true);
        /* Don't reload() here — XBE isn't loaded yet so title ID is unknown.
         * The first texture lookup will trigger a lazy reload. */
    } else {
        TR_LOG("init: replacement NOT enabled via env vars");
    }
}

void pgraph_vk_texture_replace_shutdown(void)
{
    if (!tr_state.initialized) {
        return;
    }

    qemu_mutex_lock(&tr_state.lock);
    tr_state.shutdown = true;
    qemu_cond_signal(&tr_state.cond);
    qemu_mutex_unlock(&tr_state.lock);

    qemu_thread_join(&tr_state.loader_thread);

    g_hash_table_destroy(tr_state.cache);
    g_hash_table_destroy(tr_state.applied);
    qemu_mutex_destroy(&tr_state.lock);
    qemu_cond_destroy(&tr_state.cond);
    qemu_cond_destroy(&tr_state.done_cond);

    tr_state.initialized = false;
}

void pgraph_vk_texture_replace_set_enabled(bool enabled)
{
    qatomic_set(&tr_state.enabled, enabled);
}

bool pgraph_vk_texture_replace_is_enabled(void)
{
    return qatomic_read(&tr_state.enabled);
}

void pgraph_vk_texture_replace_set_path(const char *path)
{
    if (!path) {
        return;
    }
    qemu_mutex_lock(&tr_state.lock);
    snprintf(tr_state.replace_path, sizeof(tr_state.replace_path), "%s", path);
    qemu_mutex_unlock(&tr_state.lock);
}

void pgraph_vk_texture_replace_reload(void)
{
    qemu_mutex_lock(&tr_state.lock);
    tr_state.reload_requested = true;
    qemu_cond_signal(&tr_state.cond);
    qemu_mutex_unlock(&tr_state.lock);
}

void pgraph_vk_texture_replace_set_progress_cb(void (*cb)(int, int))
{
    tr_state.progress_cb = cb;
}

const void *pgraph_vk_texture_replace_lookup(uint64_t content_hash,
                                              uint32_t *out_width,
                                              uint32_t *out_height,
                                              size_t *out_size)
{
    if (!qatomic_read(&tr_state.enabled) || content_hash == 0) {
        return NULL;
    }

    /* Lazy load: trigger first reload when XBE is available, then block
     * until the loader thread finishes so all replacements are ready. */
    qemu_mutex_lock(&tr_state.lock);
    if (!tr_state.loaded_once) {
        TR_LOG("lookup: triggering lazy reload (first texture access) — blocking until done");
        tr_state.loaded_once = true;
        tr_state.reload_requested = true;
        qemu_cond_signal(&tr_state.cond);
    }
    while (tr_state.loading || tr_state.reload_requested) {
        qemu_cond_wait(&tr_state.done_cond, &tr_state.lock);
    }
    TextureReplaceEntry *entry = g_hash_table_lookup(tr_state.cache,
                                                      &content_hash);
    qemu_mutex_unlock(&tr_state.lock);

    if (!entry) {
        return NULL;
    }

    TR_LOG("lookup HIT: hash=%016" PRIx64 " %ux%u",
           content_hash, entry->width, entry->height);

    if (out_width)  *out_width = entry->width;
    if (out_height) *out_height = entry->height;
    if (out_size)   *out_size = entry->data_size;

    return entry->pixel_data;
}

int pgraph_vk_texture_replace_predecode(const char *replace_path,
                                         const char *title_id,
                                         void (*progress_cb)(int, int))
{
    if (!replace_path || !title_id || !replace_path[0] || !title_id[0]) {
        return 0;
    }

    char subdir[4200];
    snprintf(subdir, sizeof(subdir), "%s/%s", replace_path, title_id);

    DIR *dir = opendir(subdir);
    if (!dir) {
        return 0;
    }

    if (!cache_is_stale(subdir)) {
        closedir(dir);
        return 0;
    }

    /* Count PNGs */
    int total_pngs = 0;
    struct dirent *ent;
    while ((ent = readdir(dir)) != NULL) {
        size_t len = strlen(ent->d_name);
        if (len == 20 && strcmp(ent->d_name + 16, ".png") == 0) {
            total_pngs++;
        }
    }
    rewinddir(dir);

    if (total_pngs == 0) {
        closedir(dir);
        return 0;
    }

    /* Decode PNGs to .raw */
    int decoded = 0;
    while ((ent = readdir(dir)) != NULL) {
        size_t len = strlen(ent->d_name);
        if (len != 20 || strcmp(ent->d_name + 16, ".png") != 0) {
            continue;
        }

        char png_path[4500], raw_path[4500];
        snprintf(png_path, sizeof(png_path), "%s/%s", subdir, ent->d_name);
        snprintf(raw_path, sizeof(raw_path), "%s/%.16s.raw", subdir, ent->d_name);

        decode_png_to_raw(png_path, raw_path);
        decoded++;
        if (progress_cb) {
            progress_cb(decoded, total_pngs);
        }
    }

    closedir(dir);

    if (decoded > 0) {
        write_cache_timestamp(subdir);
    }

    return decoded;
}

bool pgraph_vk_texture_replace_needs_upload(uint64_t content_hash)
{
    if (!qatomic_read(&tr_state.enabled) || content_hash == 0) {
        return false;
    }

    qemu_mutex_lock(&tr_state.lock);
    if (!tr_state.loaded_once) {
        tr_state.loaded_once = true;
        tr_state.reload_requested = true;
        qemu_cond_signal(&tr_state.cond);
    }
    while (tr_state.loading || tr_state.reload_requested) {
        qemu_cond_wait(&tr_state.done_cond, &tr_state.lock);
    }
    bool has_replacement = g_hash_table_contains(tr_state.cache, &content_hash);
    bool already_applied = g_hash_table_contains(tr_state.applied, &content_hash);
    qemu_mutex_unlock(&tr_state.lock);

    return has_replacement && !already_applied;
}

void pgraph_vk_texture_replace_mark_applied(uint64_t content_hash)
{
    qemu_mutex_lock(&tr_state.lock);
    uint64_t *key = g_malloc(sizeof(uint64_t));
    *key = content_hash;
    g_hash_table_add(tr_state.applied, key);
    qemu_mutex_unlock(&tr_state.lock);
}
