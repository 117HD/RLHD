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
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Template
{
	private final List<Function<String, String>> resourceLoaders = new ArrayList<>();

	private int includeCounter = 0;

	public String process(String str, String filename)
	{
		StringBuilder sb = new StringBuilder();
		int lineCount = 0;
		for (String line : str.split("\r?\n"))
		{
			lineCount++;
			String trimmed = line.trim();
			if (trimmed.startsWith("#include "))
			{
				String resource = trimmed.substring(9);
				includeCounter++;
				String contents = load(resource);
				if (contents.trim().startsWith("#version "))
				{
					sb.append(contents);
				}
				else
				{
					sb
						.append("#line 1\n")
						.append(contents)
						.append("#line ")
						.append(lineCount + 1)
						.append(" ");

					if (filename.endsWith(".cl")) {
						sb
							.append("\"")
							.append(resource)
							.append("\"");
					} else {
						sb
							.append(includeCounter - 1);
					}

					sb.append("\n");

				}
				includeCounter--;
			}
			else
			{
				sb.append(line).append('\n');
			}
		}
		return sb.toString();
	}

	public String load(String filename)
	{
		for (Function<String, String> loader : resourceLoaders)
		{
			String value = loader.apply(filename);
			if (value != null)
			{
				return process(value, filename);
			}
		}

		return "";
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
