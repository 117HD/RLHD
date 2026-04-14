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

// Maximum model wind reach value used for quantization (must match Java packWindData)
#define MAX_WIND_REACH 2560.0
// Model origin grid spacing in world units (must match Java packWindData)
#define MODEL_ORIGIN_SNAP 256.0

float getWindDisplacementMod(int materialData) {
    const float modifiers[7] = float[7](0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0);
    int modifierIdx = (materialData >> MATERIAL_FLAG_WIND_MODIFIER) & 0x7;
    return modifiers[modifierIdx];
}

// Unpack wind data from the normal W component (16 bits packed as a short).
// Bits 0-5  (6 bits): vertexHeightRatio (0-63, normalized to 0-1)
// Bits 6-9  (4 bits): modelWindReach (0-15, scaled by MAX_WIND_REACH)
// Bits 10-12 (3 bits): model origin X / MODEL_ORIGIN_SNAP, mod 8
// Bits 13-15 (3 bits): model origin Z / MODEL_ORIGIN_SNAP, mod 8
void unpackWindData(float packedShort, out float vertexHeightRatio, out float modelWindReach, out vec2 modelOrigin) {
    // vNormal.w is read as GL_SHORT (signed -32768..32767).
    // Convert to unsigned 0..65535, then extract fields using arithmetic
    // to avoid potential driver issues with bitwise operations on signed ints.
    float uval = packedShort < 0.0 ? packedShort + 65536.0 : packedShort;
    float originZ = floor(uval / 8192.0);
    uval -= originZ * 8192.0;
    float originX = floor(uval / 1024.0);
    uval -= originX * 1024.0;
    float reach = floor(uval / 64.0);
    float ratio = uval - reach * 64.0;
    vertexHeightRatio = ratio / 63.0;
    modelWindReach = reach / 15.0 * MAX_WIND_REACH;
    modelOrigin = vec2(originX, originZ) * MODEL_ORIGIN_SNAP;
}

// Compute per-vertex wind displacement for the zone renderer.
// Closely mirrors the legacy compute-shader wind in comp.glsl + priority_render.glsl.
// packedWindData: raw vNormal.w value containing packed height ratio + model wind reach
vec3 computeZoneWindDisplacement(vec3 worldPos, int materialData, vec3 normal, float packedWindData) {
    int windMode = (materialData >> MATERIAL_FLAG_WIND_SWAYING) & 0x7;
    if (windMode <= WIND_DISPLACEMENT_DISABLED)
        return vec3(0);

    float vertexHeightRatio;
    float modelWindReach;
    vec2 modelOrigin;
    unpackWindData(packedWindData, vertexHeightRatio, modelWindReach, modelOrigin);

    // Use packed model origin for object-level noise (matches legacy's exact model origin)
    vec2 snappedPos = modelOrigin;

    // Object-level noise (mirrors comp.glsl)
    float modelNoise = noise((snappedPos + vec2(windOffset)) * WIND_DISPLACEMENT_NOISE_RESOLUTION);
    float angle = modelNoise * (PI / 2.0);
    float c = cos(angle);
    float s = sin(angle);

    vec3 windDir = normalize(vec3(
        windDirectionX * c + windDirectionZ * s,
        0.0,
        -windDirectionX * s + windDirectionZ * c
    ));

    // Object-level strength: matches legacy formula exactly
    // Legacy: saturate((abs(modelY) + modelHeight) / windCeiling) * windStrength
    float heightBasedStrength = saturate(modelWindReach / windCeiling) * windStrength;
    vec3 objectDisplacement = windDir * (heightBasedStrength * modelNoise);

    // Per-vertex strength: matches legacy saturate(abs(localVertexY) / modelHeight)
    float vertexStrength = vertexHeightRatio;

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
            // No vertex displacement — legacy only uses object displacement for this mode
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
