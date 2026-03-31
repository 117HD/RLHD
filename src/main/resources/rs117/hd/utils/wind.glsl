#pragma once

#include <utils/constants.glsl>
#include <utils/misc.glsl>

// Wind displacement modes (must match comp_common.glsl / WindDisplacement.java)
#define WIND_DISPLACEMENT_DISABLED 0
#define WIND_DISPLACEMENT_OBJECT 1
#define WIND_DISPLACEMENT_OBJECT_NO_GROUND_DISPLACEMENT 2
#define WIND_DISPLACEMENT_VERTEX 3
#define WIND_DISPLACEMENT_VERTEX_WITH_HEMISPHERE_BLEND 4
#define WIND_DISPLACEMENT_VERTEX_JIGGLE 5

float getWindDisplacementMod(int materialData) {
    const float modifiers[7] = float[7](0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0);
    int modifierIdx = (materialData >> MATERIAL_FLAG_WIND_MODIFIER) & 0x7;
    return modifiers[modifierIdx];
}

// Compute per-vertex wind displacement for the zone renderer.
// This is a vertex-shader adaptation of the compute-shader wind in priority_render.glsl.
// Since we lack per-model info (model position, model height), we approximate:
// - Model-level noise: snap vertex world XZ to a coarse grid for coherent per-model motion
// - Height-based strength: derived from vertex world Y position relative to windCeiling
vec3 computeZoneWindDisplacement(vec3 worldPos, int materialData, vec3 normal) {
    int windMode = (materialData >> MATERIAL_FLAG_WIND_SWAYING) & 0x7;
    if (windMode <= WIND_DISPLACEMENT_DISABLED)
        return vec3(0);

    // Approximate model-level values using snapped vertex position
    const float ModelSnapping = 512.0;
    vec2 snappedPos = snap(worldPos.xz, ModelSnapping);

    // Object-level noise (mirrors comp.glsl lines 67-68)
    float modelNoise = noise((snappedPos + vec2(windOffset)) * WIND_DISPLACEMENT_NOISE_RESOLUTION);
    float angle = modelNoise * (PI / 2.0);
    float c = cos(angle);
    float s = sin(angle);

    vec3 windDir = normalize(vec3(
        windDirectionX * c + windDirectionZ * s,
        0.0,
        -windDirectionX * s + windDirectionZ * c
    ));

    // Height-based strength approximation
    float heightBasedStrength = saturate(abs(worldPos.y) / windCeiling) * windStrength;
    vec3 objectDisplacement = windDir * (heightBasedStrength * modelNoise);

    // Per-vertex strength
    float vertexStrength = saturate(abs(worldPos.y) / windCeiling);
    if ((materialData >> MATERIAL_FLAG_INVERT_DISPLACEMENT_STRENGTH & 1) == 1)
        vertexStrength = 1.0 - vertexStrength;

    float modifierMod = getWindDisplacementMod(materialData);
    vec3 displacement = vec3(0);

#if WIND_DISPLACEMENT
    if (windMode >= WIND_DISPLACEMENT_VERTEX) {
        const float VertexSnapping = 150.0;
        const float VertexDisplacementMod = 0.2;
        float windNoise = mix(-0.5, 0.5, noise(
            (snap(worldPos, VertexSnapping).xz + vec2(windOffset)) * WIND_DISPLACEMENT_NOISE_RESOLUTION
        ));

        if (windMode == WIND_DISPLACEMENT_VERTEX_WITH_HEMISPHERE_BLEND) {
            // Hemisphere blend: approximate local-space distances using snapped model center
            const float minDist = 50.0;
            const float blendDist = 10.0;
            vec3 localPos = worldPos - vec3(snappedPos.x, 0, snappedPos.y);
            float distBlend = saturate(((abs(localPos.x) + abs(localPos.z)) - minDist) / blendDist);
            float heightFade = saturate((vertexStrength - 0.5) / 0.2);
            vertexStrength *= mix(0.0, mix(distBlend, 1.0, heightFade), step(0.3, vertexStrength));

            displacement = windNoise * heightBasedStrength * vertexStrength * VertexDisplacementMod * windDir;
            vertexStrength = saturate(vertexStrength - VertexDisplacementMod) * modifierMod;
        } else if (windMode == WIND_DISPLACEMENT_VERTEX_JIGGLE) {
            vec3 skewA = safe_normalize(cross(normal, vec3(0, 1, 0)));
            vec3 skewB = safe_normalize(cross(normal, vec3(1, 0, 0)));
            vertexStrength *= modifierMod;
            displacement = (windNoise * heightBasedStrength * vertexStrength * 0.5) * skewA;
            displacement += ((1.0 - windNoise) * heightBasedStrength * vertexStrength * 0.5) * skewB;
            return displacement;
        } else {
            // VERTEX mode
            displacement = windNoise * heightBasedStrength * vertexStrength * VertexDisplacementMod * windDir;
            vertexStrength = saturate(vertexStrength - VertexDisplacementMod) * modifierMod;
        }
    }

    // Object displacement (all modes except JIGGLE)
    if (windMode != WIND_DISPLACEMENT_VERTEX_JIGGLE) {
        displacement += objectDisplacement * vertexStrength * modifierMod;
    }
#endif

    return displacement;
}
