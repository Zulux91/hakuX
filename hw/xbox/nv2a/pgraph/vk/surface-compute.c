/*
 * Geforce NV2A PGRAPH Vulkan Renderer
 *
 * Copyright (c) 2024 Matt Borgerson
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

#include "hw/xbox/nv2a/pgraph/pgraph.h"
#include "qemu/fast-hash.h"
#include "qemu/lru.h"
#include "renderer.h"
#include <vulkan/vulkan_core.h>

// TODO: Float depth format (low priority, but would be better for accuracy)

const char *pack_d24_unorm_s8_uint_to_z24s8_glsl =
    "layout(push_constant) uniform PushConstants { uint width_in, width_out; };\n"
    "layout(set = 0, binding = 0) buffer DepthIn { uint depth_in[]; };\n"
    "layout(set = 0, binding = 1) buffer StencilIn { uint stencil_in[]; };\n"
    "layout(set = 0, binding = 2) buffer DepthStencilOut { uint depth_stencil_out[]; };\n"
    "uint get_input_idx(uint idx_out) {\n"
    "    uint scale = width_in / width_out;\n"
    "    uint y = (idx_out / width_out) * scale;\n"
    "    uint x = (idx_out % width_out) * scale;\n"
    "    return y * width_in + x;\n"
    "}\n"
    "void main() {\n"
    "    uint idx_out = gl_GlobalInvocationID.x;\n"
    "    uint idx_in = get_input_idx(idx_out);\n"
    "    uint depth_value = depth_in[idx_in];\n"
    "    uint stencil_value = (stencil_in[idx_in / 4] >> ((idx_in % 4) * 8)) & 0xff;\n"
    "    depth_stencil_out[idx_out] = depth_value << 8 | stencil_value;\n"
    "}\n";

const char *unpack_z24s8_to_d24_unorm_s8_uint_glsl =
    "layout(push_constant) uniform PushConstants { uint width_in, width_out; };\n"
    "layout(set = 0, binding = 0) buffer DepthOut { uint depth_out[]; };\n"
    "layout(set = 0, binding = 1) buffer StencilOut { uint stencil_out[]; };\n"
    "layout(set = 0, binding = 2) buffer DepthStencilIn { uint depth_stencil_in[]; };\n"
    "uint get_input_idx(uint idx_out) {\n"
    "    uint scale = width_out / width_in;\n"
    "    uint y = (idx_out / width_out) / scale;\n"
    "    uint x = (idx_out % width_out) / scale;\n"
    "    return y * width_in + x;\n"
    "}\n"
    "void main() {\n"
    "    uint idx_out = gl_GlobalInvocationID.x;\n"
    "    uint idx_in = get_input_idx(idx_out);\n"
    "    depth_out[idx_out] = depth_stencil_in[idx_in] >> 8;\n"
    "    if (idx_out % 4 == 0) {\n"
    "       uint stencil_value = 0;\n"
    "       for (int i = 0; i < 4; i++) {\n" // Include next 3 pixels
    "           uint v = depth_stencil_in[get_input_idx(idx_out + i)] & 0xff;\n"
    "           stencil_value |= v << (i * 8);\n"
    "       }\n"
    "       stencil_out[idx_out / 4] = stencil_value;\n"
    "    }\n"
    "}\n";

const char *pack_d32_sfloat_s8_uint_to_z24s8_glsl =
    "layout(push_constant) uniform PushConstants { uint width_in, width_out; };\n"
    "layout(set = 0, binding = 0) buffer DepthIn { float depth_in[]; };\n"
    "layout(set = 0, binding = 1) buffer StencilIn { uint stencil_in[]; };\n"
    "layout(set = 0, binding = 2) buffer DepthStencilOut { uint depth_stencil_out[]; };\n"
    "uint get_input_idx(uint idx_out) {\n"
    "    uint scale = width_in / width_out;\n"
    "    uint y = (idx_out / width_out) * scale;\n"
    "    uint x = (idx_out % width_out) * scale;\n"
    "    return y * width_in + x;\n"
    "}\n"
    "void main() {\n"
    "    uint idx_out = gl_GlobalInvocationID.x;\n"
    "    uint idx_in = get_input_idx(idx_out);\n"
    "    uint depth_value = int(depth_in[idx_in] * float(0xffffff));\n"
    "    uint stencil_value = (stencil_in[idx_in / 4] >> ((idx_in % 4) * 8)) & 0xff;\n"
    "    depth_stencil_out[idx_out] = depth_value << 8 | stencil_value;\n"
    "}\n";

const char *unpack_z24s8_to_d32_sfloat_s8_uint_glsl =
    "layout(push_constant) uniform PushConstants { uint width_in, width_out; };\n"
    "layout(set = 0, binding = 0) buffer DepthOut { float depth_out[]; };\n"
    "layout(set = 0, binding = 1) buffer StencilOut { uint stencil_out[]; };\n"
    "layout(set = 0, binding = 2) buffer DepthStencilIn { uint depth_stencil_in[]; };\n"
    "uint get_input_idx(uint idx_out) {\n"
    "    uint scale = width_out / width_in;\n"
    "    uint y = (idx_out / width_out) / scale;\n"
    "    uint x = (idx_out % width_out) / scale;\n"
    "    return y * width_in + x;\n"
    "}\n"
    "void main() {\n"
    "    uint idx_out = gl_GlobalInvocationID.x;\n"
    "    uint idx_in = get_input_idx(idx_out);\n"
    // Conversion to float depth must be the same as in fragment shader
    "    depth_out[idx_out] = uintBitsToFloat(floatBitsToUint(float(depth_stencil_in[idx_in] >> 8) / 16777216.0) + 1u);\n"
    "    if (idx_out % 4 == 0) {\n"
    "       uint stencil_value = 0;\n"
    "       for (int i = 0; i < 4; i++) {\n" // Include next 3 pixels
    "           uint v = depth_stencil_in[get_input_idx(idx_out + i)] & 0xff;\n"
    "           stencil_value |= v << (i * 8);\n"
    "       }\n"
    "       stencil_out[idx_out / 4] = stencil_value;\n"
    "    }\n"
    "}\n";

// Direct depth pack: samples depth from image, reads stencil from buffer.
// Works for both D24_UNORM_S8_UINT and D32_SFLOAT_S8_UINT since both return
// float depth when sampled.
static const char *pack_depth_stencil_direct_glsl =
    "layout(push_constant) uniform PushConstants { uint width_in, width_out; };\n"
    "layout(set = 0, binding = 0) uniform sampler2D depth_tex;\n"
    "layout(std430, set = 0, binding = 1) readonly buffer StencilIn { uint stencil_in[]; };\n"
    "layout(std430, set = 0, binding = 2) writeonly buffer PackedOut { uint packed_out[]; };\n"
    "void main() {\n"
    "    uint idx_out = gl_GlobalInvocationID.x;\n"
    "    uint scale = width_in / width_out;\n"
    "    uint out_x = idx_out % width_out;\n"
    "    uint out_y = idx_out / width_out;\n"
    "    uint in_x = out_x * scale;\n"
    "    uint in_y = out_y * scale;\n"
    "    uint idx_in = in_y * width_in + in_x;\n"
    "    float depth = texelFetch(depth_tex, ivec2(in_x, in_y), 0).r;\n"
    "    uint depth_value = uint(depth * float(0xFFFFFF));\n"
    "    uint stencil_value = (stencil_in[idx_in / 4] >> ((idx_in % 4) * 8)) & 0xFFu;\n"
    "    packed_out[idx_out] = depth_value << 8 | stencil_value;\n"
    "}\n";

static const char *swizzle_common_glsl =
    "layout(push_constant) uniform PushConstants { uint width, height, mask_x, mask_y; };\n"
    "layout(set = 0, binding = 0) buffer DstBuf { uint dst_data[]; };\n"
    "layout(set = 0, binding = 1) buffer Unused { uint unused_data[]; };\n"
    "layout(set = 0, binding = 2) buffer SrcBuf { uint src_data[]; };\n"
    "uint swizzle_addr(uint x, uint y) {\n"
    "    uint addr = 0u;\n"
    "    uint mx = mask_x, my = mask_y;\n"
    "    for (uint bit = 1u; (mx | my) != 0u; bit <<= 1u, mx >>= 1u, my >>= 1u) {\n"
    "        if ((mx & 1u) != 0u) { addr |= (x & 1u) * bit; x >>= 1u; }\n"
    "        if ((my & 1u) != 0u) { addr |= (y & 1u) * bit; y >>= 1u; }\n"
    "    }\n"
    "    return addr;\n"
    "}\n";

static const char *swizzle_main_glsl =
    "void main() {\n"
    "    uint idx = gl_GlobalInvocationID.x;\n"
    "    if (idx >= width * height) return;\n"
    "    dst_data[swizzle_addr(idx % width, idx / width)] = src_data[idx];\n"
    "}\n";

static const char *unswizzle_main_glsl =
    "void main() {\n"
    "    uint idx = gl_GlobalInvocationID.x;\n"
    "    if (idx >= width * height) return;\n"
    "    dst_data[idx] = src_data[swizzle_addr(idx % width, idx / width)];\n"
    "}\n";

static const char *bc3_decompress_glsl =
    "layout(push_constant) uniform PushConstants {\n"
    "    uint blocks_wide, blocks_high, out_width, out_height;\n"
    "};\n"
    "layout(std430, set = 0, binding = 0) writeonly buffer DstBuf { uint dst_data[]; };\n"
    "layout(std430, set = 0, binding = 1) buffer Unused { uint unused[]; };\n"
    "layout(std430, set = 0, binding = 2) readonly buffer SrcBuf { uint src_data[]; };\n"
    "\n"
    "void main() {\n"
    "    uint block_idx = gl_GlobalInvocationID.x;\n"
    "    if (block_idx >= blocks_wide * blocks_high) return;\n"
    "\n"
    "    uint bx = block_idx % blocks_wide;\n"
    "    uint by = block_idx / blocks_wide;\n"
    "\n"
    "    // Read 16 bytes (4 uint32) of BC3 block data\n"
    "    uint d0 = src_data[block_idx * 4u + 0u];\n"
    "    uint d1 = src_data[block_idx * 4u + 1u];\n"
    "    uint d2 = src_data[block_idx * 4u + 2u];\n"
    "    uint d3 = src_data[block_idx * 4u + 3u];\n"
    "\n"
    "    // --- Alpha (bytes 0-7) ---\n"
    "    uint alpha0 = d0 & 0xFFu;\n"
    "    uint alpha1 = (d0 >> 8u) & 0xFFu;\n"
    "\n"
    "    uint a_pal[8];\n"
    "    a_pal[0] = alpha0;\n"
    "    a_pal[1] = alpha1;\n"
    "    if (alpha0 > alpha1) {\n"
    "        a_pal[2] = (6u * alpha0 + 1u * alpha1) / 7u;\n"
    "        a_pal[3] = (5u * alpha0 + 2u * alpha1) / 7u;\n"
    "        a_pal[4] = (4u * alpha0 + 3u * alpha1) / 7u;\n"
    "        a_pal[5] = (3u * alpha0 + 4u * alpha1) / 7u;\n"
    "        a_pal[6] = (2u * alpha0 + 5u * alpha1) / 7u;\n"
    "        a_pal[7] = (1u * alpha0 + 6u * alpha1) / 7u;\n"
    "    } else {\n"
    "        a_pal[2] = (4u * alpha0 + 1u * alpha1) / 5u;\n"
    "        a_pal[3] = (3u * alpha0 + 2u * alpha1) / 5u;\n"
    "        a_pal[4] = (2u * alpha0 + 3u * alpha1) / 5u;\n"
    "        a_pal[5] = (1u * alpha0 + 4u * alpha1) / 5u;\n"
    "        a_pal[6] = 0u;\n"
    "        a_pal[7] = 255u;\n"
    "    }\n"
    "\n"
    "    // 48-bit alpha index: bits [31:16] of d0 + all of d1\n"
    "    uint alpha_lo = d0 >> 16u;\n"
    "    uint alpha_hi = d1;\n"
    "\n"
    "    // --- Color (bytes 8-15) ---\n"
    "    uint c0_raw = d2 & 0xFFFFu;\n"
    "    uint c1_raw = (d2 >> 16u) & 0xFFFFu;\n"
    "\n"
    "    uint r0 = ((c0_raw >> 11u) & 0x1Fu) * 255u / 31u;\n"
    "    uint g0 = ((c0_raw >>  5u) & 0x3Fu) * 255u / 63u;\n"
    "    uint b0 = ( c0_raw         & 0x1Fu) * 255u / 31u;\n"
    "    uint r1 = ((c1_raw >> 11u) & 0x1Fu) * 255u / 31u;\n"
    "    uint g1 = ((c1_raw >>  5u) & 0x3Fu) * 255u / 63u;\n"
    "    uint b1 = ( c1_raw         & 0x1Fu) * 255u / 31u;\n"
    "\n"
    "    uint r_pal[4], g_pal[4], b_pal[4];\n"
    "    r_pal[0] = r0; g_pal[0] = g0; b_pal[0] = b0;\n"
    "    r_pal[1] = r1; g_pal[1] = g1; b_pal[1] = b1;\n"
    "    r_pal[2] = (2u*r0 + r1) / 3u;\n"
    "    g_pal[2] = (2u*g0 + g1) / 3u;\n"
    "    b_pal[2] = (2u*b0 + b1) / 3u;\n"
    "    r_pal[3] = (r0 + 2u*r1) / 3u;\n"
    "    g_pal[3] = (g0 + 2u*g1) / 3u;\n"
    "    b_pal[3] = (b0 + 2u*b1) / 3u;\n"
    "\n"
    "    uint color_indices = d3;\n"
    "\n"
    "    for (uint py = 0u; py < 4u; py++) {\n"
    "        uint oy = by * 4u + py;\n"
    "        if (oy >= out_height) break;\n"
    "        for (uint px = 0u; px < 4u; px++) {\n"
    "            uint ox = bx * 4u + px;\n"
    "            if (ox >= out_width) continue;\n"
    "            uint pixel_idx = py * 4u + px;\n"
    "\n"
    "            // Color lookup\n"
    "            uint ci = (color_indices >> (pixel_idx * 2u)) & 3u;\n"
    "\n"
    "            // Alpha lookup: 3-bit index from 48-bit table\n"
    "            uint abit = pixel_idx * 3u;\n"
    "            uint ai;\n"
    "            if (abit + 3u <= 16u) {\n"
    "                ai = (alpha_lo >> abit) & 7u;\n"
    "            } else if (abit >= 16u) {\n"
    "                ai = (alpha_hi >> (abit - 16u)) & 7u;\n"
    "            } else {\n"
    "                ai = ((alpha_lo >> abit) | (alpha_hi << (16u - abit))) & 7u;\n"
    "            }\n"
    "\n"
    "            uint rgba = r_pal[ci] | (g_pal[ci] << 8u) | (b_pal[ci] << 16u) | (a_pal[ai] << 24u);\n"
    "            dst_data[oy * out_width + ox] = rgba;\n"
    "        }\n"
    "    }\n"
    "}\n";

static gchar *get_bc3_decompress_shader_glsl(int workgroup_size)
{
    return g_strdup_printf(
        "#version 450\n"
        "layout(local_size_x = %d, local_size_y = 1, local_size_z = 1) in;\n"
        "%s", workgroup_size, bc3_decompress_glsl);
}

static gchar *get_swizzle_shader_glsl(ComputeType type, int workgroup_size)
{
    const char *main_body = (type == COMPUTE_TYPE_SWIZZLE) ?
                            swizzle_main_glsl : unswizzle_main_glsl;
    return g_strdup_printf(
        "#version 450\n"
        "layout(local_size_x = %d, local_size_y = 1, local_size_z = 1) in;\n"
        "%s%s", workgroup_size, swizzle_common_glsl, main_body);
}

static gchar *get_compute_shader_glsl(VkFormat host_fmt, bool pack,
                                      int workgroup_size)
{
    const char *template;

    switch (host_fmt) {
    case VK_FORMAT_D24_UNORM_S8_UINT:
        template = pack ? pack_d24_unorm_s8_uint_to_z24s8_glsl :
                          unpack_z24s8_to_d24_unorm_s8_uint_glsl;
        break;
    case VK_FORMAT_D32_SFLOAT_S8_UINT:
        template = pack ? pack_d32_sfloat_s8_uint_to_z24s8_glsl :
                          unpack_z24s8_to_d32_sfloat_s8_uint_glsl;
        break;
    default:
        assert(!"Unsupported host fmt");
        break;
    }
    assert(template);

    gchar *glsl = g_strdup_printf(
        "#version 450\n"
        "layout(local_size_x = %d, local_size_y = 1, local_size_z = 1) in;\n"
        "%s", workgroup_size, template);
    assert(glsl);

    return glsl;
}

static void create_descriptor_pool(PGRAPHState *pg)
{
    PGRAPHVkState *r = pg->vk_renderer_state;

    VkDescriptorPoolSize pool_sizes[] = {
        {
            .type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
            .descriptorCount = 3 * ARRAY_SIZE(r->compute.descriptor_sets),
        },
    };

    VkDescriptorPoolCreateInfo pool_info = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
        .poolSizeCount = ARRAY_SIZE(pool_sizes),
        .pPoolSizes = pool_sizes,
        .maxSets = ARRAY_SIZE(r->compute.descriptor_sets),
        .flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT,
    };
    VK_CHECK(vkCreateDescriptorPool(r->device, &pool_info, NULL,
                                    &r->compute.descriptor_pool));
}

static void destroy_descriptor_pool(PGRAPHState *pg)
{
    PGRAPHVkState *r = pg->vk_renderer_state;

    vkDestroyDescriptorPool(r->device, r->compute.descriptor_pool, NULL);
    r->compute.descriptor_pool = VK_NULL_HANDLE;
}

static void create_descriptor_set_layout(PGRAPHState *pg)
{
    PGRAPHVkState *r = pg->vk_renderer_state;

    const int num_buffers = 3;

    VkDescriptorSetLayoutBinding bindings[num_buffers];
    for (int i = 0; i < num_buffers; i++) {
        bindings[i] = (VkDescriptorSetLayoutBinding){
            .binding = i,
            .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
            .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        };
    }
    VkDescriptorSetLayoutCreateInfo layout_info = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        .bindingCount = ARRAY_SIZE(bindings),
        .pBindings = bindings,
    };
    VK_CHECK(vkCreateDescriptorSetLayout(r->device, &layout_info, NULL,
                                         &r->compute.descriptor_set_layout));
}

static void destroy_descriptor_set_layout(PGRAPHState *pg)
{
    PGRAPHVkState *r = pg->vk_renderer_state;

    vkDestroyDescriptorSetLayout(r->device, r->compute.descriptor_set_layout,
                                 NULL);
    r->compute.descriptor_set_layout = VK_NULL_HANDLE;
}

static void create_descriptor_sets(PGRAPHState *pg)
{
    PGRAPHVkState *r = pg->vk_renderer_state;

    VkDescriptorSetLayout layouts[ARRAY_SIZE(r->compute.descriptor_sets)];
    for (int i = 0; i < ARRAY_SIZE(layouts); i++) {
        layouts[i] = r->compute.descriptor_set_layout;
    }
    VkDescriptorSetAllocateInfo alloc_info = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
        .descriptorPool = r->compute.descriptor_pool,
        .descriptorSetCount = ARRAY_SIZE(r->compute.descriptor_sets),
        .pSetLayouts = layouts,
    };
    VK_CHECK(vkAllocateDescriptorSets(r->device, &alloc_info,
                                      r->compute.descriptor_sets));
}

static void destroy_descriptor_sets(PGRAPHState *pg)
{
    PGRAPHVkState *r = pg->vk_renderer_state;

    vkFreeDescriptorSets(r->device, r->compute.descriptor_pool,
                         ARRAY_SIZE(r->compute.descriptor_sets),
                         r->compute.descriptor_sets);
    for (int i = 0; i < ARRAY_SIZE(r->compute.descriptor_sets); i++) {
        r->compute.descriptor_sets[i] = VK_NULL_HANDLE;
    }
}

static void create_compute_pipeline_layout(PGRAPHState *pg)
{
    PGRAPHVkState *r = pg->vk_renderer_state;

    VkPushConstantRange push_constant_range = {
        .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        .size = 4 * sizeof(uint32_t),
    };
    VkPipelineLayoutCreateInfo pipeline_layout_info = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .setLayoutCount = 1,
        .pSetLayouts = &r->compute.descriptor_set_layout,
        .pushConstantRangeCount = 1,
        .pPushConstantRanges = &push_constant_range,
    };
    VK_CHECK(vkCreatePipelineLayout(r->device, &pipeline_layout_info, NULL,
                                    &r->compute.pipeline_layout));
}

static void destroy_compute_pipeline_layout(PGRAPHVkState *r)
{
    vkDestroyPipelineLayout(r->device, r->compute.pipeline_layout, NULL);
    r->compute.pipeline_layout = VK_NULL_HANDLE;
}

static VkPipeline create_compute_pipeline(PGRAPHVkState *r, const char *glsl,
                                          VkPipelineLayout layout)
{
    ShaderModuleInfo *module = pgraph_vk_create_shader_module_from_glsl(
        r, VK_SHADER_STAGE_COMPUTE_BIT, glsl);
    assert(module && "Compute shader GLSL compilation failed");

    VkComputePipelineCreateInfo pipeline_info = {
        .sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
        .layout = layout,
        .stage =
            (VkPipelineShaderStageCreateInfo){
                .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                .stage = VK_SHADER_STAGE_COMPUTE_BIT,
                .pName = "main",
                .module = module->module,
            },
    };
    VkPipeline pipeline;
    VK_CHECK(vkCreateComputePipelines(r->device, r->vk_pipeline_cache, 1,
                                       &pipeline_info, NULL,
                                       &pipeline));

    pgraph_vk_destroy_shader_module(r, module);

    return pipeline;
}

static void update_descriptor_sets(PGRAPHState *pg,
                                   VkDescriptorBufferInfo *buffers, int count)
{
    PGRAPHVkState *r = pg->vk_renderer_state;

    assert(count == 3);
    VkWriteDescriptorSet descriptor_writes[3];

    if (r->compute.descriptor_set_index >=
        ARRAY_SIZE(r->compute.descriptor_sets)) {
        pgraph_vk_flush_all_frames(pg);
        r->compute.descriptor_set_index = 0;
    }
    assert(r->compute.descriptor_set_index <
           ARRAY_SIZE(r->compute.descriptor_sets));

    for (int i = 0; i < count; i++) {
        descriptor_writes[i] = (VkWriteDescriptorSet){
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .dstSet =
                r->compute.descriptor_sets[r->compute.descriptor_set_index],
            .dstBinding = i,
            .dstArrayElement = 0,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
            .descriptorCount = 1,
            .pBufferInfo = &buffers[i],
        };
    }
    vkUpdateDescriptorSets(r->device, count, descriptor_writes, 0, NULL);

    r->compute.descriptor_set_index += 1;
}

bool pgraph_vk_compute_needs_finish(PGRAPHVkState *r)
{
    return (r->compute.descriptor_set_index >=
            (int)ARRAY_SIZE(r->compute.descriptor_sets)) ||
           (r->compute.direct_descriptor_set_index >=
            (int)ARRAY_SIZE(r->compute.direct_descriptor_sets));
}

void pgraph_vk_compute_finish_complete(PGRAPHVkState *r)
{
    r->compute.descriptor_set_index = 0;
    r->compute.direct_descriptor_set_index = 0;
}

static int get_workgroup_size_for_output_units(PGRAPHVkState *r, int output_units)
{
    int group_size = 1024;

    // FIXME: Smarter workgroup size calculation could factor in multiple
    //        submissions. For now we will just pick the highest number that
    //        evenly divides output_units.

    while (group_size > 1) {
        if (group_size > r->device_props.limits.maxComputeWorkGroupSize[0]) {
            group_size /= 2;
            continue;
        }
        if (output_units % group_size == 0) {
            break;
        }
        group_size /= 2;
    }

    return group_size;
}

static ComputePipeline *get_compute_pipeline(PGRAPHVkState *r, VkFormat host_fmt, bool pack, int output_units)
{
    int workgroup_size = get_workgroup_size_for_output_units(r, output_units);

    ComputePipelineKey key;
    memset(&key, 0, sizeof(key));

    key.host_fmt = host_fmt;
    key.pack = pack;
    key.workgroup_size = workgroup_size;

    LruNode *node = lru_lookup(&r->compute.pipeline_cache,
                      fast_hash((void *)&key, sizeof(key)), &key);
    ComputePipeline *pipeline = container_of(node, ComputePipeline, node);

    assert(pipeline);

    return pipeline;
}

//
// Pack depth+stencil into NV097_SET_SURFACE_FORMAT_ZETA_Z24S8
// formatted buffer with depth in bits 31-8 and stencil in bits 7-0.
//
void pgraph_vk_pack_depth_stencil(PGRAPHState *pg, SurfaceBinding *surface,
                                  VkCommandBuffer cmd, VkBuffer src,
                                  VkBuffer dst, bool downscale)
{
    PGRAPHVkState *r = pg->vk_renderer_state;

    VK_LOG("pack_depth_stencil: %ux%u fmt=%d downscale=%d",
           surface->width, surface->height,
           surface->host_fmt.vk_format, downscale);

    unsigned int input_width = surface->width, input_height = surface->height;
    pgraph_apply_scaling_factor(pg, &input_width, &input_height);

    unsigned int output_width = surface->width, output_height = surface->height;
    if (!downscale) {
        pgraph_apply_scaling_factor(pg, &output_width, &output_height);
    }

    size_t depth_bytes_per_pixel = 4;
    size_t depth_size = input_width * input_height * depth_bytes_per_pixel;

    size_t stencil_bytes_per_pixel = 1;
    size_t stencil_size = input_width * input_height * stencil_bytes_per_pixel;

    size_t output_bytes_per_pixel = 4;
    size_t output_size = output_width * output_height * output_bytes_per_pixel;

    VkDescriptorBufferInfo buffers[] = {
        {
            .buffer = src,
            .offset = 0,
            .range = depth_size,
        },
        {
            .buffer = src,
            .offset = ROUND_UP(
                depth_size,
                r->device_props.limits.minStorageBufferOffsetAlignment),
            .range = stencil_size,
        },
        {
            .buffer = dst,
            .offset = 0,
            .range = output_size,
        },
    };

    update_descriptor_sets(pg, buffers, ARRAY_SIZE(buffers));

    size_t output_size_in_units = output_width * output_height;
    ComputePipeline *pipeline = get_compute_pipeline(
        r, surface->host_fmt.vk_format, true, output_size_in_units);

    size_t workgroup_size_in_units = pipeline->key.workgroup_size;
    assert(output_size_in_units % workgroup_size_in_units == 0);
    size_t group_count = output_size_in_units / workgroup_size_in_units;

    assert(r->device_props.limits.maxComputeWorkGroupSize[0] >= workgroup_size_in_units);
    assert(r->device_props.limits.maxComputeWorkGroupCount[0] >= group_count);

    // FIXME: Smarter workgroup scaling

    pgraph_vk_begin_debug_marker(r, cmd, RGBA_PINK, __func__);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline->pipeline);
    vkCmdBindDescriptorSets(
        cmd, VK_PIPELINE_BIND_POINT_COMPUTE, r->compute.pipeline_layout, 0, 1,
        &r->compute.descriptor_sets[r->compute.descriptor_set_index - 1], 0,
        NULL);

    uint32_t push_constants[4] = { input_width, output_width, 0, 0 };
    vkCmdPushConstants(cmd, r->compute.pipeline_layout,
                       VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(push_constants),
                       push_constants);

    // FIXME: Check max group count

    vkCmdDispatch(cmd, group_count, 1, 1);
    pgraph_vk_end_debug_marker(r, cmd);
}

void pgraph_vk_unpack_depth_stencil(PGRAPHState *pg, SurfaceBinding *surface,
                                    VkCommandBuffer cmd, VkBuffer src,
                                    VkBuffer dst)
{
    PGRAPHVkState *r = pg->vk_renderer_state;

    VK_LOG("unpack_depth_stencil: %ux%u fmt=%d",
           surface->width, surface->height,
           surface->host_fmt.vk_format);

    unsigned int input_width = surface->width, input_height = surface->height;

    unsigned int output_width = surface->width, output_height = surface->height;
    pgraph_apply_scaling_factor(pg, &output_width, &output_height);

    size_t depth_bytes_per_pixel = 4;
    size_t depth_size = output_width * output_height * depth_bytes_per_pixel;

    size_t stencil_bytes_per_pixel = 1;
    size_t stencil_size = output_width * output_height * stencil_bytes_per_pixel;

    size_t input_bytes_per_pixel = 4;
    size_t input_size = input_width * input_height * input_bytes_per_pixel;

    VkDescriptorBufferInfo buffers[] = {
        {
            .buffer = dst,
            .offset = 0,
            .range = depth_size,
        },
        {
            .buffer = dst,
            .offset = ROUND_UP(
                depth_size,
                r->device_props.limits.minStorageBufferOffsetAlignment),
            .range = stencil_size,
        },
        {
            .buffer = src,
            .offset = 0,
            .range = input_size,
        },
    };
    update_descriptor_sets(pg, buffers, ARRAY_SIZE(buffers));

    size_t output_size_in_units = output_width * output_height;
    ComputePipeline *pipeline = get_compute_pipeline(
        r, surface->host_fmt.vk_format, false, output_size_in_units);

    size_t workgroup_size_in_units = pipeline->key.workgroup_size;
    assert(output_size_in_units % workgroup_size_in_units == 0);
    size_t group_count = output_size_in_units / workgroup_size_in_units;

    assert(r->device_props.limits.maxComputeWorkGroupSize[0] >= workgroup_size_in_units);
    assert(r->device_props.limits.maxComputeWorkGroupCount[0] >= group_count);

    // FIXME: Smarter workgroup scaling

    pgraph_vk_begin_debug_marker(r, cmd, RGBA_PINK, __func__);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline->pipeline);
    vkCmdBindDescriptorSets(
        cmd, VK_PIPELINE_BIND_POINT_COMPUTE, r->compute.pipeline_layout, 0, 1,
        &r->compute.descriptor_sets[r->compute.descriptor_set_index - 1], 0,
        NULL);

    assert(output_width >= input_width);
    uint32_t push_constants[4] = { input_width, output_width, 0, 0 };
    vkCmdPushConstants(cmd, r->compute.pipeline_layout,
                       VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(push_constants),
                       push_constants);
    vkCmdDispatch(cmd, group_count, 1, 1);
    pgraph_vk_end_debug_marker(r, cmd);
}

void pgraph_vk_pack_depth_stencil_direct(PGRAPHState *pg,
                                         SurfaceBinding *surface,
                                         VkCommandBuffer cmd,
                                         VkImageView depth_view,
                                         VkBuffer stencil_buf,
                                         VkDeviceSize stencil_offset,
                                         VkDeviceSize stencil_size,
                                         VkBuffer dst, bool downscale)
{
    PGRAPHVkState *r = pg->vk_renderer_state;

    unsigned int input_width = surface->width, input_height = surface->height;
    pgraph_apply_scaling_factor(pg, &input_width, &input_height);

    unsigned int output_width = surface->width, output_height = surface->height;
    if (!downscale) {
        pgraph_apply_scaling_factor(pg, &output_width, &output_height);
    }

    size_t output_size = output_width * output_height * 4;

    // Check descriptor set availability
    if (r->compute.direct_descriptor_set_index >=
        (int)ARRAY_SIZE(r->compute.direct_descriptor_sets)) {
        pgraph_vk_flush_all_frames(pg);
        r->compute.direct_descriptor_set_index = 0;
    }

    int ds_idx = r->compute.direct_descriptor_set_index;
    VkDescriptorSet ds = r->compute.direct_descriptor_sets[ds_idx];

    // Update descriptor set
    VkDescriptorImageInfo image_info = {
        .imageLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL,
        .imageView = depth_view,
        .sampler = r->compute.direct_depth_sampler,
    };
    VkDescriptorBufferInfo stencil_buf_info = {
        .buffer = stencil_buf,
        .offset = stencil_offset,
        .range = stencil_size,
    };
    VkDescriptorBufferInfo output_buf_info = {
        .buffer = dst,
        .offset = 0,
        .range = output_size,
    };
    VkWriteDescriptorSet writes[] = {
        {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .dstSet = ds,
            .dstBinding = 0,
            .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            .descriptorCount = 1,
            .pImageInfo = &image_info,
        },
        {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .dstSet = ds,
            .dstBinding = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
            .descriptorCount = 1,
            .pBufferInfo = &stencil_buf_info,
        },
        {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .dstSet = ds,
            .dstBinding = 2,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
            .descriptorCount = 1,
            .pBufferInfo = &output_buf_info,
        },
    };
    vkUpdateDescriptorSets(r->device, ARRAY_SIZE(writes), writes, 0, NULL);
    r->compute.direct_descriptor_set_index++;

    // Get or create pipeline
    size_t output_units = output_width * output_height;
    int workgroup_size = get_workgroup_size_for_output_units(r, output_units);

    ComputePipelineKey key;
    memset(&key, 0, sizeof(key));
    key.compute_type = COMPUTE_TYPE_DEPTH_STENCIL_DIRECT;
    key.pack = true;
    key.workgroup_size = workgroup_size;

    LruNode *node = lru_lookup(&r->compute.pipeline_cache,
                      fast_hash((void *)&key, sizeof(key)), &key);
    ComputePipeline *pipeline = container_of(node, ComputePipeline, node);
    assert(pipeline);

    size_t group_count = output_units / workgroup_size;

    pgraph_vk_begin_debug_marker(r, cmd, RGBA_PINK, __func__);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline->pipeline);
    vkCmdBindDescriptorSets(
        cmd, VK_PIPELINE_BIND_POINT_COMPUTE, r->compute.direct_pipeline_layout,
        0, 1, &ds, 0, NULL);

    uint32_t push_constants[4] = { input_width, output_width, 0, 0 };
    vkCmdPushConstants(cmd, r->compute.direct_pipeline_layout,
                       VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(push_constants),
                       push_constants);

    vkCmdDispatch(cmd, group_count, 1, 1);
    pgraph_vk_end_debug_marker(r, cmd);
}

void pgraph_vk_compute_swizzle(PGRAPHState *pg, VkCommandBuffer cmd,
                                VkBuffer src, size_t src_size,
                                VkBuffer dst, size_t dst_size,
                                unsigned int width, unsigned int height,
                                bool unswizzle)
{
    PGRAPHVkState *r = pg->vk_renderer_state;

    VK_LOG("compute_swizzle: %s %ux%u src_size=%zu dst_size=%zu",
           unswizzle ? "UNSWIZZLE" : "SWIZZLE", width, height,
           src_size, dst_size);

    uint32_t mask_x = 0, mask_y = 0;
    uint32_t bit = 1, mask_bit = 1;
    bool done;
    do {
        done = true;
        if (bit < width) { mask_x |= mask_bit; mask_bit <<= 1; done = false; }
        if (bit < height) { mask_y |= mask_bit; mask_bit <<= 1; done = false; }
        bit <<= 1;
    } while (!done);

    VkDescriptorBufferInfo buffers[] = {
        { .buffer = dst, .offset = 0, .range = dst_size },
        { .buffer = dst, .offset = 0, .range = dst_size },
        { .buffer = src, .offset = 0, .range = src_size },
    };
    update_descriptor_sets(pg, buffers, ARRAY_SIZE(buffers));

    size_t output_units = width * height;

    ComputePipelineKey key;
    memset(&key, 0, sizeof(key));
    key.compute_type = unswizzle ? COMPUTE_TYPE_UNSWIZZLE : COMPUTE_TYPE_SWIZZLE;
    key.workgroup_size = get_workgroup_size_for_output_units(r, output_units);

    LruNode *node = lru_lookup(&r->compute.pipeline_cache,
                      fast_hash((void *)&key, sizeof(key)), &key);
    ComputePipeline *pipeline = container_of(node, ComputePipeline, node);
    assert(pipeline);

    size_t workgroup_size_in_units = pipeline->key.workgroup_size;
    size_t group_count = output_units / workgroup_size_in_units;

    pgraph_vk_begin_debug_marker(r, cmd, RGBA_PINK, __func__);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline->pipeline);
    vkCmdBindDescriptorSets(
        cmd, VK_PIPELINE_BIND_POINT_COMPUTE, r->compute.pipeline_layout, 0, 1,
        &r->compute.descriptor_sets[r->compute.descriptor_set_index - 1], 0,
        NULL);

    uint32_t push_constants[4] = { width, height, mask_x, mask_y };
    vkCmdPushConstants(cmd, r->compute.pipeline_layout,
                       VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(push_constants),
                       push_constants);

    vkCmdDispatch(cmd, group_count, 1, 1);
    pgraph_vk_end_debug_marker(r, cmd);
}

void pgraph_vk_compute_bc3_decompress(PGRAPHState *pg, VkCommandBuffer cmd,
                                       VkBuffer src, VkDeviceSize src_offset,
                                       size_t src_size,
                                       VkBuffer dst, VkDeviceSize dst_offset,
                                       size_t dst_size,
                                       unsigned int width,
                                       unsigned int height)
{
    PGRAPHVkState *r = pg->vk_renderer_state;

    unsigned int blocks_wide = (width + 3) / 4;
    unsigned int blocks_high = (height + 3) / 4;
    size_t num_blocks = (size_t)blocks_wide * blocks_high;

    VkDescriptorBufferInfo buffers[] = {
        { .buffer = dst, .offset = dst_offset, .range = dst_size },
        { .buffer = dst, .offset = dst_offset, .range = dst_size },
        { .buffer = src, .offset = src_offset, .range = src_size },
    };
    update_descriptor_sets(pg, buffers, ARRAY_SIZE(buffers));

    ComputePipelineKey key;
    memset(&key, 0, sizeof(key));
    key.compute_type = COMPUTE_TYPE_BC3_DECOMPRESS;
    key.workgroup_size = get_workgroup_size_for_output_units(r, num_blocks);

    LruNode *node = lru_lookup(&r->compute.pipeline_cache,
                      fast_hash((void *)&key, sizeof(key)), &key);
    ComputePipeline *pipeline = container_of(node, ComputePipeline, node);
    assert(pipeline);

    size_t group_count = (num_blocks + pipeline->key.workgroup_size - 1)
                         / pipeline->key.workgroup_size;

    pgraph_vk_begin_debug_marker(r, cmd, RGBA_PINK, __func__);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline->pipeline);
    vkCmdBindDescriptorSets(
        cmd, VK_PIPELINE_BIND_POINT_COMPUTE, r->compute.pipeline_layout, 0, 1,
        &r->compute.descriptor_sets[r->compute.descriptor_set_index - 1], 0,
        NULL);

    uint32_t push_constants[4] = { blocks_wide, blocks_high, width, height };
    vkCmdPushConstants(cmd, r->compute.pipeline_layout,
                       VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(push_constants),
                       push_constants);

    vkCmdDispatch(cmd, group_count, 1, 1);
    pgraph_vk_end_debug_marker(r, cmd);
}

/*
 * BC1/BC3 texture compression compute shaders.
 *
 * Each invocation compresses one 4x4 block of RGBA8 pixels into either
 * a BC1 block (8 bytes, opaque RGB) or a BC3 block (16 bytes, with
 * separate alpha).  Input is an SSBO of uint32 (one per pixel, RGBA8),
 * output is an SSBO of uint32 (2 per BC1 block, 4 per BC3 block).
 *
 * Push constants: width, height, blocks_wide, has_alpha.
 */
