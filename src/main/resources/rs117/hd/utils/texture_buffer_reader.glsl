#pragma once

// Number of scalar components per fetched texel.
// Valid range: 1-4.
#include TEXEL_SIZE
#ifndef TEXEL_SIZE
    #define TEXEL_SIZE 4
#endif

#if TEXEL_SIZE < 1 || TEXEL_SIZE > 4
    #error TEXEL_SIZE must be between 1 and 4
#endif

#ifdef GL_KHR_shader_subgroup_vote
    #extension GL_KHR_shader_subgroup_vote : enable
    #extension GL_KHR_shader_subgroup_ballot : enable
#endif

// Sequential reader for tightly-packed scalar data stored in a buffer texture.
//
// Layout assumptions:
// - Data is packed scalar-by-scalar with NO padding.
// - Floats are stored as IEEE-754 bit patterns inside integer components.
// - TEXEL_SIZE defines how many usable components exist per fetched texel.
//
// Example packed stream:
// [int][float][vec3][ivec2]...
//
// This reader caches the currently loaded texel to avoid redundant texelFetch
// calls during sequential access.
//
// If GL_KHR_shader_subgroup_vote is supported & then data reads can be scalarized
// when the starting position is the same across all invoked lanes

struct TexBufferReader {
    // Cached texel data.
    // Always ivec4 regardless of TEXEL_SIZE.
    ivec4 data;

    // Current scalar position in stream.
    int position;

    // Currently cached texel index.
    int loadedTexel;

#ifdef GL_KHR_shader_subgroup_vote
    // if true texel fetches are scalarized via subgroup ops
    bool scalar;

    // if true then this lene has been elected to peform reads
    bool elected;
#endif
};

TexBufferReader buildTexBufferReader(int position, bool scalar) {
    TexBufferReader reader;
    reader.position = position;
    reader.data = ivec4(0);
    reader.loadedTexel = -1;

#ifdef GL_KHR_shader_subgroup_vote
    reader.scalar = scalar && subgroupAllEqual(position);
    reader.elected = subgroupElect();
#else
    reader.scalar = false;
    reader.elected = false;
#endif

    return reader;
}

int readInt(isamplerBuffer buf, inout TexBufferReader reader) {
#if TEXEL_SIZE == 4
    int texelIndex = reader.position >> 2;
    int component  = reader.position & 3;
#else
    int texelIndex = reader.position / TEXEL_SIZE;
    int component  = reader.position % TEXEL_SIZE;
#endif

    if (texelIndex != reader.loadedTexel) {
#ifdef GL_KHR_shader_subgroup_vote
        if (reader.scalar) {
            ivec4 fetched;
            if (reader.elected)
                fetched = texelFetch(buf, texelIndex);
            reader.data = subgroupBroadcastFirst(fetched);
        } else
#endif
        {
            reader.data = texelFetch(buf, texelIndex);
        }
        reader.loadedTexel = texelIndex;
    }

    reader.position++;

    switch (component) {
        case 0: return reader.data.x;
        case 1: return reader.data.y;
        case 2: return reader.data.z;
        default: return reader.data.w;
    }
}

uint readUInt(isamplerBuffer buf, inout TexBufferReader reader) {
    return uint(readInt(buf, reader));
}

float readFloat(isamplerBuffer buf, inout TexBufferReader reader) {
    return intBitsToFloat(readInt(buf, reader));
}

bool readBool(isamplerBuffer buf, inout TexBufferReader reader) {
    return readInt(buf, reader) != 0;
}

ivec2 readIVec2(isamplerBuffer buf, inout TexBufferReader reader) {
    return ivec2(
        readInt(buf, reader),
        readInt(buf, reader)
    );
}

ivec3 readIVec3(isamplerBuffer buf, inout TexBufferReader reader) {
    return ivec3(
        readInt(buf, reader),
        readInt(buf, reader),
        readInt(buf, reader)
    );
}

ivec4 readIVec4(isamplerBuffer buf, inout TexBufferReader reader) {
    return ivec4(
        readInt(buf, reader),
        readInt(buf, reader),
        readInt(buf, reader),
        readInt(buf, reader)
    );
}

uvec2 readUVec2(isamplerBuffer buf, inout TexBufferReader reader) {
    return uvec2(
        readUInt(buf, reader),
        readUInt(buf, reader)
    );
}

uvec3 readUVec3(isamplerBuffer buf, inout TexBufferReader reader) {
    return uvec3(
        readUInt(buf, reader),
        readUInt(buf, reader),
        readUInt(buf, reader)
    );
}

uvec4 readUVec4(isamplerBuffer buf, inout TexBufferReader reader) {
    return uvec4(
        readUInt(buf, reader),
        readUInt(buf, reader),
        readUInt(buf, reader),
        readUInt(buf, reader)
    );
}

vec2 readVec2(isamplerBuffer buf, inout TexBufferReader reader) {
    return vec2(
        readFloat(buf, reader),
        readFloat(buf, reader)
    );
}

vec3 readVec3(isamplerBuffer buf, inout TexBufferReader reader) {
    return vec3(
        readFloat(buf, reader),
        readFloat(buf, reader),
        readFloat(buf, reader)
    );
}

vec4 readVec4(isamplerBuffer buf, inout TexBufferReader reader) {
    return vec4(
        readFloat(buf, reader),
        readFloat(buf, reader),
        readFloat(buf, reader),
        readFloat(buf, reader)
    );
}

void skipScalars(inout TexBufferReader reader, int count) {
    reader.position += count;
}

void rewindReader(inout TexBufferReader reader, int position) {
    reader.position = position;
}

#define BEGIN_BUFFER_PARSER(FuncName, StructType, Scalar) \
StructType FuncName(int offset) {                         \
    TexBufferReader reader =                              \
        buildTexBufferReader(offset, Scalar);             \
                                                          \
    StructType data;

#define END_BUFFER_PARSER() \
    return data;            \
}

#define READ_INT(field) data.field = readInt(PARSER_TARGET_BUFFER, reader);
#define READ_FLOAT(field) data.field = readFloat(PARSER_TARGET_BUFFER, reader);
#define READ_BOOL(field) data.field = readBool(PARSER_TARGET_BUFFER, reader);
#define READ_IVEC2(field) data.field = readIVec2(PARSER_TARGET_BUFFER, reader);
#define READ_IVEC3(field) data.field = readIVec3(PARSER_TARGET_BUFFER, reader);
#define READ_IVEC4(field) data.field = readIVec4(PARSER_TARGET_BUFFER, reader);
#define READ_VEC2(field) data.field = readVec2(PARSER_TARGET_BUFFER, reader);
#define READ_VEC3(field) data.field = readVec3(PARSER_TARGET_BUFFER, reader);
#define READ_VEC4(field) data.field = readVec4(PARSER_TARGET_BUFFER, reader);