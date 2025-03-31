package rs117.hd.opengl;

import org.junit.Before;
import org.junit.Test;
import rs117.hd.opengl.shader.Shader;
import rs117.hd.opengl.shader.ShaderException;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ShaderManagerTest {
    private ShaderManager shaderManager;

    @Before
    public void setUp() {
        shaderManager = new ShaderManager();
    }

    @Test
    public void testGetShaderReturnsNullForNonExistentShader() {
        assertNull("Should return null for non-existent shader", shaderManager.getShader("nonexistent"));
    }

    @Test
    public void testLoadShadersCreatesRequiredShaders() throws ShaderException {
        // When
        shaderManager.loadShaders();

        // Then
        assertNotNull("Should create compute shader", shaderManager.getShader("compute"));
        assertNotNull("Should create scene shader", shaderManager.getShader("scene"));
    }

    @Test
    public void testDestroyShadersClearsAllShaders() throws ShaderException {
        // Given
        shaderManager.loadShaders();
        assertTrue("Should have shaders before destroy", shaderManager.getShaders().size() > 0);

        // When
        shaderManager.destroyShaders();

        // Then
        assertTrue("Should have no shaders after destroy", shaderManager.getShaders().isEmpty());
    }

    @Test(expected = ShaderException.class)
    public void testLoadShadersFailureCleanup() throws ShaderException {
        // Given
        ShaderManager spyManager = spy(shaderManager);
        doThrow(new ShaderException("Test failure")).when(spyManager).loadComputeShaders();

        // When/Then
        spyManager.loadShaders(); // Should throw exception
    }
} 