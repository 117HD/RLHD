package rs117.hd.scene;

import net.runelite.api.Scene;
import org.junit.Before;
import org.junit.Test;
import rs117.hd.opengl.ShaderManager;
import rs117.hd.opengl.shader.Shader;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class SceneRendererTest {
    private SceneRenderer sceneRenderer;
    private ShaderManager mockShaderManager;
    private Scene mockScene;
    private Shader mockShader;

    @Before
    public void setUp() {
        mockShaderManager = mock(ShaderManager.class);
        mockScene = mock(Scene.class);
        mockShader = mock(Shader.class);
        
        sceneRenderer = new SceneRenderer();
        sceneRenderer.shaderManager = mockShaderManager;
    }

    @Test
    public void testRenderSceneWithValidScene() {
        // Given
        when(mockShaderManager.getShader("scene")).thenReturn(mockShader);
        sceneRenderer.setSceneValid(true);

        // When
        sceneRenderer.renderScene(mockScene);

        // Then
        verify(mockShaderManager).getShader("scene");
        assertTrue(sceneRenderer.isSceneValid());
    }

    @Test
    public void testRenderSceneWithInvalidScene() {
        // Given
        when(mockShaderManager.getShader("scene")).thenReturn(mockShader);
        sceneRenderer.setSceneValid(false);

        // When
        sceneRenderer.renderScene(mockScene);

        // Then
        verify(mockShaderManager).getShader("scene");
        assertTrue(sceneRenderer.isSceneValid());
    }

    @Test
    public void testRenderSceneWithNullShader() {
        // Given
        when(mockShaderManager.getShader("scene")).thenReturn(null);
        sceneRenderer.setSceneValid(true);

        // When
        sceneRenderer.renderScene(mockScene);

        // Then
        verify(mockShaderManager).getShader("scene");
        assertTrue(sceneRenderer.isSceneValid());
    }
} 