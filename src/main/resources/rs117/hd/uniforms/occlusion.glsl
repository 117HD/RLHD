#pragma once

layout(std140) uniform UBOOcclusion {
    vec3 aabbs[4000];
};