static const char *bc_compress_glsl =
    "layout(push_constant) uniform PushConstants {\n"
    "    uint width;\n"
    "    uint height;\n"
    "    uint blocks_wide;\n"
    "    uint has_alpha;\n"
    "};\n"
    "layout(set = 0, binding = 0) buffer DstBuf { uint dst_data[]; };\n"
    "layout(set = 0, binding = 1) buffer Unused { uint unused_data[]; };\n"
    "layout(set = 0, binding = 2) buffer SrcBuf { uint src_data[]; };\n"
    "\n"
    "uvec4 load_pixel(uint px, uint py) {\n"
    "    px = min(px, width - 1u);\n"
    "    py = min(py, height - 1u);\n"
    "    uint rgba = src_data[py * width + px];\n"
    "    return uvec4(rgba & 0xFFu, (rgba >> 8) & 0xFFu,\n"
    "                 (rgba >> 16) & 0xFFu, (rgba >> 24) & 0xFFu);\n"
    "}\n"
    "\n"
    "uint pack_rgb565(uvec3 c) {\n"
    "    return ((c.r >> 3) << 11) | ((c.g >> 2) << 5) | (c.b >> 3);\n"
    "}\n"
    "\n"
    "void main() {\n"
    "    uint block_idx = gl_GlobalInvocationID.x;\n"
    "    uint bx = block_idx % blocks_wide;\n"
    "    uint by = block_idx / blocks_wide;\n"
    "    uint px0 = bx * 4u;\n"
    "    uint py0 = by * 4u;\n"
    "\n"
    "    uvec4 pixels[16];\n"
    "    for (uint j = 0u; j < 4u; j++)\n"
    "        for (uint i = 0u; i < 4u; i++)\n"
    "            pixels[j * 4u + i] = load_pixel(px0 + i, py0 + j);\n"
    "\n"
    "    uvec3 cmin = uvec3(255);\n"
    "    uvec3 cmax = uvec3(0);\n"
    "    for (uint k = 0u; k < 16u; k++) {\n"
    "        cmin = min(cmin, pixels[k].rgb);\n"
    "        cmax = max(cmax, pixels[k].rgb);\n"
    "    }\n"
    "\n"
    "    uvec3 inset = (cmax - cmin) / 16u;\n"
    "    cmin = min(cmin + inset, uvec3(255));\n"
    "    cmax = max(uvec3(inset), cmax) - inset;\n"
    "\n"
    "    uint c0 = pack_rgb565(cmax);\n"
    "    uint c1 = pack_rgb565(cmin);\n"
    "\n"
    "    if (c0 < c1) {\n"
    "        uint tmp = c0; c0 = c1; c1 = tmp;\n"
    "        uvec3 tv = cmax; cmax = cmin; cmin = tv;\n"
    "    } else if (c0 == c1) {\n"
    "        uint color_word = c0 | (c1 << 16);\n"
    "        if (has_alpha != 0u) {\n"
    "            uint a = pixels[0].a;\n"
    "            uint alpha_endpoints = a | (a << 8);\n"
    "            dst_data[block_idx * 4u + 0u] = alpha_endpoints;\n"
    "            dst_data[block_idx * 4u + 1u] = 0u;\n"
    "            dst_data[block_idx * 4u + 2u] = color_word;\n"
    "            dst_data[block_idx * 4u + 3u] = 0u;\n"
    "        } else {\n"
    "            dst_data[block_idx * 2u + 0u] = color_word;\n"
    "            dst_data[block_idx * 2u + 1u] = 0u;\n"
    "        }\n"
    "        return;\n"
    "    }\n"
    "\n"
    "    ivec3 palette[4];\n"
    "    palette[0] = ivec3(cmax);\n"
    "    palette[1] = ivec3(cmin);\n"
    "    palette[2] = (2 * palette[0] + palette[1] + ivec3(1)) / 3;\n"
    "    palette[3] = (palette[0] + 2 * palette[1] + ivec3(1)) / 3;\n"
    "\n"
    "    uint indices = 0u;\n"
    "    for (uint k = 0u; k < 16u; k++) {\n"
    "        ivec3 p = ivec3(pixels[k].rgb);\n"
    "        uint best = 0u;\n"
    "        int best_dist = 0x7FFFFFFF;\n"
    "        for (uint c = 0u; c < 4u; c++) {\n"
    "            ivec3 d = p - palette[c];\n"
    "            int dist = d.r * d.r + d.g * d.g + d.b * d.b;\n"
    "            if (dist < best_dist) {\n"
    "                best_dist = dist;\n"
    "                best = c;\n"
    "            }\n"
    "        }\n"
    "        indices |= best << (k * 2u);\n"
    "    }\n"
    "\n"
    "    uint color_word = c0 | (c1 << 16);\n"
    "\n"
    "    if (has_alpha != 0u) {\n"
    "        uint amin = 255u;\n"
    "        uint amax = 0u;\n"
    "        for (uint k = 0u; k < 16u; k++) {\n"
    "            amin = min(amin, pixels[k].a);\n"
    "            amax = max(amax, pixels[k].a);\n"
    "        }\n"
    "        uint a0 = amax;\n"
    "        uint a1 = amin;\n"
    "        if (a0 == a1) a0 = min(a0 + 1u, 255u);\n"
    "\n"
    "        int alp[8];\n"
    "        alp[0] = int(a0);\n"
    "        alp[1] = int(a1);\n"
    "        alp[2] = (6 * alp[0] + 1 * alp[1] + 3) / 7;\n"
    "        alp[3] = (5 * alp[0] + 2 * alp[1] + 3) / 7;\n"
    "        alp[4] = (4 * alp[0] + 3 * alp[1] + 3) / 7;\n"
    "        alp[5] = (3 * alp[0] + 4 * alp[1] + 3) / 7;\n"
    "        alp[6] = (2 * alp[0] + 5 * alp[1] + 3) / 7;\n"
    "        alp[7] = (1 * alp[0] + 6 * alp[1] + 3) / 7;\n"
    "\n"
    "        uint alpha_indices_lo = 0u;\n"
    "        uint alpha_indices_hi = 0u;\n"
    "        for (uint k = 0u; k < 16u; k++) {\n"
    "            int a = int(pixels[k].a);\n"
    "            uint best_ai = 0u;\n"
    "            int best_ad = 0x7FFFFFFF;\n"
    "            for (uint ai = 0u; ai < 8u; ai++) {\n"
    "                int ad = abs(a - alp[ai]);\n"
    "                if (ad < best_ad) { best_ad = ad; best_ai = ai; }\n"
    "            }\n"
    "            uint bit_pos = k * 3u;\n"
    "            if (bit_pos < 32u) {\n"
    "                alpha_indices_lo |= best_ai << bit_pos;\n"
    "                if (bit_pos + 3u > 32u)\n"
    "                    alpha_indices_hi |= best_ai >> (32u - bit_pos);\n"
    "            } else {\n"
    "                alpha_indices_hi |= best_ai << (bit_pos - 32u);\n"
    "            }\n"
    "        }\n"
    "\n"
    "        uint alpha_endpoints = a0 | (a1 << 8);\n"
    "        dst_data[block_idx * 4u + 0u] = alpha_endpoints | (alpha_indices_lo << 16);\n"
    "        dst_data[block_idx * 4u + 1u] = (alpha_indices_lo >> 16) | (alpha_indices_hi << 16);\n"
    "        dst_data[block_idx * 4u + 2u] = color_word;\n"
    "        dst_data[block_idx * 4u + 3u] = indices;\n"
    "    } else {\n"
    "        dst_data[block_idx * 2u + 0u] = color_word;\n"
    "        dst_data[block_idx * 2u + 1u] = indices;\n"
    "    }\n"
    "}\n";

