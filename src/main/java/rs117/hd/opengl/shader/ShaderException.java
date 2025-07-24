/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * Copyright (c) 2025, Hooder <ahooder@protonmail.com>
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

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ShaderException extends Exception
{
	private static final Pattern NVIDIA_GL_ERROR_REGEX = Pattern.compile("^(\\d+)\\((\\d+)\\) : (.*)$", Pattern.MULTILINE);
	private static final Pattern NVIDIA_CL_ERROR_REGEX = Pattern.compile("^<kernel>:(\\d+):(\\d+): (.*)$", Pattern.MULTILINE);
	private static final Pattern CL_LINE_REGEX = Pattern.compile("^#line (\\d+) \"(.*)\"", Pattern.MULTILINE);

	public ShaderException(String message) {
		super(message);
	}

	public static ShaderException compileError(ShaderIncludes includes, String source, String error, String... paths)
	{
		boolean linkerError = paths.length > 1;

		StringBuilder sb = new StringBuilder();
		sb.append("Error when ");
		if (linkerError) {
			sb.append("linking shaders");
		} else {
			sb.append("compiling shader");
		}
		sb.append(": ");
		for (int i = 0; i < paths.length; i++) {
			if (i > 0)
				sb.append(" & ");
			sb.append(paths[i]);
		}
		sb.append('\n');

		// We can't parse linker errors without making line directives unique across different shader units
		if (linkerError)
			return new ShaderException(sb.append(error).toString());

		switch (includes.includeType) {
			case GLSL: {
				Matcher m = NVIDIA_GL_ERROR_REGEX.matcher(error);
				if (m.find()) {
					try {
						int prevEnd = 0;
						do {
							if (m.start() > prevEnd)
								sb.append(error, prevEnd, m.start());
							prevEnd = m.end();

							int index = Integer.parseInt(m.group(1));
							int lineNumber = Integer.parseInt(m.group(2));
							String errorString = m.group(3);
							String include = includes.includeList.get(index);
							sb.append(String.format("%s line %d - %s", include, lineNumber, errorString));
						} while (m.find());
						return new ShaderException(sb.toString());
					} catch (Exception ex) {
						log.error("Error while parsing shader compilation error:", ex);
						break;
					}
				}
				break;
			}
			case C:
				Matcher m = NVIDIA_CL_ERROR_REGEX.matcher(error);
				if (m.find()) {
					try {
						int prevEnd = 0;
						do {
							if (m.start() > prevEnd)
								sb.append(error, prevEnd, m.start());
							prevEnd = m.end();

							int lineNumber = Integer.parseInt(m.group(1));
							String errorString = m.group(3);
							String include = "Unknown source";

							var lm = CL_LINE_REGEX.matcher("");
							String[] lines = source.split("\n", lineNumber);
							for (int i = lineNumber - 2; i >= 0; i--) {
								lm.reset(lines[i]);
								if (lm.find()) {
									lineNumber += Integer.parseInt(lm.group(1)) - i - 2;
									include = lm.group(2).replaceAll("\\\\\"", "\"");
									break;
								}
							}

							sb.append(String.format("%s line %d - %s", include, lineNumber, errorString));
						} while (m.find());
						return new ShaderException(sb.toString());
					} catch (Exception ex) {
						log.error("Error while parsing shader compilation error:", ex);
						break;
					}
				}
				break;
		}

		// Unknown error format, so include a mapping from source file indices to paths
		sb.append(error).append("Included sources: [\n");
		int maxIndexWidth = String.valueOf(includes.includeList.size() - 1).length();
		String indexFormat = String.format("%%%dd", maxIndexWidth + 2);
		for (int i = 0; i < includes.includeList.size(); i++)
			sb
				.append(String.format(indexFormat, i))
				.append(": ")
				.append(includes.includeList.get(i))
				.append('\n');
		sb.append("]");

		return new ShaderException(error);
	}
}
