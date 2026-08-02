# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

117 HD is a RuneLite plugin that replaces the game's GPU renderer with an OpenGL-based renderer featuring shadows, dynamic lighting, materials, and environment effects. This fork (`Day/Night-Cycle` branch) adds a real-time day/night cycle with sun/moon positioning, sky gradients, starfields, and time-of-day-dependent lighting.

## Build & Run

```bash
./gradlew shadowJar                                    # Build fat JAR
java -jar build/libs/hd-1.5.2-all.jar                 # Run standalone
./gradlew run                                          # Run via Gradle (launches RuneLite with plugin)
./gradlew test                                         # Run unit tests
```

The main test entry point `HdPluginTest` launches a full RuneLite client with the plugin loaded in dev mode.

## Architecture

### Entry Point & Plugin Lifecycle
- `HdPlugin.java` — Main plugin class. Handles OpenGL context creation, manager initialization, shader compilation, and the per-frame render loop via `DrawCallbacks`.
- `HdPluginConfig.java` — Config interface using RuneLite's `@ConfigItem` annotations with `KEY_*` string constants.

### Two Renderers
- **Legacy** (`renderer/legacy/`) — Geometry-shader-based pipeline for older GPUs.
- **Zone** (`renderer/zone/`) — Modern renderer using provoked vertices, no geometry shaders.
- Both implement `Renderer.java` interface. Selected at startup via `config.legacyRenderer()`.

### Shader System
Shaders live in `src/main/resources/rs117/hd/`:
- Main programs: `scene_{vert,frag,geom}`, `shadow_{vert,frag,geom}`, `sky_{vert,frag}`, `ui_{vert,frag}`, `tiled_lighting_{vert,frag}`
- Compute: `comp.glsl`, `comp_common.glsl`, `comp_unordered.glsl`, `comp_sorting_utils.glsl`
- Uniforms: `uniforms/global.glsl`, `uniforms/materials.glsl`, `uniforms/lights.glsl`, etc.
- Utilities: `utils/constants.glsl`, `utils/fog.glsl`, `utils/water.glsl`, `utils/lights.glsl`, etc.

**Include system** (`ShaderIncludes.java`): Supports `#include <path/to/file.glsl>` for files and `#include IDENTIFIER` for dynamic defines injected from Java. Supports `#pragma once`.

### UBO (Uniform Buffer Object) System
Java UBO classes in `opengl/uniforms/` map to GLSL `uniform` blocks with **std140 layout**. Property order in Java must exactly match the GLSL struct declaration order. Key UBOs:
- `UBOGlobal` ↔ `uniforms/global.glsl` — Scene-wide state (colors, matrices, fog, sun/moon)
- `UBOMaterials` ↔ `uniforms/materials.glsl` — Material properties array
- `UBOLights` ↔ `uniforms/lights.glsl` — Dynamic point lights

### Environment System
- `EnvironmentManager.java` — Blends between environments using start/current/target triplet pattern (3-second transitions).
- `Environment.java` — Data class with color, fog, wind, lighting properties.
- `environments.json` — Per-area environment definitions.
- Colors in JSON are sRGB hex strings (`#RRGGBB`), auto-converted to linear via `@JsonAdapter(SrgbToLinearAdapter.class)`.
- Angles in JSON are degrees, auto-converted to radians via `@JsonAdapter(DegreesToRadians.class)`.

### Data-Driven JSON
Scene data in `src/main/resources/rs117/hd/scene/`: `environments.json`, `areas.json`, `materials.json`, `ground_materials.json`, `lights.json`, `water_types.json`, `model_overrides.json`, `tile_overrides.json`. Schemas in `schemas/`. Each has a corresponding manager class under `scene/`.

### Config → Shader Pipeline
1. Config change arrives via `@Subscribe onConfigChanged`
2. Added to `pendingConfigChanges` set, processed by renderer's `processConfigChanges(Set<String> keys)`
3. Keys checked with `keys.contains(KEY_*)` — must match `KEY_*` constants exactly
4. Shader defines built in `HdPlugin.getShaderIncludes()` via `includes.define("NAME", value)`
5. Config toggles that affect shaders must use `config.methodName()` (direct call), not cached variables

## Critical Patterns

- **Adding a shader define**: Add `#include DEFINE_NAME` in `utils/constants.glsl`, then `includes.define("DEFINE_NAME", value)` in `getShaderIncludes()`.
- **Adding a UBO field**: Add property in Java UBO class AND in GLSL uniform block at the same position. Order must match (std140).
- **Zone renderer normals**: `SceneUploader` stores normals as `(X, Z, Y)`. Use `.xzy` swizzle in shaders.
- **Zone renderer flat varyings**: Don't put face-constant data in interpolated `FragmentData`. Use separate `flat out/in` declarations.
- **Water reflection clipping**: In `RENDER_PASS_WATER_REFLECTION`, discard fragments where `IN.position.y > waterHeight` to prevent underwater geometry contaminating reflections.
- **Sun horizon offset**: 5-degree offset (0.087 radians) in `sky_frag.glsl` to match player-perceived horizon.
