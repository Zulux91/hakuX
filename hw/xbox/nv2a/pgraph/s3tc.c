/*
 * S3TC Texture Decompression
 *
 * Copyright (c) 2020 Wilhelm Kovatch
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
#include <pthread.h>

#ifdef __aarch64__
#include <arm_neon.h>
#endif

#include "s3tc.h"

static void decode_bc1_colors(uint16_t c0, uint16_t c1, uint8_t r[4],
                              uint8_t g[4], uint8_t b[4], uint8_t a[16],
                              bool transparent)
{
    r[0] = ((c0 & 0xF800) >> 8) * 0xFF / 0xF8,
    g[0] = ((c0 & 0x07E0) >> 3) * 0xFF / 0xFC,
    b[0] = ((c0 & 0x001F) << 3) * 0xFF / 0xF8,
    a[0] = 255;

    r[1] = ((c1 & 0xF800) >> 8) * 0xFF / 0xF8,
    g[1] = ((c1 & 0x07E0) >> 3) * 0xFF / 0xFC,
    b[1] = ((c1 & 0x001F) << 3) * 0xFF / 0xF8,
    a[1] = 255;

    if (transparent) {
        r[2] = (r[0]+r[1])/2;
        g[2] = (g[0]+g[1])/2;
        b[2] = (b[0]+b[1])/2;
        a[2] = 255;

        r[3] = 0;
        g[3] = 0;
        b[3] = 0;
        a[3] = 0;
    } else {
        r[2] = (2*r[0]+r[1])/3;
        g[2] = (2*g[0]+g[1])/3,
        b[2] = (2*b[0]+b[1])/3;
        a[2] = 255;

        r[3] = (r[0]+2*r[1])/3;
        g[3] = (g[0]+2*g[1])/3;
        b[3] = (b[0]+2*b[1])/3;
        a[3] = 255;
    }
}

#ifdef __aarch64__
static inline uint8x8_t s3tc_make_lookup_table(uint8_t v0, uint8_t v1,
                                               uint8_t v2, uint8_t v3)
{
    uint32_t packed = v0 | ((uint32_t)v1 << 8) | ((uint32_t)v2 << 16) |
                      ((uint32_t)v3 << 24);
    return vreinterpret_u8_u32(vdup_n_u32(packed));
}

static inline uint8x8_t s3tc_make_index_vector(uint8_t packed_indices)
{
    uint64_t indices = (packed_indices & 0x03) |
                       (((uint64_t)((packed_indices >> 2) & 0x03)) << 8) |
                       (((uint64_t)((packed_indices >> 4) & 0x03)) << 16) |
                       (((uint64_t)((packed_indices >> 6) & 0x03)) << 24);
    return vcreate_u8(indices);
}

static inline uint8x8_t s3tc_load_alpha_row(const uint8_t *alpha_row)
{
    uint32_t packed = alpha_row[0] | ((uint32_t)alpha_row[1] << 8) |
                      ((uint32_t)alpha_row[2] << 16) |
                      ((uint32_t)alpha_row[3] << 24);
    return vreinterpret_u8_u32(vdup_n_u32(packed));
}

static inline void s3tc_store_rgba_row(uint8_t *dst, uint8x8_t r,
                                       uint8x8_t g, uint8x8_t b, uint8x8_t a)
{
    uint8x8_t rg = vzip1_u8(r, g);
    uint8x8_t ba = vzip1_u8(b, a);
    uint16x4_t rgba_lo =
        vzip1_u16(vreinterpret_u16_u8(rg), vreinterpret_u16_u8(ba));
    uint16x4_t rgba_hi =
        vzip2_u16(vreinterpret_u16_u8(rg), vreinterpret_u16_u8(ba));

    vst1q_u8(dst, vcombine_u8(vreinterpret_u8_u16(rgba_lo),
                              vreinterpret_u8_u16(rgba_hi)));
}

static bool write_block_to_texture_neon(uint8_t *converted_data, uint32_t indices,
                                        int i, int j, int width, int height,
                                        int z_pos_factor, const uint8_t r[4],
                                        const uint8_t g[4], const uint8_t b[4],
                                        const uint8_t a[16],
                                        bool separate_alpha)
{
    int x0 = i * 4;
    int y0 = j * 4;
    uint8x8_t r_table;
    uint8x8_t g_table;
    uint8x8_t b_table;
    uint8x8_t a_table;

    if (x0 + 4 > width || y0 + 4 > height) {
        return false;
    }

    r_table = s3tc_make_lookup_table(r[0], r[1], r[2], r[3]);
    g_table = s3tc_make_lookup_table(g[0], g[1], g[2], g[3]);
    b_table = s3tc_make_lookup_table(b[0], b[1], b[2], b[3]);
    a_table = s3tc_make_lookup_table(a[0], a[1], a[2], a[3]);

    for (int row = 0; row < 4; row++) {
        uint8_t packed_row_indices = (indices >> (row * 8)) & 0xFF;
        uint8x8_t row_indices = s3tc_make_index_vector(packed_row_indices);
        uint8x8_t row_r = vtbl1_u8(r_table, row_indices);
        uint8x8_t row_g = vtbl1_u8(g_table, row_indices);
        uint8x8_t row_b = vtbl1_u8(b_table, row_indices);
        uint8x8_t row_a = separate_alpha
                              ? s3tc_load_alpha_row(a + row * 4)
                              : vtbl1_u8(a_table, row_indices);
        uint8_t *dst = converted_data +
                       (z_pos_factor + (y0 + row) * width + x0) * 4;

        s3tc_store_rgba_row(dst, row_r, row_g, row_b, row_a);
    }

    return true;
}
#endif

static void write_block_to_texture(uint8_t *converted_data, uint32_t indices,
                                   int i, int j, int width, int height,
                                   int z_pos_factor, uint8_t r[4],
                                   uint8_t g[4], uint8_t b[4], uint8_t a[16],
                                   bool separate_alpha)
{
    int x0 = i * 4,
        y0 = j * 4;

    int x1 = x0 + 4,
        y1 = y0 + 4;

#ifdef __aarch64__
    if (write_block_to_texture_neon(converted_data, indices, i, j, width,
                                    height, z_pos_factor, r, g, b, a,
                                    separate_alpha)) {
        return;
    }
#endif

    for (int y = y0; y < y1 && y < height; y++) {
        int y_index = 4 * (y - y0);
        int z_plus_y_pos_factor = z_pos_factor + y * width;
        for (int x = x0; x < x1 && x < width; x++) {
            int xy_index = y_index + x - x0;
            uint8_t index = (indices >> 2 * xy_index) & 0x03;
            uint8_t alpha_index = separate_alpha ? xy_index : index;
            uint8_t *p = converted_data + (z_plus_y_pos_factor + x) * 4;
            *p++ = r[index];
            *p++ = g[index];
            *p++ = b[index];
            *p++ = a[alpha_index];
        }
    }
}

static void decompress_dxt1_block(const uint8_t block_data[8],
                                  uint8_t *converted_data, int i, int j,
                                  int width, int height, int z_pos_factor)
{
    uint16_t c0 = ((uint16_t*)block_data)[0],
             c1 = ((uint16_t*)block_data)[1];
    uint8_t r[4], g[4], b[4], a[16];
    decode_bc1_colors(c0, c1, r, g, b, a, c0 <= c1);

    uint32_t indices = ((uint32_t*)block_data)[1];
    write_block_to_texture(converted_data, indices,
                           i, j, width, height, z_pos_factor,
                           r, g, b, a, false);
}

static void decompress_dxt3_block(const uint8_t block_data[16],
                                  uint8_t *converted_data, int i, int j,
                                  int width, int height, int z_pos_factor)
{
    uint16_t c0 = ((uint16_t*)block_data)[4],
             c1 = ((uint16_t*)block_data)[5];
    uint8_t r[4], g[4], b[4], a[16];
    decode_bc1_colors(c0, c1, r, g, b, a, false);

    uint64_t alpha = ((uint64_t*)block_data)[0];
    for (int a_i=0; a_i < 16; a_i++) {
        a[a_i] = (((alpha >> 4*a_i) & 0x0F) << 4) * 0xFF / 0xF0;
    }

    uint32_t indices = ((uint32_t*)block_data)[3];
    write_block_to_texture(converted_data, indices,
                           i, j, width, height, z_pos_factor,
                           r, g, b, a, true);
}

static void decompress_dxt5_block(const uint8_t block_data[16],
                                  uint8_t *converted_data, int i, int j,
                                  int width, int height, int z_pos_factor)
{
    uint16_t c0 = ((uint16_t*)block_data)[4],
             c1 = ((uint16_t*)block_data)[5];
    uint8_t r[4], g[4], b[4], a[16];
    decode_bc1_colors(c0, c1, r, g, b, a, false);

    uint64_t alpha = ((uint64_t*)block_data)[0];
    uint8_t a0 = block_data[0];
    uint8_t a1 = block_data[1];
    uint8_t a_palette[8];
    a_palette[0] = a0;
    a_palette[1] = a1;
    if (a0 > a1) {
        a_palette[2] = (6*a0+1*a1)/7;
        a_palette[3] = (5*a0+2*a1)/7;
        a_palette[4] = (4*a0+3*a1)/7;
        a_palette[5] = (3*a0+4*a1)/7;
        a_palette[6] = (2*a0+5*a1)/7;
        a_palette[7] = (1*a0+6*a1)/7;
    } else {
        a_palette[2] = (4*a0+1*a1)/5;
        a_palette[3] = (3*a0+2*a1)/5;
        a_palette[4] = (2*a0+3*a1)/5;
        a_palette[5] = (1*a0+4*a1)/5;
        a_palette[6] = 0;
        a_palette[7] = 255;
    }
    for (int a_i = 0; a_i < 16; a_i++) {
        a[a_i] = a_palette[(alpha >> (16+3*a_i)) & 0x07];
    }

    uint32_t indices = ((uint32_t*)block_data)[3];
    write_block_to_texture(converted_data, indices,
                           i, j, width, height, z_pos_factor,
                           r, g, b, a, true);
}

/*
 * Parallel S3TC decompression constants.
 * Used by both 2D (split by block rows) and 3D (split by z-blocks).
 */
