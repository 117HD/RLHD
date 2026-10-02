#pragma once

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

#ifdef GL_KHR_shader_subgroup_vote
    #extension GL_KHR_shader_subgroup_basic : enable
    #extension GL_KHR_shader_subgroup_vote : enable
    #extension GL_KHR_shader_subgroup_ballot : enable
#endif

// Number of scalar components per fetched texel.
// Valid range: 1-4.
#include TEXEL_SIZE
#ifndef TEXEL_SIZE
    #define TEXEL_SIZE 4
#endif

#if TEXEL_SIZE < 1 || TEXEL_SIZE > 4
    #error TEXEL_SIZE must be between 1 and 4
#endif

#if SHADER_STORAGE_BUFFERS
    struct TexBufferReader {
        int  position;
        bool scalar;
    };

    TexBufferReader buildTexBufferReader(int position, bool scalar) {
        TexBufferReader reader;
        reader.position = position;
    #ifdef GL_KHR_shader_subgroup_vote
        reader.scalar = scalar && subgroupAllEqual(position);
    #else
        reader.scalar = false;
    #endif
        return reader;
    }

    #define SETUP_BUFFER(name, bindingPoint) \
        layout(std430, binding = bindingPoint) buffer name##Buffer { int data[]; } name;

    #ifdef GL_KHR_shader_subgroup_vote
        #define SSBO_LOAD(buf, reader, idx) \
            ((reader).scalar \
                ? subgroupBroadcastFirst(subgroupElect() ? (buf).data[idx] : 0) \
                : (buf).data[idx])
    #else
        #define SSBO_LOAD(buf, reader, idx) ((buf).data[idx])
    #endif

    #define readInt(buf, reader)  (++(reader).position, SSBO_LOAD(buf, reader, (reader).position - 1))
#else
    #define SETUP_BUFFER(name, bindingPoint) uniform isamplerBuffer name;

    struct TexBufferReader {
        // Cached texel data.
        // Always ivec4 regardless of TEXEL_SIZE.
        ivec4 data;

        // Current scalar position in stream.
        int position;

        // Currently cached texel index.
        int loadedTexel;

        // if true texel fetches are scalarized via subgroup ops
        bool scalar;

        // if true then this lene has been elected to peform reads
        bool elected;
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
#endif

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

#define READ_RAW_INT()   readInt(PARSER_TARGET_BUFFER, reader)
#define READ_RAW_UINT()  uint(READ_RAW_INT())
#define READ_RAW_FLOAT() intBitsToFloat(READ_RAW_INT())
#define READ_RAW_BOOL()  (READ_RAW_INT() != 0)

#define READ_INT(field)   data.field = READ_RAW_INT();
#define READ_UINT(field)  data.field = READ_RAW_UINT();
#define READ_FLOAT(field) data.field = READ_RAW_FLOAT();
#define READ_BOOL(field)  data.field = READ_RAW_BOOL();

#define READ_IVEC2(field) data.field = ivec2(READ_RAW_INT(), READ_RAW_INT());
#define READ_IVEC3(field) data.field = ivec3(READ_RAW_INT(), READ_RAW_INT(), READ_RAW_INT());
#define READ_IVEC4(field) data.field = ivec4(READ_RAW_INT(), READ_RAW_INT(), READ_RAW_INT(), READ_RAW_INT());

#define READ_UVEC2(field) data.field = uvec2(READ_RAW_UINT(), READ_RAW_UINT());
#define READ_UVEC3(field) data.field = uvec3(READ_RAW_UINT(), READ_RAW_UINT(), READ_RAW_UINT());
#define READ_UVEC4(field) data.field = uvec4(READ_RAW_UINT(), READ_RAW_UINT(), READ_RAW_UINT(), READ_RAW_UINT());

#define READ_VEC2(field)  data.field = vec2(READ_RAW_FLOAT(), READ_RAW_FLOAT());
#define READ_VEC3(field)  data.field = vec3(READ_RAW_FLOAT(), READ_RAW_FLOAT(), READ_RAW_FLOAT());
#define READ_VEC4(field)  data.field = vec4(READ_RAW_FLOAT(), READ_RAW_FLOAT(), READ_RAW_FLOAT(), READ_RAW_FLOAT());
