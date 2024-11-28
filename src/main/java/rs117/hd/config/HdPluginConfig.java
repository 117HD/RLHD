package rs117.hd.config;

public class HdPluginConfig {
    public AntiAliasingMode antiAliasingMode() {
        return AntiAliasingMode.MSAA_4X;
    }
    
    public boolean configUndoVanillaShading() {
        return true;
    }
    
    public int uiScalingMode() {
        return 0;
    }
    
    public int shadowDistance() {
        return 50;
    }
    
    public boolean trueTerrainHeight() {
        return true;
    }
    
    public boolean hdInfernalTexture() {
        return true;
    }
    
    public int objectTextures() {
        return 100;
    }
    
    public int groundTextures() {
        return 100;
    }
    
    public ColorBlindMode colorBlindness() {
        return ColorBlindMode.NONE;
    }
} 