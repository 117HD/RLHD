package rs117.hd.config;

import lombok.Getter;

@Getter
public class ConfigManager {
    private final HdPluginConfig config;
    
    public ConfigManager(HdPluginConfig config) {
        this.config = config;
    }
    
    public AntiAliasingMode getAntiAliasingMode() {
        return config.antiAliasingMode();
    }
    
    public int getShadowDistance() {
        return config.shadowDistance();
    }
    
    public boolean isTrueTerrainHeight() {
        return config.trueTerrainHeight();
    }
    
    public boolean isHdInfernalTexture() {
        return config.hdInfernalTexture();
    }
    
    public int getObjectTextures() {
        return config.objectTextures();
    }
    
    public int getGroundTextures() {
        return config.groundTextures();
    }
} 