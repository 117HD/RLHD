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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.ResourcePath;

@Slf4j
public class Template
{
	enum IncludeType { GLSL, C, UNKNOWN }

	@FunctionalInterface
	public interface IncludeLoader
	{
		String load(String path) throws IOException;
	}

	private final List<IncludeLoader> loaders = new ArrayList<>();

	IncludeType includeType = IncludeType.UNKNOWN;
	final Stack<Integer> includeStack = new Stack<>();
	final ArrayList<String> includeList = new ArrayList<>();

	public Template copy()
	{
		var clone = new Template();
		clone.loaders.addAll(this.loaders);
		return clone;
	}

	public String process(String str) throws IOException
	{
		StringBuilder sb = new StringBuilder();
		int lineCount = 0;
		for (String line : str.split("\r?\n"))
		{
			lineCount++;
			String trimmed = line.trim();
			if (trimmed.startsWith("#include "))
			{
				int currentIndex = includeStack.peek();
				String currentFile = includeList.get(currentIndex);

				String includeFile = trimmed.substring(9);
				int includeIndex = includeList.size();
				includeList.add(includeFile);
				includeStack.push(includeIndex);
				String includeContents = loadInternal(includeFile);
				includeStack.pop();

				if (Shader.DUMP_SHADERS)
					sb.append("// Including ").append(includeFile).append('\n');

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

				if (Shader.DUMP_SHADERS)
					sb.append("// End include of ").append(includeFile).append('\n');
			}
			else if (trimmed.startsWith("#pragma once"))
			{
				int currentIndex = includeList.size() - 1;
				String currentInclude = includeList.get(currentIndex);
				if (includeList.indexOf(currentInclude) != currentIndex) {
					if (Shader.DUMP_SHADERS)
						sb.append("// Skipping already included ").append(currentInclude).append('\n');
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

	private String loadInternal(String path) throws IOException
	{
		for (var loader : loaders)
		{
			String value = loader.load(path);
			if (value != null)
			{
				return process(value);
			}
		}

		return "";
	}

	public String load(String filename) throws IOException
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

	public Template addIncludeLoader(IncludeLoader resolver)
	{
		loaders.add(resolver);
		return this;
	}

	public Template addIncludePath(Class<?> clazz)
	{
		return addIncludePath(ResourcePath.path(clazz));
	}

	public Template addIncludePath(ResourcePath includePath)
	{
		return addIncludeLoader(path -> {
			ResourcePath resolved = includePath.resolve(path);
			if (resolved.exists())
				return resolved.loadString();
			return null;
		});
	}

	public Template addInclude(String identifier, String value)
	{
		return addIncludeLoader(key -> key.equals(identifier) ? value : null);
	}

	public Template define(String identifier, String value)
	{
		return addIncludeLoader(key ->
			key.equals(identifier) ? String.format("#define %s %s", identifier, value) : null);
	}

	public Template define(String identifier, boolean value)
	{
		return addIncludeLoader(key ->
			key.equals(identifier) ? String.format("#define %s %d", identifier, value ? 1 : 0) : null);
	}

	public Template define(String identifier, int value)
	{
		return addIncludeLoader(key ->
			key.equals(identifier) ? String.format("#define %s %d", identifier, value) : null);
	}

	public Template define(String identifier, double value)
	{
		return addIncludeLoader(key ->
			key.equals(identifier) ? String.format("#define %s %f", identifier, value) : null);
	}

	public Template define(String identifier, Enum<?> enumValue)
	{
		return addIncludeLoader(key ->
			key.equals(identifier) ? String.format("#define %s %d", identifier, enumValue.ordinal()) : null);
	}

	public Template define(String identifier, Supplier<String> supplier)
	{
		return addIncludeLoader(key -> key.equals(identifier) ? supplier.get() : null);
	}
}
