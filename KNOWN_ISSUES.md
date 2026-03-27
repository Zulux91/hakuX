# Known Issues

## Diagnostic Frame Capture Freezes Game

**Symptom:** Triggering a multi-frame diagnostic capture (e.g. 10 frames) from the pause menu causes the game to freeze indefinitely.

**Root cause:** The diag capture writes JSON draw call logs and surface dumps to disk synchronously on the pfifo render thread. Disk I/O blocks GPU command processing, stalling the entire rendering pipeline. With 100+ draw calls per frame and surface dumps per draw, the write volume overwhelms the storage bandwidth.

**Workaround:** Use single-frame captures only. Multi-frame captures may work on devices with fast storage but are unreliable.

**Fix needed:** Move diag capture I/O to a background thread with a bounded queue. The render thread should only snapshot data into memory; a writer thread flushes to disk asynchronously.

---

## VK Texture LRU Exhaustion

**Symptom:** Crash (previously assert failure) in `lru_evict_one` when all texture cache slots are in-flight. Seen in Dead or Alive 3.

**Root cause:** The VK texture cache (1024 entries) is exhausted when a game uses many textures and multiple command buffers are in-flight (triple buffering). All LRU entries are pinned by in-flight frames, leaving no evictable slot.

**Current handling:** Returns NULL and skips the texture bind for the current frame. May cause momentary texture flickering instead of a crash.

**Fix needed:** Increase texture cache size for texture-heavy games, or flush an in-flight frame to free slots when the cache is full.

---

## VK Driver Crash on Adreno (SIGSEGV in vulkan.ad07XX.so)

**Symptom:** SIGSEGV inside the Adreno Vulkan driver during `pgraph_vk_flush_draw`. Signal 11 crash with no xemu assert — the GPU driver itself segfaults.

**Root cause:** Unknown. Likely a driver bug triggered by specific command buffer sequences. Seen sporadically across games.

**Workaround:** Try switching to OpenGL ES renderer. If the crash is reproducible, clearing the shader cache may help.

---

## OpenGL ES Texture Artifacts

**Symptom:** Misplaced geometry, dragged/stretched textures on the GL renderer.

**Root cause:** The GL state cache optimization (state save/restore reduction) introduced state desync between clear/display paths and the draw path. The optimization was reverted. Artifacts may also stem from inherent GL renderer limitations (CPU-side format conversions, surface scaling differences).

**Status:** GL state cache optimization reverted. Some residual artifacts may exist from the x1_box GL port. See `GL_ARTIFACT_INVESTIGATION.md` for detailed analysis.

---

## TB Cache Prewarm Crash on Settings Change

**Symptom:** Assertion failure in `translator.c:310` during `tb_cache_prewarm` at startup.

**Root cause:** Stale `tb_cache.bin` from a previous session with different FP settings or a different build. The cached translation block hints are incompatible with the current code generation configuration.

**Current handling:** FP settings changes delete `tb_cache.bin`. FP state is XORed into the game hash for cache rejection. A "Clear code cache" button is available in Debug settings.

**Remaining risk:** Build-to-build changes that affect code generation (e.g. GLSL changes) don't auto-invalidate the cache. Users must manually clear the code cache after updates.
