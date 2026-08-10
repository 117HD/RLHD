package rs117.hd.opengl.shader;

import java.io.IOException;

import static org.lwjgl.opengl.GL33C.*;

public abstract class SkyboxShaderProgram extends ShaderProgram {
	protected final UniformTexture uniSkyboxTexture = addUniformTexture("skyboxTexture");
	protected final UniformTexture uniSkyboxCubemap = addUniformTexture("skyboxCubemap");

	protected boolean isCubemap;

	SkyboxShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "skybox_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "skybox_frag.glsl"));
	}

	@Override
	public void compile(ShaderIncludes includes) throws ShaderException, IOException {
		super.compile(includes.copy().define("IS_CUBEMAP", isCubemap));
	}

	public void setSkyboxTexture(int textureUnit) {
		uniSkyboxTexture.set(textureUnit);
	}

	public void setSkyboxCubemap(int textureUnit) {
		uniSkyboxCubemap.set(textureUnit);
	}

	public static class Equirect extends SkyboxShaderProgram {
		public Equirect() {
			isCubemap = false;
			uniSkyboxCubemap.ignoreMissing = true;
		}
	}

	public static class Cubemap extends SkyboxShaderProgram {
		public Cubemap() {
			isCubemap = true;
			uniSkyboxTexture.ignoreMissing = true;
		}
	}
}
