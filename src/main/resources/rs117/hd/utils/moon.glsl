#pragma once

#include <utils/misc.glsl>

// Standard normal CDF, using a polynomial approximation to its tail.
float moonGaussianCdf(float x) {
    float t = 1.0 / (1.0 + 0.2316419 * abs(x));
    float tail = 0.39894228 * exp(-0.5 * x * x) * t *
        (0.31938153 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
    return x < 0.0 ? tail : 1.0 - tail;
}

// Gaussian coverage of the full disk and its illuminated portion, respectively.
// Integrate each horizontal interval analytically, then use eight-point Gaussian
// quadrature vertically. This blurs the phase silhouette, not the regolith shading.
vec2 blurredMoonDisk(
    out vec3 local,
    vec3 ray,
    vec3 center,
    vec3 illuminant,
    float phaseCos,
    float radius,
    vec2 sigma,
    vec3 blurAxis
) {
    vec3 up = blurAxis - center * dot(blurAxis, center);
    if (dot(up, up) < 1e-8)
        up = abs(center.y) < 0.999 ? vec3(0, 1, 0) : vec3(0, 0, 1);
    up = normalize(up - center * dot(up, center));
    vec3 right = normalize(cross(up, center));
    float cosine = clamp(dot(ray, center), -1.0, 1.0);
    vec3 tangent = ray - center * cosine;
    vec3 angular = tangent * (acos(cosine) / max(length(tangent), 1e-7));
    // Low-frequency surface detail follows the broadened disk footprint.
    vec2 axes = sqrt(vec2(radius * radius) + 4.0 * sigma * sigma);
    local = right * dot(angular, right) / max(axes.x, 1e-7) +
        up * dot(angular, up) / max(axes.y, 1e-7);

    vec3 phaseX = illuminant - center * dot(illuminant, center);
    phaseX = dot(phaseX, phaseX) > 1e-8 ? normalize(phaseX) : right;
    vec3 phaseY = cross(center, phaseX);
    vec2 position = vec2(dot(angular, phaseX), dot(angular, phaseY)) / radius;
    sigma = max(sigma / radius, vec2(1e-4));
    // Rotate the anisotropic blur into phase coordinates. Conditioning on Y
    // leaves a one-dimensional Gaussian in X, including for tilted crescents.
    vec2 major = vec2(dot(up, phaseX), dot(up, phaseY));
    float varianceDifference = sigma.y * sigma.y - sigma.x * sigma.x;
    float varianceY = sigma.x * sigma.x + varianceDifference * major.y * major.y;
    float sigmaY = sqrt(varianceY);
    float slope = varianceDifference * major.x * major.y / varianceY;
    float conditionalSigma = sigma.x * sigma.y / sigmaY;
    float lower = max(-1.0, position.y - 4.0 * sigmaY);
    float upper = min(1.0, position.y + 4.0 * sigmaY);
    if (cosine <= 0.0 || lower >= upper || abs(position.x) > 1.0 + 4.0 * sigma.y)
        return vec2(0.0);

    // At grazing angles the conditional mean traces a thin, tilted strip through
    // the disk. Sampling all of [-1, 1] can miss it entirely. Bound that strip's
    // intersection using a disk expanded by four conditional standard deviations.
    // Any point within the original disk and strip lies inside this bound.
    float inverseLineLength = inversesqrt(1.0 + slope * slope);
    float lineDistance = (position.x - slope * position.y) * inverseLineLength;
    float expandedRadius = 1.0 + 4.0 * conditionalSigma;
    float halfChordSquared = expandedRadius * expandedRadius - lineDistance * lineDistance;
    if (halfChordSquared <= 0.0)
        return vec2(0.0);
    float centerY = -slope * inverseLineLength * lineDistance;
    float halfSpanY = sqrt(halfChordSquared) * inverseLineLength;
    lower = max(lower, centerY - halfSpanY);
    upper = min(upper, centerY + halfSpanY);
    if (lower >= upper)
        return vec2(0.0);

    const float nodes[4] = float[4](0.18343464, 0.52553241, 0.79666648, 0.96028986);
    const float weights[4] = float[4](0.36268378, 0.31370665, 0.22238103, 0.10122854);
    float midpoint = (lower + upper) * 0.5;
    float halfWidth = (upper - lower) * 0.5;
    vec2 coverage = vec2(0.0);
    for (int i = 0; i < 8; i++) {
        int node = i / 2;
        float y = midpoint + (i % 2 == 0 ? -1.0 : 1.0) * halfWidth * nodes[node];
        float h = sqrt(max(0.0, 1.0 - y * y));
        float meanX = position.x + slope * (y - position.y);
        float end = moonGaussianCdf((h - meanX) / conditionalSigma);
        // The terminator is x = -phaseCos * sqrt(1-y*y).
        vec2 start = vec2(-h, -phaseCos * h);
        vec2 interval = end - vec2(moonGaussianCdf((start.x - meanX) / conditionalSigma),
            moonGaussianCdf((start.y - meanX) / conditionalSigma));
        float dy = (y - position.y) / sigmaY;
        coverage += max(interval, vec2(0.0)) * weights[node] * exp(-0.5 * dy * dy);
    }
    return coverage * (halfWidth * 0.39894228 / sigmaY);
}

// Regolith response shared by direct and reflected sky samples.
// Surface detail may perturb lightCos before this calculation.
float moonDiffuse(float lightCos, float viewCos, float phaseCos) {
    float incidence = max(lightCos, 0.0);
    float lommelSeeliger = 2.0 * incidence / max(incidence + viewCos, 1e-4);
    float diffuse = mix(lommelSeeliger, incidence, mix(0.6, 0.15, max(phaseCos, 0.0)));
    float lit = clamp(diffuse, 0.0, 1.0) * smoothstep(-0.14, 0.08, lightCos);
    float terminatorProximity = 1.0 - smoothstep(0.02, 0.2, abs(lightCos));
    return lit * mix(1.0, smoothstep(0.0, 0.25, viewCos), terminatorProximity);
}

float moonFbm(in vec2 st) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 6; i++) {
        value += amplitude * noise(st);
        st *= 2.0;
        amplitude *= 0.5;
    }
    return value;
}