static gchar *get_bc_compress_shader_glsl(int workgroup_size)
{
    return g_strdup_printf(
        "#version 450\n"
        "layout(local_size_x = %d, local_size_y = 1, local_size_z = 1) in;\n"
        "%s", workgroup_size, bc_compress_glsl);
}

void pgraph_vk_compress_texture_to_bc(PGRAPHState *pg, VkCommandBuffer cmd,
                                       VkBuffer src, size_t src_size,
                                       VkBuffer dst, size_t dst_size,
                                       unsigned int width, unsigned int height,
                                       bool has_alpha)
{
    PGRAPHVkState *r = pg->vk_renderer_state;

    unsigned int blocks_wide = (width + 3) / 4;
    unsigned int blocks_high = (height + 3) / 4;
    size_t num_blocks = (size_t)blocks_wide * blocks_high;

    VkDescriptorBufferInfo buffers[] = {
        { .buffer = dst, .offset = 0, .range = dst_size },
        { .buffer = dst, .offset = 0, .range = dst_size },
        { .buffer = src, .offset = 0, .range = src_size },
    };
    update_descriptor_sets(pg, buffers, ARRAY_SIZE(buffers));

    ComputePipelineKey key;
    memset(&key, 0, sizeof(key));
    key.compute_type = COMPUTE_TYPE_BC_COMPRESS;
    key.pack = has_alpha;
    key.workgroup_size = get_workgroup_size_for_output_units(r, num_blocks);

    LruNode *node = lru_lookup(&r->compute.pipeline_cache,
                      fast_hash((void *)&key, sizeof(key)), &key);
    ComputePipeline *pipeline = container_of(node, ComputePipeline, node);
    assert(pipeline);

    size_t group_count = num_blocks / pipeline->key.workgroup_size;
    if (group_count == 0) group_count = 1;

    pgraph_vk_begin_debug_marker(r, cmd, RGBA_PINK, __func__);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline->pipeline);
    vkCmdBindDescriptorSets(
        cmd, VK_PIPELINE_BIND_POINT_COMPUTE, r->compute.pipeline_layout, 0, 1,
        &r->compute.descriptor_sets[r->compute.descriptor_set_index - 1], 0,
        NULL);

    uint32_t push_constants[4] = { width, height, blocks_wide, has_alpha ? 1 : 0 };
    vkCmdPushConstants(cmd, r->compute.pipeline_layout,
                       VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(push_constants),
                       push_constants);

    vkCmdDispatch(cmd, group_count, 1, 1);
    pgraph_vk_end_debug_marker(r, cmd);
}

