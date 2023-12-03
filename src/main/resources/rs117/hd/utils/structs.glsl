struct Light {
    vec3 color;
    float brightness;
    vec3 direction;
    vec3 reflection;
    float ndl; // normal.light
};

// Dont know what else to call this. Just holds all the scene vars used for lighting for quick access
struct Scene {
    Light sun;
    Light[LIGHT_COUNT] lights;
    vec3 viewDir;
    vec3 downDir;
    float ddn; // down.normal
    float vdn; // view.normal
};