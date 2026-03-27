# GL Texture Artifact Investigation

## Symptoms
Misplaced geometry, dragged/stretched textures on the OpenGL ES renderer after porting from x1_box.

## Fixes Applied (commit 427055fba3)

### Fix 1: Clear Surface Bypasses GL State Cache
**File:** `hw/xbox/nv2a/pgraph/gl/draw.c`
**Problem:** `pgraph_gl_clear_surface()` used raw `glEnable`/`glDisable`/`glScissor` calls, leaving the GL state cache stale. Next draw call skipped re-applying state.
**Fix:** Invalidate `scissor_test`, `scissor_rect`, `dither` cache fields after clear.

### Fix 2: Active Texture Unit Leak After Display Blit
**File:** `hw/xbox/nv2a/pgraph/gl/display.c`
**Problem:** `render_display_pvideo_overlay()` set `glActiveTexture(GL_TEXTURE0 + 1)` but restore didn't reset to GL_TEXTURE0.
**Fix:** Added `glActiveTexture(GL_TEXTURE0)` in restore path.

### Fix 3: Missing MAG_FILTER on Display Textures
**File:** `hw/xbox/nv2a/pgraph/gl/display.c`
**Problem:** Display buffer and pvideo textures set MIN_FILTER but not MAG_FILTER.
**Fix:** Added `glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)`.

### Fix 4: Full Cache Invalidation After Display Blit (commit 44ce0ee662)
**File:** `hw/xbox/nv2a/pgraph/gl/display.c`
**Problem:** Display restore set `glDisable(GL_BLEND/SCISSOR/DEPTH)` without telling the cache.
**Fix:** Added `memset(&r->gl_cache, -1, sizeof(r->gl_cache))` after restore.

## Verified NOT Bugs

### NEON Loop Variable in texture.c
Lines 235-275: `x = width` inside `for (x=0; x<width; x++)`. The NEON block processes all remaining pixels, writes them contiguously starting from `out`, then `x = width` exits the loop. The increment makes `x = width+1`, but `x < width` catches it. Correct.

### Scissor Y-Flip
NV2A renders to FBO, not default framebuffer. FBO origin matches NV2A convention. Y-flip only needed at display presentation (done in display.c). Same as upstream xemu. Correct.

### Vertex Attribute Buffer Offset
`attrib_data_addr` used as byte offset into GL_ARRAY_BUFFER, matching `update_memory_buffer()` upload range. Same as upstream xemu. Correct.

### GL State Cache Desync
Both clear-surface (draw.c) and display-blit (display.c) paths now properly invalidate the cache. Confirmed in code review.

## Remaining Differences from x1_box (Not Applied)

### Material Color Lighting (vsh-ff.c)
x1_box restructured ambient/emission color computation:
- **Current:** `oD0 = ambient_source; oD0.rgb *= emission; oD0.rgb += emission_source`
- **x1_box:** `oD0 = sceneAmbient * ambient_source; oD0.rgb += emission_source`

Different math — would also affect VK. Not safe to apply without testing.

### Push Constant/UBO Simplification (vsh.c)
x1_box removed `vertex_push_offset` and `ubo_set` handling. Would break VK descriptor set layout. Not applicable.

### GLSL Float Literals (vsh-ff.c, vsh-prog.c, vsh.c)
All float literal fixes (`1/length` → `1.0/length`, `2.0f` → `2.0`, etc.) are ALREADY APPLIED. Verified in code — all GLSL strings use proper float literals.

## Potential Architectural Causes (Not Point Fixes)

### CPU Format Conversions
GL uses `android_surface_guest_to_rgba8()` and `android_texture_convert_to_rgba8()` for BGRA→RGBA on Android. These introduce per-pixel CPU processing not present in VK (which uses native format support). Rounding/precision differences possible but unlikely to cause major geometry issues.

### Surface Scaling
`surface_copy_expand()` / `surface_copy_shrink()` use scalar CPU code. VK handles scaling differently (GPU-side). Could cause visual differences but not geometry displacement.

### Texture-from-Surface Slow Path
When a surface can't be aliased as a texture, GL falls back to CPU readback (`glReadPixels`) + format conversion + re-upload. This path loses any GPU-side state and may produce different results than VK's direct blit.

### GL Renderer Maturity
The GL renderer is fundamentally less mature than VK. It lacks:
- Surface aliasing optimizations (VK has `check_surface_to_texture_compatiblity`)
- Deferred surface download coalescing
- Multi-frame command buffer pipelining
- Shader pipeline caching (VK has SPIR-V binary cache)

## Next Steps

1. Compare GL vs VK output on specific games with known artifacts
2. Add GLSL shader dump logging to capture generated shaders for GL path
3. Test if the material color lighting change (vsh-ff.c) improves GL without regressing VK
4. Profile surface download frequency — if GL does more CPU readbacks, that's the bottleneck
