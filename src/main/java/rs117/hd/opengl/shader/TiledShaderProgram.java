package rs117.hd.opengl.shader;

import org.lwjgl.opengl.*;

import static org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER;

public class TiledShaderProgram extends ShaderProgram {

	public UniformProperty<Integer> uniTiledLightingTex = addUniformProperty("tiledLightingArray", GL33::glUniform1i);

	public TiledShaderProgram() {
		setShader(new Shader()
			.add(GL_VERTEX_SHADER, "tiled_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "tiled_frag.glsl"));
	}
}
