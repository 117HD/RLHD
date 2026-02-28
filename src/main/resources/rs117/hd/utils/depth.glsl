#pragma once

#define ENABLE_LOG_Z 1

float ApplyLogDepthZeroToOne(float depth, float nearPlane, float farPlane)
{
#if ENABLE_LOG_Z
    float logNear = log(nearPlane + 1.0);
    float logFar  = log(farPlane  + 1.0);

    float logZ = log(depth * (farPlane - nearPlane) + nearPlane + 1.0);
    return (logZ - logNear) / (logFar - logNear);
#else
    return depth;
#endif
}

float ApplyLogDepthMinusOneToOne(float depth, float nearPlane, float farPlane)
{
#if ENABLE_LOG_Z
    // Convert from clip space [-1,1] to [0,1]
    float z = max(depth * 0.5 + 0.5, 1e-6);

    z = ApplyLogDepthZeroToOne(z, nearPlane, farPlane);

    // Convert back to clip space [-1,1]
    return z * 2.0 - 1.0;
#else
    return depth;
#endif
}