package rs117.hd.opengl.shader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.opengl.uniforms.UniformBuffer;

import static org.lwjgl.opengl.GL33C.*;

@Slf4j
public class ShaderProgram {
	@RequiredArgsConstructor
	private static class UniformBufferBlockPair {
		public final UniformBuffer buffer;
		public final int uboProgramIndex;
	}

	private final List<UniformProperty> uniformProperties = new ArrayList<>();
	private final List<UniformBufferBlockPair> uniformBlockMappings = new ArrayList<>();

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

		for (var ubo : includes.uniformBuffers) {
			int bindingIndex = glGetUniformBlockIndex(program, ubo.getUniformBlockName());
			if (bindingIndex != -1)
				uniformBlockMappings.add(new UniformBufferBlockPair(ubo, bindingIndex));
		}
	}

	public boolean isValid() {
		return program != 0;
	}

	public boolean isActive() {
		// Meant for debugging only
		return program == glGetInteger(GL_CURRENT_PROGRAM);
	}

	@SuppressWarnings("unchecked")
	public <T extends UniformBuffer> T getUniformBufferBlock(int UniformBlockIndex) {
		for (UniformBufferBlockPair pair : uniformBlockMappings)
			if (pair.buffer.getBindingIndex() == UniformBlockIndex)
				return (T) pair.buffer;
		return null;
	}

	public void use() {
		assert program != 0;
		glUseProgram(program);

		for (UniformBufferBlockPair pair : uniformBlockMappings)
			glUniformBlockBinding(program, pair.uboProgramIndex, pair.buffer.getBindingIndex());
	}

	public void validate() {
		glValidateProgram(program);
		if (glGetProgrami(program, GL_VALIDATE_STATUS) == GL_FALSE) {
			String err = glGetProgramInfoLog(program);
			log.error("Failed to validate shader program: {}", getClass().getSimpleName(), new ShaderException(err));
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

		uniformBlockMappings.clear();
	}

	private static class UniformProperty {
		ShaderProgram program;
		String uniformName;
		int uniformIndex;

		void destroy() {
			uniformIndex = -1;
		}
	}

	public static class Uniform1i extends UniformProperty {
		public void set(int value) {
			assert program.isActive();
			glUniform1i(uniformIndex, value);
		}
	}

	public Uniform1i addUniform1i(String uniformName) {
		var prop = new Uniform1i();
		prop.program = this;
		prop.uniformName = uniformName;
		uniformProperties.add(prop);
		return prop;
	}
}
