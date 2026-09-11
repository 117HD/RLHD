#version 330

// Point-sprite stars: per-star work scales with star count, not screen pixels.

#include <uniforms/global.glsl>
#include <uniforms/sky.glsl>

#include <utils/starfield.glsl>

layout(location = 0) in vec3 aStarDir;     // field-space unit direction
layout(location = 1) in float aStarSize;   // relative size
layout(location = 2) in float aStarBright; // base brightness
layout(location = 3) in vec3 aStarColor;   // tint
layout(location = 4) in float aStarRotationSpeed;

out vec3 vColor;
out float vBrightness;

const float SKY_HORIZON_OFFSET = 0.087;

void main() {
    vec3 dir = inverseRotateStarfield(aStarDir, elapsedTime, aStarRotationSpeed);

    // Softly occlude additively blended stars behind the opaque moon disk.
    float moonOcclusion = 1.0;
    if (moonVisibility > 0.0) {
        vec3 moonDir = normalize(vec3(skyMoonDir.x, -skyMoonDir.y + SKY_HORIZON_OFFSET, skyMoonDir.z));
        float moonDot = dot(dir, moonDir);
        // Match the moon disk's per-environment angular scale.
        float innerAngle = acos(0.99951) * moonSizeMult;
        float outerAngle = acos(0.9991) * moonSizeMult;
        moonOcclusion = smoothstep(cos(innerAngle), cos(outerAngle), moonDot);
    }

    // Project a far point from the camera; depth testing is disabled for this pass.
    vec4 clip = projectionMatrix * vec4(cameraPos + dir * 1.0e6, 1.0);
    if (clip.w <= 0.0) {
        // Push stars behind the camera off-screen.
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        gl_PointSize = 0.0;
        return;
    }
    gl_Position = clip;

    // Match sky_frag's night-sky visibility.
    float upAmount = -dir.y;

    vec3 sunDir = normalize(vec3(skySunDir.x, -skySunDir.y + SKY_HORIZON_OFFSET, skySunDir.z));
    vec2 viewHoriz = vec2(dir.x, dir.z);
    float viewHorizLen = length(viewHoriz);
    vec3 viewHorizontal = viewHorizLen > 1e-4 ? vec3(viewHoriz.x, 0.0, viewHoriz.y) / viewHorizLen : vec3(0.0);
    vec3 sunHorizontal = normalize(vec3(sunDir.x, 0.0, sunDir.z));
    float sunFacing = dot(viewHorizontal, sunHorizontal) * smoothstep(0.0, 0.35, viewHorizLen);
    float sunSideBlend = smoothstep(0.0, 1.0, (sunFacing + 1.0) * 0.5);

    float zenithBlend = smoothstep(-0.1, 0.7, upAmount);
    float nightFade = smoothstep(-0.26, 0.0, skySunDir.y);

    float baseProgress = 1.0 - nightFade;
    float sunProximity = sunSideBlend * (1.0 - zenithBlend);
    float nightSkyBlend = pow(baseProgress, mix(0.4, 0.9, sunProximity)) * starVisibility;

    // Fade stars just above the nebula horizon band.
    float horizonShift = nightHorizonOffset(starHorizonHeight);
    float horizonStarFade = smoothstep(horizonShift, 0.12 + horizonShift, upAmount);

    float visibility = nightSkyBlend * horizonStarFade * moonOcclusion;

    vColor = aStarColor;

    // Stable per-star hashes give each deliberate twinkle its own phase, rate, and depth.
    float starHash = fract(sin(dot(aStarDir, vec3(12.9898, 78.233, 37.719))) * 43758.5453);
    float starHash2 = fract(sin(dot(aStarDir, vec3(93.989, 41.123, 19.37))) * 24634.6345);
    float twinklePhase = starHash * TAU;
    float twinkleRate = mix(4.0, 13.2, starHash2);
    float twinkleAmt = mix(0.35, 0.5, starHash);
    // Two incommensurate oscillators keep the shimmer from visibly repeating.
    float s1 = sin(elapsedTime * twinkleRate + twinklePhase);
    float s2 = sin(elapsedTime * twinkleRate * 0.37 + twinklePhase * 2.13);
    float osc = (s1 + s2) * 0.5; // [-1, 1]
    float twinkle = 1.0 + twinkleAmt * osc; // swing around baseline

    vBrightness = min(aStarBright, .4) * visibility * twinkle;

    // Size in screen pixels, then enforce the same anti-flicker floor in FBO pixels.
    float viewportHeight = max(float(viewportSize.y), 1.0);
    float screenSize = clamp(aStarSize * viewportHeight * 0.003 * (0.9 + 0.15 * vBrightness), 2.0, 3.5);
    float renderScale = float(sceneResolution.y) / viewportHeight;
    float sizePixels = max(screenSize * renderScale, 2.0);
    float actualScreenSize = sizePixels / max(renderScale, 1e-6);
    vBrightness *= min(1.0, (screenSize / actualScreenSize) * (screenSize / actualScreenSize));
    gl_PointSize = visibility > 0.001 ? sizePixels : 0.0;
}
