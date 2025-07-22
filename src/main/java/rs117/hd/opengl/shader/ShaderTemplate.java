/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * Copyright (c) 2020 Abex
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package rs117.hd.opengl.shader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.opengl.shader.ShaderIncludes.SHADER_DUMP_PATH;

@Slf4j
public class ShaderTemplate
{
	private final List<Unit> units = new ArrayList<>();

	@RequiredArgsConstructor
	public static class Unit
	{
		public final int type;
		public final String filename;
	}

	public ShaderTemplate add(int type, String name)
	{
		units.add(new Unit(type, name));
		return this;
	}

	public int compile(ShaderIncludes includes) throws ShaderException, IOException
	{
		int program = glCreateProgram();
		int[] shaders = new int[units.size()];
		int i = 0;
		boolean ok = false;

		try
		{
			while (i < shaders.length) {
				Unit unit = units.get(i);
				int shader = glCreateShader(unit.type);
				if (shader == 0) {
					throw new ShaderException("Unable to create shader of type " + unit.type);
				}

				String source = includes.loadFile(unit.filename);
				glShaderSource(shader, source);
				glCompileShader(shader);

				if (glGetShaderi(shader, GL_COMPILE_STATUS) != GL_TRUE)
				{
					String error = glGetShaderInfoLog(shader);
					glDeleteShader(shader);
					throw ShaderException.compileError(includes, source, error, unit.filename);
				}

				glAttachShader(program, shader);
				shaders[i++] = shader;
			}

			glLinkProgram(program);

			if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
				String[] paths = units.stream()
					.map(u -> u.filename)
					.toArray(String[]::new);
				throw ShaderException.compileError(
					includes,
					"// Linking " + String.join(" & ", paths),
					glGetProgramInfoLog(program),
					paths
				);
			}

			ok = true;

			if (SHADER_DUMP_PATH != null) {
				int[] numFormats = { 0 };
				glGetIntegerv(GL41C.GL_NUM_PROGRAM_BINARY_FORMATS, numFormats);
				if (numFormats[0] < 1) {
					log.error("OpenGL driver does not support any binary formats");
				} else {
					int[] size = { 0 };
					glGetProgramiv(program, GL41C.GL_PROGRAM_BINARY_LENGTH, size);

					int[] format = { 0 };
					ByteBuffer binary = BufferUtils.createByteBuffer(size[0]);
					GL41C.glGetProgramBinary(program, size, format, binary);

					String shaderName =
						units.stream()
							.map(u -> u.filename)
							.collect(Collectors.joining(" + ")) + ".bin";
					SHADER_DUMP_PATH.resolve("binaries", shaderName).mkdirs().writeByteBuffer(binary);
				}
			}
		}
		finally
		{
			while (i > 0)
			{
				int shader = shaders[--i];
				glDetachShader(program, shader);
				glDeleteShader(shader);
			}

			if (!ok)
			{
				glDeleteProgram(program);
			}
		}

		return program;
	}
}
