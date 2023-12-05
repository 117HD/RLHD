#pragma once

struct Light {
    int type;
    vec3 position;
    float radius; // squared
    vec3 color;
    float brightness;
    vec3 direction;
    vec3 reflection;
    float ndl; // normal.light
    float distance; // squared
};

// Dont know what else to call this. Just holds all vars used for lighting and helper functions for quick access
struct Context {
    Light sun;
    vec3 viewDir;
    float udn; // up.normal
    float vdn; // view.normal
    int materialData;
    Material[3] materials;
    vec2[4] uvs; // 0, 1, 2 = standard uv, 3 = blended uv // modified by Helpers/populateUvs
    vec3 texBlend;
    float mipBias;
    vec3 fragPos;
    mat3 TBN;
    vec4 albedo;
    vec3 normals;
    vec3 smoothness;
    vec3 reflectivity;
    bool isWater;
    bool isUnderwater;
    WaterType waterType;
    int waterTypeIndex;
    float waterDepth;
};
