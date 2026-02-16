package rs117.hd.opengl.shader;

import rs117.hd.HdPlugin;

import static org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER;

public class OcclusionShaderProgram extends ShaderProgram {
	public OcclusionShaderProgram() {
		super(t -> {
			t.add(GL_VERTEX_SHADER, "occlusion_vert.glsl");
			if(HdPlugin.APPLE)
				t.add(GL_FRAGMENT_SHADER, "depth_frag.glsl");
		});
	}

	public static class Debug extends OcclusionShaderProgram {
		public Uniform1i queryId = addUniform1i("queryId");

		public Debug() {
			shaderTemplate.remove(GL_FRAGMENT_SHADER).add(GL_FRAGMENT_SHADER, "occlusion_debug_frag.glsl");
		}
	}
}
