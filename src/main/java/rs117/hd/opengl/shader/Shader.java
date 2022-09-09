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

import com.google.common.annotations.VisibleForTesting;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.BufferUtils;
import rs117.hd.utils.Env;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.lwjgl.opengl.GL43C.*;
import static rs117.hd.utils.ResourcePath.path;

@Slf4j
public class Shader
{
	@VisibleForTesting
	final List<Unit> units = new ArrayList<>();

	@RequiredArgsConstructor
	@VisibleForTesting
	static class Unit
	{
		@Getter
		private final int type;

		@Getter
		private final String filename;
	}

	public Shader add(int type, String name)
	{
		units.add(new Unit(type, name));
		return this;
	}

	Pattern NVIDIA_ERROR_REGEX = Pattern.compile("^(\\d+)\\((\\d+)\\) : (.*)");

	public int compile(Template template) throws ShaderException
	{
		int program = glCreateProgram();
		int[] shaders = new int[units.size()];
		int i = 0;
		boolean ok = false;
		try
		{
			while (i < shaders.length)
			{
				Unit unit = units.get(i);
				int shader = glCreateShader(unit.type);
				if (shader == 0)
				{
					throw new ShaderException("Unable to create shader of type " + unit.type);
				}

				String source = template.load(unit.filename);
				glShaderSource(shader, source);
				glCompileShader(shader);

				if (glGetShaderi(shader, GL_COMPILE_STATUS) != GL_TRUE)
				{
					String err = glGetShaderInfoLog(shader);
					glDeleteShader(shader);

					if (template.includeType == Template.IncludeType.GLSL) {
						Matcher m = NVIDIA_ERROR_REGEX.matcher(err);
						if (m.find()) {
							try {
								int index = Integer.parseInt(m.group(1));
								int lineNumber = Integer.parseInt(m.group(2));
								String error = m.group(3);
								String include = template.includeList.get(index);
								err = String.format(
									"Compile error in '%s' on line %d when compiling shader '%s':\n\n%s\n",
									include, lineNumber, unit.filename, error);
							} catch (Exception ex) {
								log.error("Error while parsing shader compilation error:", ex);
							}
						}
						else
						{
							// Unknown error format, so include a mapping from source file index to filename
							StringBuilder sb = new StringBuilder();
							sb
								.append("Compile error while compiling shader '")
								.append(unit.filename)
								.append("':\n\n")
								.append(err)
								.append("\nIncluded sources: [\n");
							for (int j = 0; j < template.includeList.size(); j++) {
								String s = String.valueOf(j);
								sb
									.append("  ")
									.append(String.join("", Collections.nCopies( // Left pad
										1 + (int) Math.log10(template.includeList.size()) - s.length(), " ")))
									.append(s)
									.append(": ")
									.append(template.includeList.get(j))
									.append("\n");
							}
							sb.append("]\n");
							err = sb.toString();
						}
					}

					throw new ShaderException(err);
				}

				glAttachShader(program, shader);
				shaders[i++] = shader;
			}

			glLinkProgram(program);

			if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
			{
				String err = glGetProgramInfoLog(program);
				throw new ShaderException(err);
			}

			ok = true;

			if (Env.has("RLHD_DUMP_SHADERS"))
			{
				int[] numFormats = { 0 };
				glGetIntegerv(GL_NUM_PROGRAM_BINARY_FORMATS, numFormats);
				if (numFormats[0] < 1) {
					log.error("OpenGL driver does not support any binary formats");
				} else {
					int[] size = { 0 };
					glGetProgramiv(program, GL_PROGRAM_BINARY_LENGTH, size);

					int[] format = { 0 };
					ByteBuffer binary = BufferUtils.createByteBuffer(size[0]);
					glGetProgramBinary(program, size, format, binary);

					try {
						String shaderName = units.stream()
							.map(Unit::getFilename)
							.collect(Collectors.joining(" + ")) + ".bin";
						path("shader-dumps", shaderName)
							.mkdirs()
							.writeByteBuffer(binary);
					} catch (IOException ex) {
						throw new RuntimeException(ex);
					}
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
