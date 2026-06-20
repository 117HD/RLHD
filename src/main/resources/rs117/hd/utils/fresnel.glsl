#pragma once

#include <utils/constants.glsl>

// Schlick's approximation
float calculateFresnel(const float cosi, const float iorFrom, const float iorTo) {
    float R0 = (iorFrom - iorTo) / (iorFrom + iorTo);
    R0 *= R0;
    return R0 + (1 - R0) * pow(1 - cosi, 5);
}

float calculateFresnel(const float cosi, const float iorTo) {
    return calculateFresnel(cosi, IOR_AIR, iorTo);
}
