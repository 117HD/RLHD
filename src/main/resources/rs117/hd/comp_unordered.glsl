/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
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

#include VERSION_HEADER

#include <comp_common.glsl>

layout(local_size_x = 6) in;

void main() {
    uint groupId = gl_WorkGroupID.x;
    uint localId = gl_LocalInvocationID.x;
    const ModelInfo minfo = ol[groupId];

    int offset = minfo.offset;
    int size = minfo.size;
    int outOffset = minfo.idx;
    int uvOffset = minfo.uvOffset;
    int flags = minfo.flags;
    vec3 pos = vec3(minfo.x, minfo.y >> 16, minfo.z);

    if (localId >= size) {
        return;
    }

    uint ssboOffset = localId;
    VertexData thisA, thisB, thisC;

    // Grab triangle vertices from the correct buffer
    thisA = vb[offset + ssboOffset * 3    ];
    thisB = vb[offset + ssboOffset * 3 + 1];
    thisC = vb[offset + ssboOffset * 3 + 2];

    thisA.pos += pos;
    thisB.pos += pos;
    thisC.pos += pos;

    uint myOffset = localId;

    // position vertices in scene and write to out buffer
    vout[outOffset + myOffset * 3]     = thisA;
    vout[outOffset + myOffset * 3 + 1] = thisB;
    vout[outOffset + myOffset * 3 + 2] = thisC;

    if (uvOffset < 0) {
        uvout[outOffset + myOffset * 3]     = UVData(vec3(0.0), 0);
        uvout[outOffset + myOffset * 3 + 1] = UVData(vec3(0.0), 0);
        uvout[outOffset + myOffset * 3 + 2] = UVData(vec3(0.0), 0);
    } else {
        uvout[outOffset + myOffset * 3]     = uv[uvOffset + localId * 3];
        uvout[outOffset + myOffset * 3 + 1] = uv[uvOffset + localId * 3 + 1];
        uvout[outOffset + myOffset * 3 + 2] = uv[uvOffset + localId * 3 + 2];
    }

    vec4 normA, normB, normC;

    // Grab triangle normals from the correct buffer
    normA = normal[offset + ssboOffset * 3    ];
    normB = normal[offset + ssboOffset * 3 + 1];
    normC = normal[offset + ssboOffset * 3 + 2];

    normalout[outOffset + myOffset * 3]     = normA;
    normalout[outOffset + myOffset * 3 + 1] = normB;
    normalout[outOffset + myOffset * 3 + 2] = normC;
}