static void pipeline_cache_entry_init(Lru *lru, LruNode *node,
                                      const void *state)
{
    PGRAPHVkState *r = container_of(lru, PGRAPHVkState, compute.pipeline_cache);
    ComputePipeline *snode = container_of(node, ComputePipeline, node);

    memcpy(&snode->key, state, sizeof(snode->key));

    if (snode->key.workgroup_size == 1) {
        fprintf(stderr,
                "Warning: Needed compute shader with workgroup size = 1\n");
    }

    gchar *glsl;
    VkPipelineLayout layout;
    switch (snode->key.compute_type) {
    case COMPUTE_TYPE_SWIZZLE:
    case COMPUTE_TYPE_UNSWIZZLE:
        glsl = get_swizzle_shader_glsl(snode->key.compute_type,
                                       snode->key.workgroup_size);
        layout = r->compute.pipeline_layout;
        break;
    case COMPUTE_TYPE_DEPTH_STENCIL_DIRECT:
        glsl = g_strdup_printf(
            "#version 450\n"
            "layout(local_size_x = %d, local_size_y = 1, local_size_z = 1) in;\n"
            "%s", snode->key.workgroup_size, pack_depth_stencil_direct_glsl);
        layout = r->compute.direct_pipeline_layout;
        break;
    case COMPUTE_TYPE_BC3_DECOMPRESS:
        glsl = get_bc3_decompress_shader_glsl(snode->key.workgroup_size);
        layout = r->compute.pipeline_layout;
        break;
    case COMPUTE_TYPE_BC_COMPRESS:
        glsl = get_bc_compress_shader_glsl(snode->key.workgroup_size);
        layout = r->compute.pipeline_layout;
        break;
    default:
        glsl = get_compute_shader_glsl(snode->key.host_fmt, snode->key.pack,
                                       snode->key.workgroup_size);
        layout = r->compute.pipeline_layout;
        break;
    }
    assert(glsl);
    snode->pipeline = create_compute_pipeline(r, glsl, layout);
    g_free(glsl);
}

