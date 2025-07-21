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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.opengl.uniforms.UniformBuffer;
import rs117.hd.utils.ResourcePath;

@Slf4j
public class ShaderIncludes {
	enum Type { GLSL, C, UNKNOWN }

	@FunctionalInterface
	public interface IncludeLoader {
		String load(String path) throws IOException;
	}

	private final List<ResourcePath> includePaths = new ArrayList<>();
	private final List<IncludeLoader> includeLoaders = new ArrayList<>();

	public final Set<UniformBuffer> uniformBuffers = new HashSet<>();

	Type includeType = Type.UNKNOWN;
	final Stack<Integer> includeStack = new Stack<>();
	final ArrayList<String> includeList = new ArrayList<>();

	public ShaderIncludes copy() {
		var clone = new ShaderIncludes();
		clone.includePaths.addAll(includePaths);
		clone.includeLoaders.addAll(includeLoaders);
		clone.uniformBuffers.addAll(uniformBuffers);
		return clone;
	}

	private static int nextUnescapedMatch(String s, int offset, char targetChar) {
		int i;
		while ((i = s.indexOf(targetChar, offset)) != -1) {
			// Check if the char was escaped
			int j = i - 1;
			while (j >= 0 && s.charAt(j) == '\\')
				j--;
			if ((i - j) % 2 != 0)
				break; // Not escaped
			offset = i + 1;
		}
		return i;
	}

	private static int smallestIndex(int... indices) {
		int smallest = -1;
		for (int i : indices)
			if (smallest == -1 || i != -1 && i < smallest)
				smallest = i;
		return smallest;
	}

