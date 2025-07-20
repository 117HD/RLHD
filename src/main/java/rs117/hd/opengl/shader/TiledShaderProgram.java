package rs117.hd.opengl.shader;

import org.lwjgl.opengl.*;

import static org.lwjgl.opengl.GL20.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER;

public class TiledShaderProgram extends ShaderProgram {

	public UniformProperty<Integer> uniTiledLightingTex = addUniformProperty("tiledLightingArray", GL33::glUniform1i);
	public UniformProperty<Integer> uniLayer = addUniformProperty("layer", GL33::glUniform1i);
	public UniformProperty<Integer> uniTileCountX = addUniformProperty("tileCountX", GL33::glUniform1i);
	public UniformProperty<Integer> uniTileCountY = addUniformProperty("tileCountY", GL33::glUniform1i);
	public UniformProperty<Integer> uniPointLightsCount = addUniformProperty("pointLightsCount", GL33::glUniform1i);
	public UniformProperty<float[]> uniCameraPos = addUniformProperty("cameraPos", GL33::glUniform3fv);
	public UniformProperty<float[]> uniInvProjectionMatrix = addUniformProperty(
		"invProjectionMatrix",
		(location, value) -> glUniformMatrix4fv(location, false, value)
	);

	public TiledShaderProgram() {
		setShader(new Shader()
			.add(GL_VERTEX_SHADER, "tiled_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "tiled_frag.glsl"));
	}
}