static void pipeline_cache_release_node_resources(PGRAPHVkState *r, ComputePipeline *snode)
{
    vkDestroyPipeline(r->device, snode->pipeline, NULL);
    snode->pipeline = VK_NULL_HANDLE;
}

static void pipeline_cache_entry_post_evict(Lru *lru, LruNode *node)
{
    PGRAPHVkState *r = container_of(lru, PGRAPHVkState, compute.pipeline_cache);
    ComputePipeline *snode = container_of(node, ComputePipeline, node);
    pipeline_cache_release_node_resources(r, snode);
}

static bool pipeline_cache_entry_compare(Lru *lru, LruNode *node,
                                         const void *key)
{
    ComputePipeline *snode = container_of(node, ComputePipeline, node);
    return memcmp(&snode->key, key, sizeof(ComputePipelineKey));
}

static void pipeline_cache_init(PGRAPHVkState *r)
{
    const size_t pipeline_cache_size = 100;
    lru_init(&r->compute.pipeline_cache, 256);
    r->compute.pipeline_cache_entries = g_malloc_n(pipeline_cache_size, sizeof(ComputePipeline));
    assert(r->compute.pipeline_cache_entries != NULL);
    for (int i = 0; i < pipeline_cache_size; i++) {
        lru_add_free(&r->compute.pipeline_cache, &r->compute.pipeline_cache_entries[i].node);
    }
    r->compute.pipeline_cache.init_node = pipeline_cache_entry_init;
    r->compute.pipeline_cache.compare_nodes = pipeline_cache_entry_compare;
    r->compute.pipeline_cache.post_node_evict = pipeline_cache_entry_post_evict;
}

