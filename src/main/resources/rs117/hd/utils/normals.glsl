/*
 * Copyright (c) 2022, Hooder <ahooder@protonmail.com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

#include NORMAL_MAPPING

#include <uniforms/materials.glsl>

#if NORMAL_MAPPING
vec3 sampleNormalMap(const Material material, const vec2 uv, const mat3 TBN) {
    if (material.normalMap == -1)
        return TBN[2];

    // Sample normal map texture, swapping Y and Z to match the coordinate system in OSRS
    vec3 n = texture(textureArray, vec3(uv, material.normalMap)).xyz;
    // Undo automatic sRGB to linear conversion, since we want the raw values
    n = linearToSrgb(n);
    // Scale and shift normal so it can point in both directions
    n.xy = n.xy * 2 - 1;
    // Flip normals when UVs are flipped
    n.xy *= sign(material.textureScale.xy);
    // Scale the normal map's Z-component to adjust strength
    n.z *= material.textureScale.z;
    // Transform the normal from tangent space to world space
    n = TBN * n;
    // Assume the normal is already normalized
    return n;
}
#else
#define sampleNormalMap(material, uv, TBN) TBN[2]
#endif
