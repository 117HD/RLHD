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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.function.Supplier;
import lombok.Getter;
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

	private final List<IncludeLoader> loaders = new ArrayList<>();
	private ResourcePath rootPath;

	@Getter
	private final List<UniformBuffer> uniformBuffers = new ArrayList<>();

	Type includeType = Type.UNKNOWN;
	final Stack<Integer> includeStack = new Stack<>();
	final ArrayList<String> includeList = new ArrayList<>();

	public ShaderIncludes copy() {
		var clone = new ShaderIncludes();
		clone.loaders.addAll(this.loaders);
		clone.uniformBuffers.addAll(this.uniformBuffers);
		clone.rootPath = this.rootPath;
		return clone;
	}

	public String process(String str) throws IOException {
		StringBuilder sb = new StringBuilder();
		int lineCount = 0;
		for (String line : str.split("\r?\n")) {
			lineCount++;
			String trimmed = line.trim();
			if (trimmed.startsWith("#include ")) {
				int currentIndex = includeStack.peek();
				String currentFile = includeList.get(currentIndex);

				String includeFile = trimmed.substring(9);
				int includeIndex = includeList.size();
				includeList.add(includeFile);
				includeStack.push(includeIndex);
				String includeContents = loadInternal(includeFile);
				includeStack.pop();

				int nextLineOffset = 1;
				if (ShaderTemplate.DUMP_SHADERS) {
					sb.append("// Including ").append(includeFile).append('\n');
					nextLineOffset = 0;
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
							.append(includeFile)
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
					sb.append("// End include of ").append(includeFile).append('\n');
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

	private String loadInternal(String path) throws IOException {
		for (var loader : loaders) {
			String value = loader.load(path);
			if (value != null) {
				return process(value);
			}
		}

		return "";
	}

	public String load(String filename) throws IOException {
		includeList.clear();
		includeList.add(filename);
		includeStack.add(0);

		switch (ResourcePath.path(filename).getExtension().toLowerCase()) {
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

		ResourcePath resolved = rootPath.resolve(filename);
		if (resolved != null) {
			return process(resolved.loadString());
		}
		return null;
	}

	public ShaderIncludes addIncludeLoader(IncludeLoader resolver) {
		loaders.add(resolver);
		return this;
	}

	public ShaderIncludes addIncludePath(Class<?> clazz) {
		return addIncludePath(ResourcePath.path(clazz), false);
	}

	public ShaderIncludes addIncludePath(ResourcePath includePath, boolean isRoot) {
		if (isRoot) {
			rootPath = includePath;
		}

		return addIncludeLoader(path -> {
			int quoteStartIDx = path.indexOf("\"");
			int quoteEndIDx = path.indexOf("\"", quoteStartIDx + 1);
			if (quoteStartIDx >= 0 && quoteEndIDx > quoteStartIDx) {
				String relativePath = path.substring(quoteStartIDx + 1, quoteEndIDx);
				String relativeFolder = "";
				if (includeStack.size() >= 2) {
					String processingFilePath = includeList.get(includeStack.get(includeStack.size() - 2));
					Path parentFolder = Paths.get(processingFilePath).getParent();
					if (parentFolder != null) {
						relativeFolder = parentFolder.toString();
					}
				}

				String fullPath = (relativeFolder.isEmpty() ? "" : relativeFolder + "/") + relativePath;
				ResourcePath resolved = includePath.resolve(fullPath);
				if (resolved.exists())
					return resolved.loadString();
				return null;
			}

			int bracketStartIDx = path.indexOf("<");
			int bracketEndIDx = path.indexOf(">", bracketStartIDx + 1);
			if (bracketStartIDx >= 0 && bracketEndIDx > bracketStartIDx) {
				String absolutePath = path.substring(bracketStartIDx + 1, bracketEndIDx);
				ResourcePath resolved = includePath.resolve(absolutePath);
				if (resolved.exists())
					return resolved.loadString();
			}
			return null;
		});
	}

	public ShaderIncludes addInclude(String identifier, String value) {
		return addIncludeLoader(key -> key.equals(identifier) ? value : null);
	}

	public ShaderIncludes addUniformBuffer(UniformBuffer ubo) {
		if (!uniformBuffers.contains(ubo)) {
			uniformBuffers.add(ubo);
		}
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
