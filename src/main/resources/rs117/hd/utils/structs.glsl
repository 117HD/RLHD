struct Light {
    vec3 color;
    float brightness;
    vec3 direction;
    vec3 reflection;
    float ndl; // normal.light
};

// Dont know what else to call this. Just holds all the scene vars used for lighting and helper functions for quick access
struct Scene {
    Light sun;
    vec3 viewDir;
    vec3 downDir;
    float ddn; // down.normal
    float vdn; // view.normal
    Material[3] materials;
    vec2[4] uvs; // 0, 1, 2 = standard uv, 3 = blended uv // modified by Helpers/adjustSceneUvs
    vec3 texBlend;
    float mipBias;
};