static void pipeline_cache_finalize(PGRAPHVkState *r)
{
    lru_flush(&r->compute.pipeline_cache);
    lru_destroy(&r->compute.pipeline_cache);
    g_free(r->compute.pipeline_cache_entries);
    r->compute.pipeline_cache_entries = NULL;
}

static void init_direct_compute(PGRAPHState *pg)
{
    PGRAPHVkState *r = pg->vk_renderer_state;

    // Sampler for depth texelFetch (settings don't matter for texelFetch)
    VkSamplerCreateInfo sampler_info = {
        .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
        .magFilter = VK_FILTER_NEAREST,
        .minFilter = VK_FILTER_NEAREST,
        .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
    };
    VK_CHECK(vkCreateSampler(r->device, &sampler_info, NULL,
                             &r->compute.direct_depth_sampler));

    // Descriptor set layout: sampled image + 2 storage buffers
    VkDescriptorSetLayoutBinding bindings[3] = {
        {
            .binding = 0,
            .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
            .pImmutableSamplers = &r->compute.direct_depth_sampler,
        },
        {
            .binding = 1,
            .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
            .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        },
        {
            .binding = 2,
            .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
            .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        },
    };
    VkDescriptorSetLayoutCreateInfo layout_info = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        .bindingCount = ARRAY_SIZE(bindings),
        .pBindings = bindings,
    };
    VK_CHECK(vkCreateDescriptorSetLayout(r->device, &layout_info, NULL,
                                         &r->compute.direct_descriptor_set_layout));

    // Descriptor pool
    VkDescriptorPoolSize pool_sizes[] = {
        {
            .type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            .descriptorCount = ARRAY_SIZE(r->compute.direct_descriptor_sets),
        },
        {
            .type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
            .descriptorCount = 2 * ARRAY_SIZE(r->compute.direct_descriptor_sets),
        },
    };
    VkDescriptorPoolCreateInfo pool_info = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
        .poolSizeCount = ARRAY_SIZE(pool_sizes),
        .pPoolSizes = pool_sizes,
        .maxSets = ARRAY_SIZE(r->compute.direct_descriptor_sets),
        .flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT,
    };
    VK_CHECK(vkCreateDescriptorPool(r->device, &pool_info, NULL,
                                    &r->compute.direct_descriptor_pool));

    // Allocate descriptor sets
    VkDescriptorSetLayout layouts[ARRAY_SIZE(r->compute.direct_descriptor_sets)];
    for (int i = 0; i < ARRAY_SIZE(layouts); i++) {
        layouts[i] = r->compute.direct_descriptor_set_layout;
    }
    VkDescriptorSetAllocateInfo alloc_info = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
        .descriptorPool = r->compute.direct_descriptor_pool,
        .descriptorSetCount = ARRAY_SIZE(r->compute.direct_descriptor_sets),
        .pSetLayouts = layouts,
    };
    VK_CHECK(vkAllocateDescriptorSets(r->device, &alloc_info,
                                      r->compute.direct_descriptor_sets));

    // Pipeline layout
    VkPushConstantRange push_constant_range = {
        .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        .size = 4 * sizeof(uint32_t),
    };
    VkPipelineLayoutCreateInfo pipeline_layout_info = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .setLayoutCount = 1,
        .pSetLayouts = &r->compute.direct_descriptor_set_layout,
        .pushConstantRangeCount = 1,
        .pPushConstantRanges = &push_constant_range,
    };
    VK_CHECK(vkCreatePipelineLayout(r->device, &pipeline_layout_info, NULL,
                                    &r->compute.direct_pipeline_layout));
}

