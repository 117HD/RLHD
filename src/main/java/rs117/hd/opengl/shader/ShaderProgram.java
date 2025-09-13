package rs117.hd.opengl.shader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.opengl.uniforms.UniformBuffer;

import static org.lwjgl.opengl.GL33C.*;

@Slf4j
public class ShaderProgram {
	@RequiredArgsConstructor
	private static class UniformBufferBlockPair {
		public final UniformBuffer<?> buffer;
		public final int uboProgramIndex;
	}

	private final List<UniformProperty> uniformProperties = new ArrayList<>();
	private final List<UniformBufferBlockPair> uniformBlockMappings = new ArrayList<>();

	protected final ShaderTemplate shaderTemplate;

	private int program;
	@Getter
	private boolean viable = true;

	public ShaderProgram(Consumer<ShaderTemplate> templateConsumer) {
		shaderTemplate = new ShaderTemplate();
		templateConsumer.accept(shaderTemplate);
	}

	public void compile(ShaderIncludes includes) throws ShaderException, IOException {
		int newProgram;
		try {
			newProgram = shaderTemplate.compile(includes);
		} catch (ShaderException ex) {
			viable = false;
			throw ex;
		}

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

		use();
		initialize();

		glValidateProgram(program);
		if (glGetProgrami(program, GL_VALIDATE_STATUS) == GL_FALSE) {
			String err = glGetProgramInfoLog(program);
			log.error("Failed to validate shader program: {}", getClass().getSimpleName(), new ShaderException(err));
		}
	}

	protected void initialize() {}

	public boolean isValid() {
		return program != 0;
	}

	public boolean isActive() {
		// Meant for debugging only
		return program == glGetInteger(GL_CURRENT_PROGRAM);
	}

	@SuppressWarnings("unchecked")
	public <T extends UniformBuffer<?>> T getUniformBufferBlock(int UniformBlockIndex) {
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

	public void destroy() {
		viable = true;
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

	private <T extends UniformProperty> T addUniform(T property, String uniformName) {
		property.program = this;
		property.uniformName = uniformName;
		uniformProperties.add(property);
		return property;
	}

	public static class UniformBool extends UniformProperty {
		public void set(boolean bool) {
			assert program.isActive();
			glUniform1i(uniformIndex, bool ? 1 : 0);
		}
	}

	public UniformBool addUniformBool(String uniformName) {
		return addUniform(new UniformBool(), uniformName);
	}

	public static class UniformTexture extends UniformProperty {
		public void set(int textureUnit) {
			assert textureUnit >= GL_TEXTURE0 : "Did you accidentally pass in an image unit?";
			assert program.isActive();
			glUniform1i(uniformIndex, textureUnit - GL_TEXTURE0);
		}
	}

	public UniformTexture addUniformTexture(String uniformName) {
		return addUniform(new UniformTexture(), uniformName);
	}

	public static class UniformImage extends UniformProperty {
		public void set(int imageUnit) {
			assert imageUnit < GL_TEXTURE0 : "Did you accidentally pass in a texture unit?";
			assert program.isActive();
			glUniform1i(uniformIndex, imageUnit);
		}
	}

	public UniformImage addUniformImage(String uniformName) {
		return addUniform(new UniformImage(), uniformName);
	}

	public static class Uniform1i extends UniformProperty {
		public void set(int value) {
			assert program.isActive();
			glUniform1i(uniformIndex, value);
		}
	}

	public Uniform1i addUniform1i(String uniformName) {
		return addUniform(new Uniform1i(), uniformName);
	}

	public static class Uniform2i extends UniformProperty {
		public void set(int x, int y) {
			assert program.isActive();
			glUniform2i(uniformIndex, x, y);
		}

		public void set(int... ivec2) {
			assert program.isActive();
			glUniform2iv(uniformIndex, ivec2);
		}
	}

	public Uniform2i addUniform2i(String uniformName) {
		return addUniform(new Uniform2i(), uniformName);
	}

	public static class Uniform3i extends UniformProperty {
		public void set(int x, int y, int z) {
			assert program.isActive();
			glUniform3i(uniformIndex, x, y, z);
		}

		public void set(int... ivec3) {
			assert program.isActive();
			glUniform3iv(uniformIndex, ivec3);
		}
	}

	public Uniform3i addUniform3i(String uniformName) {
		return addUniform(new Uniform3i(), uniformName);
	}

	public static class Uniform4i extends UniformProperty {
		public void set(int x, int y, int z, int w) {
			assert program.isActive();
			glUniform4i(uniformIndex, x, y, z, w);
		}

		public void set(int... ivec4) {
			assert program.isActive();
			glUniform4iv(uniformIndex, ivec4);
		}
	}

	public Uniform4i addUniform4i(String uniformName) {
		return addUniform(new Uniform4i(), uniformName);
	}

	public static class Uniform1f extends UniformProperty {
		public void set(float value) {
			assert program.isActive();
			glUniform1f(uniformIndex, value);
		}
	}

	public Uniform1f addUniform1f(String uniformName) {
		return addUniform(new Uniform1f(), uniformName);
	}

	public static class Uniform2f extends UniformProperty {
		public void set(float x, float y) {
			assert program.isActive();
			glUniform2f(uniformIndex, x, y);
		}

		public void set(float... vec2) {
			assert program.isActive();
			glUniform2fv(uniformIndex, vec2);
		}
	}

	public Uniform2f addUniform2f(String uniformName) {
		return addUniform(new Uniform2f(), uniformName);
	}

	public static class Uniform3f extends UniformProperty {
		public void set(float x, float y, float z) {
			assert program.isActive();
			glUniform3f(uniformIndex, x, y, z);
		}

		public void set(float... vec3) {
			assert program.isActive();
			glUniform3fv(uniformIndex, vec3);
		}
	}

	public Uniform3f addUniform3f(String uniformName) {
		return addUniform(new Uniform3f(), uniformName);
	}

	public static class Uniform4f extends UniformProperty {
		public void set(float x, float y, float z, float w) {
			assert program.isActive();
			glUniform4f(uniformIndex, x, y, z, w);
		}

		public void set(float... vec4) {
			assert program.isActive();
			glUniform4fv(uniformIndex, vec4);
		}
	}

	public Uniform4f addUniform4f(String uniformName) {
		return addUniform(new Uniform4f(), uniformName);
	}

	public static class UniformMat4 extends UniformProperty {
		public void set(float[] mat4) {
			assert program.isActive();
			glUniformMatrix4fv(uniformIndex, false, mat4);
		}
	}

	public UniformMat4 addUniformMat4(String uniformName) {
		return addUniform(new UniformMat4(), uniformName);
	}
}
