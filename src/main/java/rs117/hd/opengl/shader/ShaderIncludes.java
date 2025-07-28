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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.opengl.uniforms.UniformBuffer;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;

@Slf4j
public class ShaderIncludes {
	public static final ResourcePath SHADER_DUMP_PATH = Props.getFolder("rlhd.dump-shaders", () -> null);

	enum Type { GLSL, C, UNKNOWN }

	private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_]\\w*");

	@FunctionalInterface
	public interface IncludeProcessor {
		String process(String expression) throws IOException;
	}

	private final List<IncludeProcessor> includeProcessors = new ArrayList<>();
	private final List<ResourcePath> includePaths = new ArrayList<>();
	private final Map<String, Supplier<String>> includeMap = new HashMap<>();

	public final Set<UniformBuffer<?>> uniformBuffers = new HashSet<>();

	Type includeType = Type.UNKNOWN;
	final Stack<Integer> includeStack = new Stack<>();
	final List<String> includeList = new ArrayList<>();

	public ShaderIncludes copy() {
		var clone = new ShaderIncludes();
		clone.includeProcessors.addAll(includeProcessors);
		clone.includePaths.addAll(includePaths);
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

	private static boolean isCommentOrEmpty(String expression) {
		expression = expression.stripLeading();
		if (expression.isEmpty())
			return true;

		if (expression.length() < 2 || expression.charAt(0) != '/')
			return false;

		switch (expression.charAt(1)) {
			case '/':
				return true;
			case '*':
				int i = expression.indexOf("*/");
				return i == -1 || isCommentOrEmpty(expression.substring(i + 2));
		}

		return false;
	}

	private ShaderException syntaxError(int lineNumber, String error) {
		String currentFile = includeList.get(includeStack.peek());
		return new ShaderException(String.format(
			"Syntax error in shader include in '%s' on line %d: %s", currentFile, lineNumber, error));
	}

	private ShaderException includeError(int lineNumber, String include) {
		String currentFile = includeList.get(includeStack.peek());
		return new ShaderException(String.format(
			"Failed to load shader include in '%s' on line %d: #include %s", currentFile, lineNumber, include));
	}

	private String parse(String source) throws ShaderException, IOException {
		StringBuilder sb = new StringBuilder();
		int lineNumber = 0;
		for (String line : source.split("\r?\n")) {
			lineNumber++;
			String trimmed = line.stripLeading();
			if (trimmed.startsWith("#include ")) {
				int currentIncludeIndex = includeList.size();
				int parentIncludeIndex = includeStack.peek();
				String currentFile = includeList.get(parentIncludeIndex);

				String expression = trimmed.substring(9).stripLeading();
				if (expression.isEmpty())
					throw syntaxError(lineNumber, "Empty include");

				String includeContents = null;
				char endChar = '"';
				int commentIndex = -1;
				switch (expression.charAt(0)) {
					case '<':
						endChar = '>';
					case '"':
						// Process path includes
						int endIndex = nextUnescapedMatch(expression, 1, endChar);
						if (endIndex == -1)
							throw syntaxError(lineNumber, "Expected closing '" + endChar + "' in include");

						commentIndex = endIndex + 1;
						if (!isCommentOrEmpty(expression.substring(commentIndex)))
							throw syntaxError(
								lineNumber,
								"Unexpected characters after closing '" + endChar + "' in include. Only comments are allowed."
							);

						// Valid include
						String include = expression.substring(1, endIndex);
						if (endChar == '"')
							include = ResourcePath.normalize(currentFile, "..", include);

						includeContents = loadFileInternal(include);
						if (includeContents == null)
							throw includeError(lineNumber, expression.substring(0, endIndex + 1));
						break;
					default:
						// Process constant identifier includes
						var m = IDENTIFIER_PATTERN.matcher(expression);
						if (m.find() && isCommentOrEmpty(expression.substring(m.end()))) {
							commentIndex = m.end();
							var supplier = includeMap.get(m.group());
							if (supplier == null)
								throw includeError(lineNumber, m.group());
							includeContents = supplier.get();
							break;
						}

						// Fall back to custom include processors
						for (var processor : includeProcessors)
							if ((includeContents = processor.process(expression)) != null)
								break;
				}

				if (includeContents == null) {
					log.error("Include not found. Did you forget quotes?", includeError(lineNumber, expression));
					includeContents = String.format("// Not found: %s", expression);
				}

				if (SHADER_DUMP_PATH != null) {
					sb.append("// Include: ").append(expression).append('\n');
					includeContents += String.format("\n// End include: %s", expression);
				}

				switch (includeType) {
					case GLSL:
						if (includeContents.stripLeading().startsWith("#version ")) {
							// In GLSL, no preprocessor directive can precede #version, so handle included files
							// starting with a #version directive differently.
							sb.append(includeContents);
						} else {
							// In GLSL, the #line directive takes a line number and a source file index, which we map to
							// an include-filename through tracking the list of includes.
							// Source: https://www.khronos.org/opengl/wiki/Core_Language_(GLSL)#.23line_directive
							sb
								.append("#line 1 ") // Mark the first line of the included file
								.append(currentIncludeIndex)
								.append('\n')
								.append(includeContents)
								.append('\n')
								.append("#line ") // Return to the next line of the current file
								.append(lineNumber + 1)
								.append(" ")
								.append(parentIncludeIndex)
								.append('\n');
						}
						break;
					case C:
						// In C, #line followed by a line number sets the line number for the current file, while
						// #line followed by a line number and a string constant filename changes the line number and
						// current filename being processed, so in our case we will only be using the latter.
						// Source: https://gcc.gnu.org/onlinedocs/cpp/Line-Control.html
						sb
							.append("#line 1 \"") // Change to line 1 in the included file
							.append(expression.replaceAll("\"", "\\\\\""))
							.append("\"\n")
							.append(includeContents)
							.append('\n')
							.append("#line ") // Return to the next line in the parent include
							.append(lineNumber + 1)
							.append(" \"")
							.append(currentFile)
							.append("\"\n");
						break;
					default:
						sb.append(includeContents).append('\n');
						break;
				}

				String comment = commentIndex == -1 ? "" : expression.substring(commentIndex).stripLeading();
				if (!comment.isEmpty())
					sb.append(comment).append('\n');
			} else if (trimmed.startsWith("#pragma once")) {
				int currentIndex = includeList.size() - 1;
				String currentInclude = includeList.get(currentIndex);
				sb.append("// #pragma once: ");
				if (includeList.indexOf(currentInclude) != currentIndex) {
					sb.append("already included\n");
					break;
				} else {
					sb.append("first include\n");
				}
			} else {
				sb.append(line).append('\n');
			}
		}
		return sb.toString();
	}

	private String loadFileInternal(String path) throws ShaderException, IOException {
		includeStack.push(includeList.size());
		includeList.add(path);

		String source = null;
		for (var includePath : includePaths) {
			var resourcePath = includePath.resolve(path);
			if (resourcePath.exists()) {
				source = parse(resourcePath.loadString());
				if (SHADER_DUMP_PATH != null)
					SHADER_DUMP_PATH.resolve(resourcePath.path).mkdirs().writeString(source);
				break;
			}
		}

		includeStack.pop();
		return source;
	}

	public String loadFile(String path) throws ShaderException, IOException {
		includeList.clear();

		switch (path(path).getExtension().toLowerCase()) {
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

	public ShaderIncludes addIncludePath(Class<?> clazz) {
		return addIncludePath(path(clazz));
	}

	public ShaderIncludes addIncludePath(ResourcePath includePath) {
		includePaths.add(includePath.chroot());
		return this;
	}

	public ShaderIncludes addInclude(String identifier, Supplier<String> supplier) {
		includeMap.put(identifier, supplier);
		return this;
	}

	public ShaderIncludes addInclude(String identifier, String value) {
		return addInclude(identifier, () -> value);
	}

	public ShaderIncludes addUniformBuffer(UniformBuffer<?> ubo) {
		uniformBuffers.add(ubo);
		return this;
	}

	public ShaderIncludes define(String identifier, String value) {
		return addInclude(identifier, String.format("#define %s %s", identifier, value));
	}

	public ShaderIncludes define(String identifier, boolean value) {
		return define(identifier, String.format("%d", value ? 1 : 0));
	}

	public ShaderIncludes define(String identifier, int value) {
		return define(identifier, String.format("%d", value));
	}

	/**
	 * Define a single-precision float shader constant. OpenCL warns when using doubles in float contexts.
	 */
	public ShaderIncludes define(String identifier, float value) {
		return define(identifier, String.format("%ff", value));
	}

	/**
	 * Define a double-precision float shader constant.
	 */
	public ShaderIncludes define(String identifier, double value) {
		return define(identifier, String.format("%f", value));
	}

	public ShaderIncludes define(String identifier, Enum<?> enumValue) {
		return define(identifier, String.format("%d", enumValue.ordinal()));
	}
}
