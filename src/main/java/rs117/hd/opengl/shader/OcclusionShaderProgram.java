package rs117.hd.opengl.shader;

import rs117.hd.HdPlugin;

import static org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER;
import static rs117.hd.HdPlugin.GL_CAPS;

public class OcclusionShaderProgram extends ShaderProgram {
	public OcclusionShaderProgram() {
		super(t -> t.add(GL_VERTEX_SHADER, "occlusion_vert.glsl"));
	}

	@Override
	protected void initialize() {
		super.initialize();
		if(HdPlugin.APPLE || !GL_CAPS.OpenGL46)
			shaderTemplate.add(GL_FRAGMENT_SHADER, "depth_frag.glsl");
	}

	public static class Debug extends OcclusionShaderProgram {
		public Uniform1i queryId = addUniform1i("queryId");

		@Override
		protected void initialize() {
			super.initialize();
			shaderTemplate.remove(GL_FRAGMENT_SHADER);
			shaderTemplate.add(GL_FRAGMENT_SHADER, "occlusion_debug_frag.glsl");
		}
	}
}
