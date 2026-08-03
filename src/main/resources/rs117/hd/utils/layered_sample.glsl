#pragma once

#define IDENTITY(x) (x)

#define LAYERED_SAMPLE(OUT1, OUT2, OUT3, MAPIDX1, MAPIDX2, MAPIDX3, UV1, UV2, UV3, HEX1, HEX2, HEX3, DEFAULT) \
    OUT1 = (MAPIDX1) == -1 ? (DEFAULT) : sampleHex(textureArray, vec3(UV1, MAPIDX1), HEX1); \
    if (materialIdx1 == materialIdx2) { \
        OUT2 = OUT1; \
    } else { \
        OUT2 = (MAPIDX2) == -1 ? (DEFAULT) : sampleHex(textureArray, vec3(UV2, MAPIDX2), HEX2); \
    } \
    if (materialIdx1 == materialIdx3) { \
        OUT3 = OUT1; \
    } else if (materialIdx2 == materialIdx3) { \
        OUT3 = OUT2; \
    } else { \
        OUT3 = (MAPIDX3) == -1 ? (DEFAULT) : sampleHex(textureArray, vec3(UV3, MAPIDX3), HEX3); \
    }

#define LAYERED_SAMPLE_SCALAR(OUT1, OUT2, OUT3, MAPIDX1, MAPIDX2, MAPIDX3, UV1, UV2, UV3, HEX1, HEX2, HEX3, DEFAULT, POST) \
    OUT1 = (MAPIDX1) == -1 ? (DEFAULT) : POST(sampleHex(textureArray, vec3(UV1, MAPIDX1), HEX1).r); \
    if (materialIdx1 == materialIdx2) { \
        OUT2 = OUT1; \
    } else { \
        OUT2 = (MAPIDX2) == -1 ? (DEFAULT) : POST(sampleHex(textureArray, vec3(UV2, MAPIDX2), HEX2).r); \
    } \
    if (materialIdx1 == materialIdx3) { \
        OUT3 = OUT1; \
    } else if (materialIdx2 == materialIdx3) { \
        OUT3 = OUT2; \
    } else { \
        OUT3 = (MAPIDX3) == -1 ? (DEFAULT) : POST(sampleHex(textureArray, vec3(UV3, MAPIDX3), HEX3).r); \
    }

#define LAYERED_SAMPLE_NORMAL(OUT1, OUT2, OUT3, UV1, UV2, UV3) \
    OUT1 = sampleNormalMap(material1, UV1, TBN); \
    if (materialIdx1 == materialIdx2) { \
        OUT2 = OUT1; \
    } else { \
        OUT2 = sampleNormalMap(material2, UV2, TBN); \
    } \
    if (materialIdx1 == materialIdx3) { \
        OUT3 = OUT1; \
    } else if (materialIdx2 == materialIdx3) { \
        OUT3 = OUT2; \
    } else { \
        OUT3 = sampleNormalMap(material3, UV3, TBN); \
    }