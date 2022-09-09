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

import com.google.common.io.CharStreams;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.ResourcePath;

@Slf4j
public class Template
{
	enum IncludeType { GLSL, C, UNKNOWN }

	private final List<Function<String, String>> resourceLoaders = new ArrayList<>();
	private final Stack<Integer> includeStack = new Stack<>();

	IncludeType includeType = IncludeType.UNKNOWN;
	final ArrayList<String> includeList = new ArrayList<>();

	public String process(String str)
	{
		StringBuilder sb = new StringBuilder();
		int lineCount = 0;
		for (String line : str.split("\r?\n"))
		{
			lineCount++;
			String trimmed = line.trim();
			if (trimmed.startsWith("#include "))
			{
				String currentFile = includeList.get(includeList.size() - 1);
				int currentIndex = includeStack.peek();

				String includeFile = trimmed.substring(9);
				int includeIndex = includeList.size();
				includeList.add(includeFile);
				includeStack.push(includeIndex);
				String includeContents = loadInternal(includeFile);
				includeStack.pop();

				switch (includeType)
				{
					case GLSL:
						if (includeContents.trim().startsWith("#version "))
						{
							// In GLSL, no preprocessor directive can precede #version, so handle included files
							// starting with a #version directive differently.
							sb.append(includeContents);
						}
						else
						{
							// In GLSL, the #line directive takes a line number and a source file index, which we map to
							// an include-filename through tracking the list of includes.
							// Source: https://www.khronos.org/opengl/wiki/Core_Language_(GLSL)#.23line_directive
							sb
								.append("#line 1 ") // Mark the first line of the included file
								.append(includeIndex)
								.append("\n")
								.append(includeContents)
								.append("#line ") // Return to the next line of the current file
								.append(lineCount + 1)
								.append(" ")
								.append(currentIndex)
								.append("\n");
						}
						break;
					case C:
						// In C, #line followed by a line number sets the line number for the current file, while
						// #line followed by a line number and a string constant filename changes the line number and
						// current filename being processed, so in our case we will only be using the latter.
						// Source: https://gcc.gnu.org/onlinedocs/cpp/Line-Control.html
						sb
							.append("#line 1 \"") // Change to line 1 in the included file
							.append(includeFile)
							.append("\"\n")
							.append(includeContents)
							.append("#line ") // Return to the next line in the parent include
							.append(lineCount + 1)
							.append(" \"")
							.append(currentFile)
							.append("\"\n");
						break;
					default:
						sb.append(includeContents);
						break;
				}
			}
			else
			{
				sb.append(line).append('\n');
			}
		}
		return sb.toString();
	}

	private String loadInternal(String filename)
	{
		for (Function<String, String> loader : resourceLoaders)
		{
			String value = loader.apply(filename);
			if (value != null)
			{
				return process(value);
			}
		}

		return "";
	}

	public String load(String filename)
	{
		includeList.clear();
		includeList.add(filename);
		includeStack.add(0);

		switch (ResourcePath.path(filename).getExtension().toLowerCase())
		{
			case "glsl":
				includeType = IncludeType.GLSL;
				break;
			case "c":
			case "h":
			case "cl":
				includeType = IncludeType.C;
				break;
			default:
				includeType = IncludeType.UNKNOWN;
				break;
		}

		return loadInternal(filename);
	}

	public Template add(Function<String, String> fn)
	{
		resourceLoaders.add(fn);
		return this;
	}

	public Template addInclude(Class<?> clazz)
	{
		return add(f ->
		{
			try (InputStream is = clazz.getResourceAsStream(f))
			{
				if (is != null)
				{
					return inputStreamToString(is);
				}
			}
			catch (IOException ex)
			{
				log.warn(null, ex);
			}
			return null;
		});
	}

	public static String inputStreamToString(InputStream in)
	{
		try
		{
			return CharStreams.toString(new InputStreamReader(in, StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
	}
}
