package rs117.hd.opengl.shader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.opengl.uniforms.UniformBuffer;

import static org.lwjgl.opengl.GL33C.*;

@Slf4j
public class ShaderProgram {
	@AllArgsConstructor
	private static class UniformBufferBlockPair {
		public UniformBuffer buffer;
		public int bindingIndex;
	}

	private final List<UniformProperty> uniformProperties = new ArrayList<>();
	private final List<UniformBufferBlockPair> uniformBufferBlockPairs = new ArrayList<>();

	@Setter
	private ShaderTemplate shaderTemplate;
	private int program;

	public void compile(ShaderIncludes includes) throws ShaderException, IOException {
		int newProgram = shaderTemplate.compile(includes);

		if (isValid())
			destroy();

		program = newProgram;
		assert isValid();

		for (var prop : uniformProperties)
			prop.uniformIndex = glGetUniformLocation(program, prop.uniformName);

		for (UniformBuffer ubo : includes.getUniformBuffers()) {
			int bindingIndex = glGetUniformBlockIndex(program, ubo.getUniformBlockName());
			if (bindingIndex != -1)
				uniformBufferBlockPairs.add(new UniformBufferBlockPair(ubo, bindingIndex));
		}
	}

	public boolean isValid() {
		return program != 0;
	}

	@SuppressWarnings("unchecked")
	public <T extends UniformBuffer> T getUniformBufferBlock(int UniformBlockIndex) {
		for (UniformBufferBlockPair pair : uniformBufferBlockPairs)
			if (pair.buffer.getUniformBlockIndex() == UniformBlockIndex)
				return (T) pair.buffer;
		return null;
	}

	public void use() {
		assert program != 0;
		glUseProgram(program);

		for (UniformBufferBlockPair pair : uniformBufferBlockPairs)
			glUniformBlockBinding(program, pair.bindingIndex, pair.buffer.getUniformBlockIndex());
	}

	public void validate() throws ShaderException {
		glValidateProgram(program);
		if (glGetProgrami(program, GL_VALIDATE_STATUS) == GL_FALSE) {
			String err = glGetProgramInfoLog(program);
			throw new ShaderException(err);
		}
	}

	public static <T extends ShaderProgram> void destroyAll(T[] programs) {
		if (programs != null)
			for (T program : programs)
				if (program != null)
					program.destroy();
	}

	public void destroy() {
		if (program == 0)
			return;

		glDeleteProgram(program);
		program = 0;

		for (var prop : uniformProperties)
			prop.destroy();
	}

	private static class UniformProperty {
		String uniformName;
		int uniformIndex;

		void destroy() {
			uniformIndex = -1;
		}
	}

	public static class Uniform1i extends UniformProperty {
		public void set(int value) {
			glUniform1i(uniformIndex, value);
		}
	}

	public Uniform1i addUniform1i(String uniformName) {
		var newProperty = new Uniform1i();
		newProperty.uniformName = uniformName;
		uniformProperties.add(newProperty);
		return newProperty;
	}
}
