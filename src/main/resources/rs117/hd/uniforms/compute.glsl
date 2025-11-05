#pragma once

layout(std140) uniform UBOCompute {
    float cameraYaw;
    float cameraPitch;
    int centerX;
    int centerY;
    int zoom;
    float cameraX; float cameraY; float cameraZ; // Here be dragons on macOS if converted to vec3
};
