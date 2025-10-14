package rs117.hd.renderer;

import java.io.IOException;
import javax.annotation.Nullable;
import net.runelite.api.hooks.*;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.scene.SceneContext;

public interface Renderer extends DrawCallbacks {
	default int getGpuFlags() {
		return 0;
	}
	default void initialize() {}
	default void destroy() {}
	default void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {}
	default void destroyShaders() {}
	default void reuploadScene() {}
	default boolean isLoadingScene() {
		return false;
	}
	default void waitUntilIdle() {}
	@Nullable
	default SceneContext getSceneContext() {
		return null;
	}
}
