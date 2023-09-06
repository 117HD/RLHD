/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * Copyright (c) 2023, Hooder <ahooder@protonmail.com>
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
#version 330

#include utils/constants.glsl

#if SHADOW_MODE == SHADOW_MODE_DETAILED
    uniform sampler2DArray textureArray;
    in vec3 fUvw;
#endif

#if SHADOW_TRANSPARENCY
    in float fOpacity;
#endif

void main() {
    float opacity = 0;
    #if SHADOW_TRANSPARENCY
        opacity = fOpacity;
    #endif

    #if SHADOW_MODE == SHADOW_MODE_DETAILED
        if (fUvw.z != -1) {
            opacity = texture(textureArray, fUvw).a;

            #if !SHADOW_TRANSPARENCY
                if (opacity < SHADOW_DEFAULT_OPACITY_THRESHOLD)
                    discard; // TODO: compare performance between discard and writing to gl_FragDepth
            #endif
        }
    #endif

    #if SHADOW_TRANSPARENCY
        // We pack the transparency and depth of each fragment into the upper and lower bits
        // of the output depth respectively, such that less-transparent fragments overwrite
        // more-transparent fragments first, and equally transparent fragments second, based on depth.
        // Unfortunately, the exact handling of floats is implementation dependant, so this may not work
        // the same across all GPUs.
        float depth = gl_FragCoord.z;
        gl_FragDepth = (
            int((1 - opacity) * SHADOW_ALPHA_MAX) << SHADOW_DEPTH_BITS |
            int(depth * SHADOW_DEPTH_MAX)
        ) / float(SHADOW_COMBINED_MAX);
    #endif
}
