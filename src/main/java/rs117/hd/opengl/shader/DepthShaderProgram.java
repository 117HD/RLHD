package rs117.hd.opengl.shader;

import java.io.IOException;
import rs117.hd.HdPlugin;

import static org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER_DERIVATIVE_HINT;
import static org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER;
import static rs117.hd.HdPlugin.GL_CAPS;
import static rs117.hd.renderer.zone.ZoneRenderer.TEXTURE_UNIT_TEXTURED_FACES;

public class DepthShaderProgram extends ShaderProgram {
	public DepthShaderProgram() {
		super(t -> t.add(GL_VERTEX_SHADER, "depth_vert.glsl"));
	}

	@Override
	public void compile(ShaderIncludes includes) throws ShaderException, IOException {
		if(HdPlugin.APPLE || !GL_CAPS.OpenGL46)
			shaderTemplate.add(GL_FRAGMENT_SHADER, "depth_frag.glsl");
		super.compile(includes);
	}
}
