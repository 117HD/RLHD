#include LIGHT_COUNT

struct PointLight
{
    ivec3 position;
    float size;
    vec3 color;
    float strength;
};

layout(std140) uniform PointLightUniforms {
    PointLight PointLightArray[LIGHT_COUNT];
};

#include LIGHT_GETTER
