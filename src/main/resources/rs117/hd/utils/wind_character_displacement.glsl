#pragma once

#include <uniforms/global.glsl>
#include <utils/constants.glsl>
#include <utils/misc.glsl>

// Needs to match Ordinal Values of WindDisplacement.Java
#define WIND_DISPLACEMENT_DISABLED 0
#define WIND_DISPLACEMENT_OBJECT 1
#define WIND_DISPLACEMENT_OBJECT_NO_GROUND_DISPLACEMENT 2
#define WIND_DISPLACEMENT_VERTEX 3
#define WIND_DISPLACEMENT_VERTEX_WITH_HEMISPHERE_BLEND 4
#define WIND_DISPLACEMENT_VERTEX_JIGGLE 5

struct ObjectWindSample {
    vec3 direction;
    vec3 displacement;
    float heightBasedStrength;
};

ObjectWindSample computeWindSample(vec3 modelPos, int height) {
    ObjectWindSample windSample;
    windSample.direction = vec3(0);
    windSample.displacement = vec3(0);
    windSample.heightBasedStrength = 0.0;

#if WIND_DISPLACEMENT
    float modelNoise = noise((modelPos.xz + vec2(windOffset)) * WIND_DISPLACEMENT_NOISE_RESOLUTION);
    float angle = modelNoise * (PI / 2.0);
    float c = cos(angle);
    float s = sin(angle);

    windSample.direction = normalize(vec3(windDirectionX * c + windDirectionZ * s, 0.0, -windDirectionX * s + windDirectionZ * c));
    windSample.heightBasedStrength = saturate((abs(modelPos.y) + float(height)) / windCeiling) * windStrength;
    windSample.displacement = windSample.direction.xyz * (windSample.heightBasedStrength * modelNoise);
#endif

    return windSample;
}

float getModelWindDisplacementMod(int vertexFlags) {
    const float modifiers[7] = float[7](0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0);
    int modifierIDx = (vertexFlags >> MATERIAL_FLAG_WIND_MODIFIER) & 0x7;
    return modifiers[modifierIDx];
}

vec3 applyCharacterDisplacement(vec3 characterPos, vec2 vertPos, float height, float strength, inout float offsetAccum) {
    vec2 offset = vertPos - characterPos.xy;
    float offsetLen = length(offset);

    if (offsetLen >= characterPos.z)
        return vec3(0);

    float offsetFrac = saturate(1.0 - (offsetLen / characterPos.z));
    float displacementFrac = offsetFrac * offsetFrac;

    vec3 horizontalDisplacement = normalize(vec3(offset.x, 0.0, offset.y)) * (height * strength * displacementFrac * 0.5);
    vec3 verticalDisplacement = vec3(0.0, height * strength * displacementFrac, 0.0);

    offsetAccum += offsetFrac;

    return mix(horizontalDisplacement, verticalDisplacement, offsetFrac);
}

vec3 applyWindDisplacementVertex(
    const ObjectWindSample windSample,
    int vertexFlags,
    float modelHeight,
    vec3 worldVertPos,
    vec3 localVertPos,
    vec3 normal
) {
    vec3 displacement = vec3(0);

    int windDisplacementMode = (vertexFlags >> MATERIAL_FLAG_WIND_SWAYING) & 0x7;
    if (windDisplacementMode <= WIND_DISPLACEMENT_DISABLED)
        return displacement;

    float strength = saturate(abs(localVertPos.y) / modelHeight);
    if ((vertexFlags >> MATERIAL_FLAG_INVERT_DISPLACEMENT_STRENGTH & 1) == 1)
        strength = 1.0 - strength;

    float modelDisplacementMod = getModelWindDisplacementMod(vertexFlags);

#if WIND_DISPLACEMENT
    if (windDisplacementMode >= WIND_DISPLACEMENT_VERTEX) {
        const float VertexSnapping = 150.0;
        const float VertexDisplacementMod = 0.2;
        float windNoise = mix(-0.5, 0.5, noise((snap(localVertPos, VertexSnapping).xz + vec2(windOffset)) * WIND_DISPLACEMENT_NOISE_RESOLUTION));

        if (windDisplacementMode == WIND_DISPLACEMENT_VERTEX_WITH_HEMISPHERE_BLEND) {
            const float minDist = 50;
            const float blendDist = 10.0;

            float distBlend = saturate(((abs(localVertPos.x) + abs(localVertPos.z)) - minDist) / blendDist);
            float heightFade = saturate((strength - 0.5) / 0.2);

            strength *= mix(0.0, mix(distBlend, 1.0, heightFade), step(0.3, strength));
        } else {
            if (windDisplacementMode == WIND_DISPLACEMENT_VERTEX_JIGGLE) {
                vec3 vertSkew = safe_normalize(cross(normal, vec3(0, 1, 0)));

                strength *= modelDisplacementMod;
                displacement = ((windNoise * (windSample.heightBasedStrength * strength) * 0.5) * vertSkew);

                vertSkew = safe_normalize(cross(normal, vec3(1, 0, 0)));

                displacement += (((1.0 - windNoise) * (windSample.heightBasedStrength * strength) * 0.5) * vertSkew);
            } else {
                displacement = ((windNoise * (windSample.heightBasedStrength * strength * VertexDisplacementMod)) * windSample.direction);

                strength = saturate(strength - VertexDisplacementMod) * modelDisplacementMod;
            }
        }
    }

    if (windDisplacementMode != WIND_DISPLACEMENT_VERTEX_JIGGLE) {
        displacement += windSample.displacement * strength * modelDisplacementMod;
    }
#endif

#if CHARACTER_DISPLACEMENT
    if (windDisplacementMode == WIND_DISPLACEMENT_OBJECT) {
        vec2 worldVert = worldVertPos.xz;
        float fractAccum = 0.0;
        for (int i = 0; i < characterPositionCount; i++) {
            displacement += applyCharacterDisplacement(characterPositions[i], worldVert, modelHeight, strength, fractAccum);
            if (fractAccum >= 1.0)
                break;
        }
    }
#endif

    return displacement;
}

void applyWindDisplacementFace(const ObjectWindSample windSample, int vertexFlags, float modelHeight, vec3 modelPos,
    in vec3 vertA, in vec3 vertB, in vec3 vertC,
    in vec3 normA, in vec3 normB, in vec3 normC,
    inout vec3 displacementA, inout vec3 displacementB, inout vec3 displacementC
) {
    displacementA += applyWindDisplacementVertex(windSample, vertexFlags, modelHeight, modelPos + vertA, vertA, normA);
    displacementB += applyWindDisplacementVertex(windSample, vertexFlags, modelHeight, modelPos + vertB, vertB, normB);
    displacementC += applyWindDisplacementVertex(windSample, vertexFlags, modelHeight, modelPos + vertC, vertC, normC);
}