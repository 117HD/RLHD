package rs117.hd.opengl.shader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.opengl.uniforms.UniformBuffer;

import static org.lwjgl.opengl.GL33.GL_FALSE;
import static org.lwjgl.opengl.GL33.glGetUniformLocation;
import static org.lwjgl.opengl.GL33.GL_VALIDATE_STATUS;
import static org.lwjgl.opengl.GL33.glDeleteProgram;
import static org.lwjgl.opengl.GL33.glGetProgramInfoLog;
import static org.lwjgl.opengl.GL33.glGetProgrami;
import static org.lwjgl.opengl.GL33.glUseProgram;
import static org.lwjgl.opengl.GL33.glValidateProgram;
import static org.lwjgl.opengl.GL33.glUniformBlockBinding;
import static org.lwjgl.opengl.GL33.glGetUniformBlockIndex;

@Slf4j
public class ShaderProgram {
	private static class UniformBufferBlockPair {
		public UniformBuffer buffer;
		public int bindingIndex;
	}

	private final List<UniformProperty<?>> uniformProperties = new ArrayList<>();
	private final List<UniformBufferBlockPair> uniformBufferBlockPairs = new ArrayList<>();

	@Setter private Shader shader;
	private int program;

	public ShaderProgram compile(Template template) throws ShaderException, IOException {
		assert program == 0;
		program = shader.compile(template);

		if(program != 0) {
			for (UniformProperty<?> prop : uniformProperties) {
				prop.uniformIndex = glGetUniformLocation(program, prop.uniformName);
			}

			for(UniformBuffer ubo : template.getUniformBuffers()) {
				int bindingIndex = glGetUniformBlockIndex(program, ubo.getUniformBlockName());
				if(bindingIndex != -1) {
					UniformBufferBlockPair newPair = new UniformBufferBlockPair();
					newPair.bindingIndex = bindingIndex;
					newPair.buffer = ubo;
					uniformBufferBlockPairs.add(newPair);
				}
			}
		}

		return this;
	}

	public boolean isValid() {
		return program != 0;
	}

	public <T> UniformProperty<T> addUniformProperty(String uniformName, UniformFunction<T> glFunction) {
		UniformProperty<T> newProperty = new UniformProperty<>(uniformName, glFunction);
		uniformProperties.add(newProperty);
		return newProperty;
	}

	public <T extends UniformBuffer> T getUniformBufferBlock(int UniformBlockIndex) {
		for(UniformBufferBlockPair pair : uniformBufferBlockPairs) {
			if(pair.buffer.getUniformBlockIndex() == UniformBlockIndex) {
				return (T) pair.buffer;
			}
		}
		return null;
	}

	public void use() {
		assert program != 0;
		glUseProgram(program);

		for(UniformBufferBlockPair pair : uniformBufferBlockPairs) {
			glUniformBlockBinding(program, pair.bindingIndex, pair.buffer.getUniformBlockIndex());
		}
	}

	public void validate() throws ShaderException {
		glValidateProgram(program);
		if (glGetProgrami(program, GL_VALIDATE_STATUS) == GL_FALSE) {
			String err = glGetProgramInfoLog(program);
			throw new ShaderException(err);
		}
	}

	public static <T extends ShaderProgram> void destroyAll(T[] programs) {
		if(programs != null){
			for(T program : programs) {
				if(program != null) {
					program.destroy();
				}
			}
		}
	}

	public void destroy() {
		if(program != 0) {
			glDeleteProgram(program);
			program = 0;

			for(UniformProperty<?> prop : uniformProperties){
				prop.uniformIndex = -1;
			}
		}
	}

	public interface UniformFunction<T> {
		void set(int uniformIDx, T value);
	}

	@AllArgsConstructor
	@RequiredArgsConstructor
	public static class UniformProperty<T> {
		private final String uniformName;
		private final UniformFunction<T> glFunction;
		private int uniformIndex = -1;

		public boolean isValid() {
			return uniformIndex != -1;
		}

		public void set(T value) {
			if(uniformIndex != -1) {
				glFunction.set(uniformIndex, value);
			}
		}
	}
}
