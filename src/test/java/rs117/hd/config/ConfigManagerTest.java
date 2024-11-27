package rs117.hd.config;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class ConfigManagerTest {
    @Test
    public void testGetAntiAliasingMode() {
        HdPluginConfig config = new HdPluginConfig();
        ConfigManager configManager = new ConfigManager(config);
        
        AntiAliasingMode expectedMode = AntiAliasingMode.MSAA_4X;
        AntiAliasingMode actualMode = configManager.getAntiAliasingMode();
        
        assertEquals(expectedMode, actualMode);
    }
    
    @Test
    public void testGetShadowDistance() {
        HdPluginConfig config = new HdPluginConfig();
        ConfigManager configManager = new ConfigManager(config);
        
        int expectedDistance = 50;
        int actualDistance = configManager.getShadowDistance();
        
        assertEquals(expectedDistance, actualDistance);
    }
} 