	private String parse(String str) throws ShaderException, IOException {
		StringBuilder sb = new StringBuilder();
		int lineCount = 0;
		for (String line : str.split("\r?\n")) {
			lineCount++;
			String trimmed = line.trim();
			if (trimmed.startsWith("#include ")) {
				int currentIndex = includeStack.peek();
				String currentFile = includeList.get(currentIndex);

				String includeExpression = trimmed.substring(9).trim();
				int startIndex = 1;
				int endIndex;
				int commentIndex = -1;
				boolean isPath = false;
				boolean isRelativePath = false;
				switch (includeExpression.charAt(0)) {
					case '"':
						isPath = isRelativePath = true;
						endIndex = nextUnescapedMatch(includeExpression, startIndex, '"');
						break;
					case '<':
						isPath = true;
						endIndex = nextUnescapedMatch(includeExpression, startIndex, '>');
						break;
					default:
						startIndex = 0;
						commentIndex = smallestIndex(
							includeExpression.indexOf("//"),
							includeExpression.indexOf("/*")
						);
						endIndex = smallestIndex(commentIndex, includeExpression.length());
						break;
				}

				String includeString = "";
				if (endIndex != -1)
					includeString = includeExpression.substring(startIndex, endIndex).trim();
				if (includeString.isEmpty()) {
					throw new ShaderException(String.format(
						"Syntax error in shader include in '%s' on line %d: %s",
						currentFile,
						lineCount,
						includeExpression
					));
				}

				int includeIndex = includeList.size();
				includeList.add(includeString);
				includeStack.push(includeIndex);

				String includeContents = null;
				if (isPath) {
					if (isRelativePath)
						includeString = ResourcePath.normalize(currentFile, "..", includeString);
					includeContents = loadFileInternal(includeString);
				} else {
					for (var loader : includeLoaders) {
						String value = loader.load(includeString);
						if (value != null) {
							includeContents = parse(value);
							break;
						}
					}
				}
				if (includeContents == null) {
					log.error("Failed to load include: {}", includeString);
					includeContents = "";
				}

				includeStack.pop();

				int nextLineOffset = 1;
				if (commentIndex != -1)
					nextLineOffset--;

				if (ShaderTemplate.DUMP_SHADERS) {
					sb.append("// Including ").append(includeString).append('\n');
					nextLineOffset--;
				}

				switch (includeType) {
					case GLSL:
						if (includeContents.trim().startsWith("#version ")) {
							// In GLSL, no preprocessor directive can precede #version, so handle included files
							// starting with a #version directive differently.
							sb.append(includeContents);
						} else {
							// In GLSL, the #line directive takes a line number and a source file index, which we map to
							// an include-filename through tracking the list of includes.
							// Source: https://www.khronos.org/opengl/wiki/Core_Language_(GLSL)#.23line_directive
							sb
								.append("#line 1 ") // Mark the first line of the included file
								.append(includeIndex)
								.append("\n")
								.append(includeContents)
								.append("#line ") // Return to the next line of the current file
								.append(lineCount + nextLineOffset)
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
							.append(includeString)
							.append("\"\n")
							.append(includeContents)
							.append("#line ") // Return to the next line in the parent include
							.append(lineCount + nextLineOffset)
							.append(" \"")
							.append(currentFile)
							.append("\"\n");
						break;
					default:
						sb.append(includeContents);
						break;
				}

				if (ShaderTemplate.DUMP_SHADERS)
					sb.append("// End include of ").append(includeString).append('\n');

				if (commentIndex != -1)
					sb.append(includeExpression.substring(commentIndex)).append('\n');
			} else if (trimmed.startsWith("#pragma once")) {
				int currentIndex = includeList.size() - 1;
				String currentInclude = includeList.get(currentIndex);
				if (includeList.indexOf(currentInclude) != currentIndex) {
					sb.append("// #pragma once - already included\n");
					break;
				} else {
					sb.append("// #pragma once - first include\n");
				}
			} else {
				sb.append(line).append('\n');
			}
		}
		return sb.toString();
	}

	private String loadFileInternal(String path) throws ShaderException, IOException {
		for (var includePath : includePaths) {
			var resourcePath = includePath.resolve(path);
			if (resourcePath.exists())
				return parse(resourcePath.loadString());
		}

		return null;
	}

	public String loadFile(String path) throws ShaderException, IOException {
		includeList.clear();
		includeList.add(path);
		includeStack.add(0);

		switch (ResourcePath.path(path).getExtension().toLowerCase()) {
			case "glsl":
				includeType = Type.GLSL;
				break;
			case "c":
			case "h":
			case "cl":
				includeType = Type.C;
				break;
			default:
				includeType = Type.UNKNOWN;
				break;
		}

		String source = loadFileInternal(path);
		if (source != null)
			return source;

		throw new IOException("Failed to load file: " + path);
	}

	public ShaderIncludes addIncludeLoader(IncludeLoader resolver) {
		includeLoaders.add(resolver);
		return this;
	}

	public ShaderIncludes addIncludePath(Class<?> clazz) {
		return addIncludePath(ResourcePath.path(clazz));
	}

	public ShaderIncludes addIncludePath(ResourcePath includePath) {
		includePaths.add(includePath.chroot());
		return this;
	}

	public ShaderIncludes addInclude(String identifier, String value) {
		return addIncludeLoader(key -> key.equals(identifier) ? value : null);
	}

	public ShaderIncludes addUniformBuffer(UniformBuffer ubo) {
		uniformBuffers.add(ubo);
		return this;
	}

	public ShaderIncludes define(String identifier, String value) {
		return addIncludeLoader(key ->
			key.equals(identifier) ? String.format("#define %s %s", identifier, value) : null);
	}

	public ShaderIncludes define(String identifier, boolean value) {
		return addIncludeLoader(key ->
			key.equals(identifier) ? String.format("#define %s %d", identifier, value ? 1 : 0) : null);
	}

	public ShaderIncludes define(String identifier, int value) {
		return addIncludeLoader(key ->
			key.equals(identifier) ? String.format("#define %s %d", identifier, value) : null);
	}

	/**
	 * Define a single-precision float shader constant. OpenCL warns when using doubles in float contexts.
	 */
	public ShaderIncludes define(String identifier, float value) {
		return addIncludeLoader(key ->
			key.equals(identifier) ? String.format("#define %s %ff", identifier, value) : null);
	}

	/**
	 * Define a double-precision float shader constant.
	 */
	public ShaderIncludes define(String identifier, double value) {
		return addIncludeLoader(key ->
			key.equals(identifier) ? String.format("#define %s %f", identifier, value) : null);
	}

	public ShaderIncludes define(String identifier, Enum<?> enumValue) {
		return addIncludeLoader(key ->
			key.equals(identifier) ? String.format("#define %s %d", identifier, enumValue.ordinal()) : null);
	}

	public ShaderIncludes define(String identifier, Supplier<String> supplier) {
		return addIncludeLoader(key -> key.equals(identifier) ? supplier.get() : null);
	}
}
