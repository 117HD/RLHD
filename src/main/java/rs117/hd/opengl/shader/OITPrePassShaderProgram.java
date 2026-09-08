package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER;
public class OITPrePassShaderProgram extends SceneShaderProgram {
	public OITPrePassShaderProgram() {
		shaderTemplate.add(GL_FRAGMENT_SHADER, "oit_prepass_frag.glsl");
	}
}
