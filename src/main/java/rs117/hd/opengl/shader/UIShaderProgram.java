package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.opengl.GLBinding.BINDING_TEX_UI;

public class UIShaderProgram extends ShaderProgram {
	private final UniformTexture uniUiTexture = addUniformTexture("uiTexture");

	public UIShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "ui_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "ui_frag.glsl"));
	}

	@Override
	protected void initialize() {
		uniUiTexture.set(BINDING_TEX_UI);
	}
}
