package rs117.hd.opengl.shader;

import org.lwjgl.opengl.*;

public class ComputeModelShaderProgram extends ShaderProgram {
	public ComputeModelShaderProgram(boolean isUnordered) {
		setShader(new Shader().add(GL43C.GL_COMPUTE_SHADER, isUnordered ? "comp_unordered.glsl" : "comp.glsl"));
	}
}