#define S3TC_MIN_BLOCKS_FOR_MT  128  /* ~32x32 texture = 8x8 blocks */
#define S3TC_MAX_THREADS        4

typedef struct {
    enum S3TC_DECOMPRESS_FORMAT color_format;
    const uint8_t *data;
    uint8_t *converted_data;
    unsigned int width, height, depth;
    int num_blocks_x, num_blocks_y;
    int block_size;
    int k_start, k_end;       /* z-block range for this worker */
    int data_block_offset;    /* starting block index in input data */
    int cur_depth_start;      /* starting depth slice index */
} S3tc3dWorkerArgs;

static void *s3tc_3d_worker_func(void *opaque)
{
    S3tc3dWorkerArgs *args = (S3tc3dWorkerArgs *)opaque;
    int sub_block_index = args->data_block_offset;
    int cur_depth = args->cur_depth_start;

    for (int k = args->k_start; k < args->k_end; k++) {
        int residual_depth = args->depth - cur_depth;
        int block_depth = MIN(residual_depth, 4);
        for (int j = 0; j < args->num_blocks_y; j++) {
            for (int i = 0; i < args->num_blocks_x; i++) {
                for (int slice = 0; slice < block_depth; slice++) {
                    int z_pos_factor = (cur_depth + slice) *
                                       args->width * args->height;
                    if (args->color_format == S3TC_DECOMPRESS_FORMAT_DXT1) {
                        decompress_dxt1_block(
                            args->data + 8 * sub_block_index,
                            args->converted_data, i, j,
                            args->width, args->height, z_pos_factor);
                    } else if (args->color_format == S3TC_DECOMPRESS_FORMAT_DXT3) {
                        decompress_dxt3_block(
                            args->data + 16 * sub_block_index,
                            args->converted_data, i, j,
                            args->width, args->height, z_pos_factor);
                    } else {
                        decompress_dxt5_block(
                            args->data + 16 * sub_block_index,
                            args->converted_data, i, j,
                            args->width, args->height, z_pos_factor);
                    }
                    sub_block_index++;
                }
            }
        }
        cur_depth += block_depth;
    }
    return NULL;
}

