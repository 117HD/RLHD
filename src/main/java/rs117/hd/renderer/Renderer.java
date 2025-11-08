package rs117.hd.renderer;

import java.io.IOException;
import java.util.Set;
import javax.annotation.Nullable;
import net.runelite.api.hooks.*;
import org.lwjgl.opengl.*;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.scene.SceneContext;

public interface Renderer extends DrawCallbacks {
	default boolean supportsGpu(GLCapabilities glCaps) {
		return true;
	}
	default int gpuFlags() {
		return 0;
	}
	default void initialize() {}
	default void destroy() {}
	default void addShaderIncludes(ShaderIncludes includes) {}
	default void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {}
	default void destroyShaders() {}
	default void waitUntilIdle() {}
	default void processConfigChanges(Set<String> keys) {}
	default boolean isLoadingScene() {
		return false;
	}
	default void reloadScene() {}
	default void clearCaches() {}
	@Nullable
	default SceneContext getSceneContext() {
		return null;
	}
}
