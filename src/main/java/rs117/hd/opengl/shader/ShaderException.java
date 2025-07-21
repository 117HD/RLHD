/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
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

import java.util.Arrays;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ShaderException extends Exception
{
	private static final Pattern NVIDIA_ERROR_REGEX = Pattern.compile("^(\\d+)\\((\\d+)\\) : (.*)$", Pattern.MULTILINE);

	static ShaderException compileError(String error, ShaderIncludes includes, ShaderTemplate.Unit... units)
	{
		StringBuilder sb = new StringBuilder();
		if (includes.includeType == ShaderIncludes.Type.GLSL) {
			Matcher m = NVIDIA_ERROR_REGEX.matcher(error);
			if (m.find()) {
				try {
					sb.append(String.format("Compile error when compiling shader%s: %s\n",
						units.length == 1 ? "" : "s",
						Arrays.stream(units)
							.map(u -> u.filename)
							.collect(Collectors.joining(", "))));

					int offset = 0;
					do {
						if (m.start() > offset)
							sb.append(error, offset, m.start());
						offset = m.end();

						int index = Integer.parseInt(m.group(1));
						int lineNumber = Integer.parseInt(m.group(2));
						String errorString = m.group(3);
						String include = includes.includeList.get(index);
						sb.append(String.format(
							"%s line %d - %s",
							include, lineNumber, errorString));
					} while (m.find());
				} catch (Exception ex) {
					log.error("Error while parsing shader compilation error:", ex);
				}
			}
			else
			{
				// Unknown error format, so include a mapping from source file index to filename
				sb
					.append("Compile error while compiling shader")
					.append(units.length == 1 ? "" : "s")
					.append(": ")
					.append(Arrays.stream(units)
						.map(u -> u.filename)
						.collect(Collectors.joining(", ")))
					.append("\n")
					.append(error)
					.append("Included sources: [\n");
				for (int j = 0; j < includes.includeList.size(); j++) {
					String s = String.valueOf(j);
					sb
						.append("  ")
						.append(String.join("", Collections.nCopies( // Left pad
							1 + (int) Math.log10(includes.includeList.size()) - s.length(), " ")
						))
						.append(s)
						.append(": ")
						.append(includes.includeList.get(j))
						.append("\n");
				}
				sb.append("]");
			}
		}

		return new ShaderException(sb.toString());
	}

	public ShaderException(String message)
	{
		super(message);
	}
}