static void finalize_direct_compute(PGRAPHVkState *r)
{
    vkDestroyPipelineLayout(r->device, r->compute.direct_pipeline_layout, NULL);
    vkFreeDescriptorSets(r->device, r->compute.direct_descriptor_pool,
                         ARRAY_SIZE(r->compute.direct_descriptor_sets),
                         r->compute.direct_descriptor_sets);
    vkDestroyDescriptorPool(r->device, r->compute.direct_descriptor_pool, NULL);
    vkDestroyDescriptorSetLayout(r->device,
                                 r->compute.direct_descriptor_set_layout, NULL);
    vkDestroySampler(r->device, r->compute.direct_depth_sampler, NULL);
}

void pgraph_vk_init_compute(PGRAPHState *pg)
{
    PGRAPHVkState *r = pg->vk_renderer_state;

    VK_LOG("init_compute: begin");
    create_descriptor_pool(pg);
    create_descriptor_set_layout(pg);
    create_descriptor_sets(pg);
    create_compute_pipeline_layout(pg);
    pipeline_cache_init(r);
    init_direct_compute(pg);
    VK_LOG("init_compute: done");
}

void pgraph_vk_finalize_compute(PGRAPHState *pg)
{
    PGRAPHVkState *r = pg->vk_renderer_state;

    assert(!r->in_command_buffer);

    finalize_direct_compute(r);
    pipeline_cache_finalize(r);
    destroy_compute_pipeline_layout(r);
    destroy_descriptor_sets(pg);
    destroy_descriptor_set_layout(pg);
    destroy_descriptor_pool(pg);
}
