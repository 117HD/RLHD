#pragma once

#include <utils/constants.glsl>

#if ZONE_RENDERER
    #include MAX_ZONE_DATA_COUNT

    struct ZoneData {
        int worldViewIdx;
        int offsetX;
        int offsetZ;
        float reveal;
    };

    layout(std140) uniform UBOZoneData {
        ZoneData ZoneDataArray[MAX_ZONE_DATA_COUNT];
    };

    #include ZONE_DATA_GETTER

    int getZoneWorldViewIdx(int zoneIdx) {
        if (zoneIdx <= 0)
            return 0;
        return getZoneData(zoneIdx - 1).worldViewIdx;
    }

    vec3 getZoneSceneOffset(int zoneIdx) {
        if (zoneIdx <= 0)
            return vec3(0, 0, 0);
        ZoneData data = getZoneData(zoneIdx - 1);
        return vec3(data.offsetX, 0.0, data.offsetZ);
    }

    float getZoneReveal(int zoneIdx) {
        if (zoneIdx <= 0)
            return 0.0f;
        return getZoneData(zoneIdx - 1).reveal;
    }
#else
    // TODO: Define Stubs
#endif
