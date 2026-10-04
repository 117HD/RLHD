package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_MINIMAP_MASK;

public class MinimapLineShaderProgram extends ShaderProgram {
	public final UniformTexture uniMaskTexture = addUniformTexture("maskTexture");
	public final Uniform2f uniFocusWorld = addUniform2f("focusWorld");
	public final Uniform1f uniCosYaw = addUniform1f("cosYaw");
	public final Uniform1f uniSinYaw = addUniform1f("sinYaw");
	public final Uniform1f uniPixelToWorld = addUniform1f("pixelToWorld");
	public final Uniform2f uniFboSize = addUniform2f("fboSize");
	public final Uniform1f uniMinHalfThicknessPx = addUniform1f("minHalfThicknessPx");

	public MinimapLineShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "minimap_lines_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "minimap_lines_frag.glsl"));
	}

	@Override
	protected void initialize() {
		uniMaskTexture.set(TEXTURE_UNIT_MINIMAP_MASK);
	}
}
