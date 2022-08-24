layout(std140) uniform CameraUniforms {
    int cameraYaw;
    int cameraPitch;
    int centerX;
    int centerY;
    int zoom;
    int cameraX;
    int cameraY;
    int cameraZ;
    ivec2 sinCosTable[2048];
};
