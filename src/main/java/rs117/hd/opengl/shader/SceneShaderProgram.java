package rs117.hd.opengl.shader;

import org.lwjgl.opengl.*;

import static org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL32C.GL_GEOMETRY_SHADER;

public class SceneShaderProgram extends ShaderProgram {
	public UniformProperty<Integer> uniTextureArray = addUniformProperty("textureArray", GL33::glUniform1i);
	public UniformProperty<Integer> uniShadowMap = addUniformProperty("shadowMap", GL33::glUniform1i);

	public SceneShaderProgram() {
		setShader(new Shader()
			.add(GL_VERTEX_SHADER, "vert.glsl")
			.add(GL_GEOMETRY_SHADER, "geom.glsl")
			.add(GL_FRAGMENT_SHADER, "frag.glsl"));
	}
}
