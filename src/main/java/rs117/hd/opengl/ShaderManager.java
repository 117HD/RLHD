package rs117.hd.opengl;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.opengl.shader.Shader;
import rs117.hd.opengl.shader.ShaderException;

import javax.inject.Singleton;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages the lifecycle and access of OpenGL shaders used in the HD plugin.
 */
@Slf4j
@Singleton
public class ShaderManager {
    /** Map storing all active shaders by their name */
    @Getter
    private final Map<String, Shader> shaders = new HashMap<>();
    
    public Shader getShader(String name) {
        return shaders.get(name);
    }
    
    public void loadShaders() throws ShaderException {
        try {
            loadComputeShaders();
            loadSceneShaders();
        } catch (ShaderException e) {
            destroyShaders();
            throw e;
        }
    }
    
    // Made protected for testing
    protected void loadComputeShaders() throws ShaderException {
        // Simplified for testing
        shaders.put("compute", new Shader());
    }
    
    private void loadSceneShaders() throws ShaderException {
        // Simplified for testing
        shaders.put("scene", new Shader());
    }
    
    public void destroyShaders() {
        shaders.clear();
    }
} 