uint8_t *s3tc_decompress_3d(enum S3TC_DECOMPRESS_FORMAT color_format,
                            const uint8_t *data, unsigned int width,
                            unsigned int height, unsigned int depth)
{
    assert(width > 0);
    assert(height > 0);
    assert(depth > 0);
    unsigned int physical_width = (width + 3) & ~3,
                 physical_height = (height + 3) & ~3;
    int num_blocks_x = physical_width / 4,
        num_blocks_y = physical_height / 4,
        num_blocks_z = (depth + 3) / 4;
    int block_size = (color_format == S3TC_DECOMPRESS_FORMAT_DXT1) ? 8 : 16;
    uint8_t *converted_data = (uint8_t *)g_malloc(width * height * depth * 4);

    /* For small textures or single z-block, decompress single-threaded */
    int num_threads = num_blocks_z < S3TC_MAX_THREADS
                      ? num_blocks_z : S3TC_MAX_THREADS;
    if (num_blocks_x * num_blocks_y * num_blocks_z < S3TC_MIN_BLOCKS_FOR_MT) {
        num_threads = 1;
    }

    /* Pre-compute per-z-block offsets: block count and starting depth */
    int zblock_block_count[num_blocks_z];
    int zblock_depth_start[num_blocks_z];
    int cur_depth = 0;
    for (int k = 0; k < num_blocks_z; k++) {
        zblock_depth_start[k] = cur_depth;
        int bd = MIN((int)depth - cur_depth, 4);
        zblock_block_count[k] = num_blocks_y * num_blocks_x * bd;
        cur_depth += bd;
    }

    pthread_t threads[S3TC_MAX_THREADS];
    S3tc3dWorkerArgs thread_args[S3TC_MAX_THREADS];
    int zblocks_per_thread = num_blocks_z / num_threads;
    int remainder = num_blocks_z % num_threads;

    int k = 0, block_offset = 0;
    for (int t = 0; t < num_threads; t++) {
        int zblocks = zblocks_per_thread + (t < remainder ? 1 : 0);
        int blocks_in_range = 0;
        for (int kk = k; kk < k + zblocks; kk++) {
            blocks_in_range += zblock_block_count[kk];
        }
        thread_args[t] = (S3tc3dWorkerArgs){
            .color_format = color_format, .data = data,
            .converted_data = converted_data,
            .width = width, .height = height, .depth = depth,
            .num_blocks_x = num_blocks_x, .num_blocks_y = num_blocks_y,
            .block_size = block_size,
            .k_start = k, .k_end = k + zblocks,
            .data_block_offset = block_offset,
            .cur_depth_start = zblock_depth_start[k],
        };
        block_offset += blocks_in_range;
        k += zblocks;

        if (t < num_threads - 1) {
            pthread_create(&threads[t], NULL, s3tc_3d_worker_func,
                           &thread_args[t]);
        }
    }

    /* Last chunk runs on current thread */
    s3tc_3d_worker_func(&thread_args[num_threads - 1]);

    for (int t = 0; t < num_threads - 1; t++) {
        pthread_join(threads[t], NULL);
    }

    return converted_data;
}

typedef struct {
    enum S3TC_DECOMPRESS_FORMAT color_format;
    const uint8_t *data;
    uint8_t *converted_data;
    unsigned int width, height;
    int num_blocks_x;
    int j_start, j_end;
    int block_size;
} S3tcWorkerArgs;

static void *s3tc_worker_func(void *opaque)
{
    S3tcWorkerArgs *args = (S3tcWorkerArgs *)opaque;
    for (int j = args->j_start; j < args->j_end; j++) {
        for (int i = 0; i < args->num_blocks_x; i++) {
            int block_index = j * args->num_blocks_x + i;
            if (args->color_format == S3TC_DECOMPRESS_FORMAT_DXT1) {
                decompress_dxt1_block(args->data + 8 * block_index,
                                      args->converted_data, i, j,
                                      args->width, args->height, 0);
            } else if (args->color_format == S3TC_DECOMPRESS_FORMAT_DXT3) {
                decompress_dxt3_block(args->data + 16 * block_index,
                                      args->converted_data, i, j,
                                      args->width, args->height, 0);
            } else {
                decompress_dxt5_block(args->data + 16 * block_index,
                                      args->converted_data, i, j,
                                      args->width, args->height, 0);
            }
        }
    }
    return NULL;
}

uint8_t *s3tc_decompress_2d(enum S3TC_DECOMPRESS_FORMAT color_format,
                            const uint8_t *data, unsigned int width,
                            unsigned int height)
{
    assert(width > 0);
    assert(height > 0);
    unsigned int physical_width = (width + 3) & ~3,
                 physical_height = (height + 3) & ~3;
    int num_blocks_x = physical_width / 4, num_blocks_y = physical_height / 4;
    int total_blocks = num_blocks_x * num_blocks_y;
    uint8_t *converted_data = (uint8_t *)g_malloc(width * height * 4);

    /* For small textures, decompress single-threaded */
    if (total_blocks < S3TC_MIN_BLOCKS_FOR_MT) {
        S3tcWorkerArgs args = {
            .color_format = color_format, .data = data,
            .converted_data = converted_data,
            .width = width, .height = height,
            .num_blocks_x = num_blocks_x,
            .j_start = 0, .j_end = num_blocks_y,
        };
        s3tc_worker_func(&args);
        return converted_data;
    }

    /* Split block rows across threads */
    int num_threads = S3TC_MAX_THREADS;
    if (num_blocks_y < num_threads) num_threads = num_blocks_y;

    pthread_t threads[S3TC_MAX_THREADS];
    S3tcWorkerArgs thread_args[S3TC_MAX_THREADS];
    int rows_per_thread = num_blocks_y / num_threads;
    int remainder = num_blocks_y % num_threads;

    int j = 0;
    for (int t = 0; t < num_threads; t++) {
        int rows = rows_per_thread + (t < remainder ? 1 : 0);
        thread_args[t] = (S3tcWorkerArgs){
            .color_format = color_format, .data = data,
            .converted_data = converted_data,
            .width = width, .height = height,
            .num_blocks_x = num_blocks_x,
            .j_start = j, .j_end = j + rows,
        };
        j += rows;

        if (t < num_threads - 1) {
            pthread_create(&threads[t], NULL, s3tc_worker_func,
                           &thread_args[t]);
        }
    }

    /* Last chunk runs on current thread */
    s3tc_worker_func(&thread_args[num_threads - 1]);

    /* Wait for worker threads */
    for (int t = 0; t < num_threads - 1; t++) {
        pthread_join(threads[t], NULL);
    }

    return converted_data;
}
