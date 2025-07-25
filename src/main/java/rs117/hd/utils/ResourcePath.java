/*
 * Copyright (c) 2025, Hooder <ahooder@protonmail.com>
 * Copyright (c) 2022, Mark <https://github.com/Mark7625>
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
package rs117.hd.utils;

import com.google.gson.Gson;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Objects;
import java.util.Stack;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.RegEx;
import javax.swing.ImageIcon;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;

@Slf4j
public class ResourcePath {
	private static final FileWatcher.UnregisterCallback NOOP = () -> {};
	private static final ResourcePath RESOURCE_PATH = Props.getFolder("rlhd.resource-path", () -> null);

	@Nullable
	public final ResourcePath root;
	@Nullable
	public final String path;

	public static ResourcePath path(Path path) {
		return path(path.toString());
	}

	public static ResourcePath path(String... parts) {
		return new ResourcePath(parts);
	}

	public static ResourcePath path(Class<?> clazz, String... parts) {
		String path = normalize(clazz.getPackage().getName().replace(".", "/"), parts);
		return path(clazz.getClassLoader(), path);
	}

	public static ResourcePath path(ClassLoader root, String... parts) {
		// Redirect class resources to the specified resource path during development
		if (RESOURCE_PATH != null)
			return RESOURCE_PATH.resolve(parts);

		return new ClassResourcePath(root).resolve(parts);
	}

	private ResourcePath(String... parts) {
		this(null, parts);
	}

	private ResourcePath(@Nonnull ResourcePath root) {
		// Encapsulate the current root and path into a new root ResourcePath.
		// Subsequent path resolutions will not include the encapsulated path.
		this.root = root;
		this.path = null;
	}

	private ResourcePath(@Nullable ResourcePath root, String... parts) {
		this.root = root;
		this.path = normalize(parts);
	}

	public ResourcePath chroot() {
		return new ResourcePath(this);
	}

	public ResourcePath resolve(String... parts) {
		return new ResourcePath(root, normalize(path, parts));
	}

	@SuppressWarnings("ResultOfMethodCallIgnored")
	public ResourcePath mkdirs() {
		var path = this;
		// Assume paths with an extension are file paths, not directories
		if (!getExtension().isEmpty())
			path = path.resolve("..");
		path.toFile().mkdirs();
		return this;
	}

	public boolean exists() {
		if (root != null)
			return root.resolve(path).exists();
		return toFile().exists();
	}

	public String getFilename() {
		if (path == null)
			return "";
		int i = path.lastIndexOf("/");
		if (i != -1)
			return path.substring(i + 1);
		return path;
	}

	public String getExtension() {
		return getExtension(0);
	}

	public String getExtension(int nthLast) {
		String filename = getFilename();
		String extension = "";
		while (nthLast-- >= 0) {
			int i = filename.lastIndexOf('.');
			if (i == -1)
				return nthLast >= 0 ? "" : filename;
			extension = filename.substring(i + 1);
			filename = filename.substring(0, i);
		}
		return extension;
	}

	public ResourcePath setExtension(String extension) {
		if (path == null)
			throw new IllegalStateException("Cannot set extension for root path: " + this);

		String path = this.path;
		int i = path.lastIndexOf('.');
		if (i != -1)
			path = path.substring(0, i);

		if (extension != null && !extension.isEmpty())
			path += '.' + extension;

		return new ResourcePath(root, path);
	}

	public boolean matches(@RegEx String posixPathRegex) {
		Pattern p = Pattern.compile(posixPathRegex);
		Matcher m = p.matcher(toPosixPath());
		return m.find();
	}

	@Override
	public String toString() {
		String path = toPosixPath();
		return path.isEmpty() ? "." : path;
	}

	@Override
	public boolean equals(Object obj) {
		// Used for file watcher event deduplication
		return obj instanceof ResourcePath &&
			   Objects.equals(root, ((ResourcePath) obj).root) &&
			   Objects.equals(path, ((ResourcePath) obj).path);
	}

	public String toPosixPath() {
		if (root != null)
			return normalize(root.toPosixPath(), new String[] { path });
		return path;
	}

	public Path toPath() {
		if (root == null) {
			assert path != null;
			return Paths.get(path);
		}

		Path basePath = root.toPath();
		if (path == null)
			return basePath;

		return basePath.resolve(stripLeadingSlash(path));
	}

	public File toFile() {
		if (isFileSystemResource())
			return toPath().toFile();
		throw new IllegalStateException("Not a file: " + this);
	}

	public BufferedReader toReader() throws IOException {
		return new BufferedReader(new InputStreamReader(toInputStream(), StandardCharsets.UTF_8));
	}

	public InputStream toInputStream() throws IOException {
		if (root != null)
			return root.resolve(path).toInputStream();

		try {
			return Files.newInputStream(toPath());
		} catch (IOException ex) {
			throw new IOException("Unable to load resource: " + this, ex);
		}
	}

	public FileOutputStream toOutputStream() throws FileNotFoundException {
		return new FileOutputStream(toFile());
	}

	public boolean isFileSystemResource() {
		if (root != null)
			return root.isFileSystemResource();
		return true;
	}

	/**
	 * Run the callback once at the start & every time the resource (or sub resource) changes.
	 *
	 * @param changeHandler Callback to call once at the start (bool = true) and every time the resource changes (bool = false)
	 * @return A runnable that can be called to unregister the watch callback
	 */
	public FileWatcher.UnregisterCallback watch(BiConsumer<ResourcePath, Boolean> changeHandler) {
		var path = this;

		// Load once up front
		changeHandler.accept(path, true);

		// Watch for changes if the resource is on the file system, which will exclude paths pointing into the JAR.
		// By default, unless paths are overridden by VM arguments, all of 117 HD's paths point into the JAR.
		if (path.isFileSystemResource())
			return FileWatcher.watchPath(path, p -> changeHandler.accept(p, false));

		return NOOP;
	}

	public FileWatcher.UnregisterCallback watch(Consumer<ResourcePath> changeHandler) {
		return watch((path, first) -> changeHandler.accept(path));
	}

	/**
	 * Run the callback once at the start & every time the resource (or sub resource) changes.
	 *
	 * @param changeHandler Callback to call once at the start and every time the resource changes
	 * @return A runnable that can be called to unregister the watch callback
	 */
	public FileWatcher.UnregisterCallback watch(@RegEx String filter, BiConsumer<ResourcePath, Boolean> changeHandler) {
		return watch((path, first) -> {
			if (path.matches(filter))
				changeHandler.accept(path, first);
		});
	}

	public FileWatcher.UnregisterCallback watch(@RegEx String filter, Consumer<ResourcePath> changeHandler) {
		return watch(filter, (path, first) -> changeHandler.accept(path));
	}

	public String loadString() throws IOException {
		try (BufferedReader reader = toReader()) {
			return reader.lines().collect(Collectors.joining(System.lineSeparator()));
		}
	}

	public <T> T loadJson(Gson gson, Class<T> type) throws IOException {
		try (BufferedReader reader = toReader()) {
			return gson.fromJson(reader, type);
		}
	}

	public <T> T loadJson(Gson gson, Type type) throws IOException {
		try (BufferedReader reader = toReader()) {
			return gson.fromJson(reader, type);
		}
	}

	public BufferedImage loadImage() throws IOException {
		try (InputStream is = toInputStream()) {
			byte[] bytes = is.readAllBytes();
			var icon = new ImageIcon(Toolkit.getDefaultToolkit().createImage(bytes));
			var bufferedImage = new BufferedImage(
				icon.getIconWidth(),
				icon.getIconHeight(),
				BufferedImage.TYPE_INT_ARGB
			);
			var g = bufferedImage.createGraphics();
			icon.paintIcon(null, g, 0, 0);
			g.dispose();
			return bufferedImage;
		}
	}

	/**
	 * Reads the full InputStream into a garbage-collected ByteBuffer allocated with BufferUtils.
	 *
	 * @return a ByteBuffer
	 * @throws IOException if the InputStream cannot be read
	 */
	public ByteBuffer loadByteBuffer() throws IOException {
		return readInputStream(toInputStream(), BufferUtils::createByteBuffer, null);
	}

	/**
	 * Reads the full InputStream into a ByteBuffer allocated with malloc, which must be explicitly freed.
	 *
	 * @return a ByteBuffer
	 * @throws IOException if the InputStream cannot be read
	 */
	public ByteBuffer loadByteBufferMalloc() throws IOException {
		return readInputStream(toInputStream(), MemoryUtil::memAlloc, MemoryUtil::memRealloc);
	}

	public ResourcePath writeByteBuffer(ByteBuffer buffer) throws IOException {
		try (var os = toOutputStream(); var channel = os.getChannel()) {
			int bytesToWrite = buffer.remaining();
			int bytesWritten = channel.write(buffer);
			if (bytesWritten < bytesToWrite) {
				throw new IOException(String.format(
					"Only %d out of %d bytes were successfully written to %s",
					bytesWritten, bytesToWrite, this
				));
			}
		}
		return this;
	}

	public ResourcePath writeString(String string) throws IOException {
		try (OutputStream os = toOutputStream()) {
			os.write(string.getBytes(StandardCharsets.UTF_8));
		}
		return this;
	}

	/**
	 * Reads the full InputStream into a garbage-collected ByteBuffer allocated with BufferUtils.
	 *
	 * @param is the InputStream
	 * @return a ByteBuffer
	 * @throws IOException if the InputStream cannot be read
	 */
	private static ByteBuffer readInputStream(
		InputStream is,
		Function<Integer, ByteBuffer> alloc,
		@Nullable BiFunction<ByteBuffer, Integer, ByteBuffer> realloc
	) throws IOException {
		if (realloc == null) {
			realloc = (ByteBuffer oldBuffer, Integer newSize) -> {
				ByteBuffer newBuffer = alloc.apply(newSize);
				newBuffer.put(oldBuffer);
				return newBuffer;
			};
		}

		try (ReadableByteChannel channel = Channels.newChannel(is)) {
			// Read all currently buffered data into a ByteBuffer
			ByteBuffer buffer = alloc.apply(is.available());
			channel.read(buffer);

			// If there's more data available, double the buffer size and round up to the nearest power of 2
			if (is.available() > buffer.remaining()) {
				int newSize = (buffer.position() + is.available()) * 2;
				int nearestPow2 = 2 << (31 - Integer.numberOfLeadingZeros(newSize - 1));
				buffer = realloc.apply(buffer, nearestPow2);
			}

			// Continue reading all bytes, doubling the buffer each time it runs out of space
			while (is.available() > 0)
				if (buffer.remaining() == channel.read(buffer))
					buffer = realloc.apply(buffer, buffer.capacity() * 2);

			channel.close();
			buffer.flip();
			return buffer;
		}
	}

	public static String normalize(String... parts) {
        return normalize(null, parts);
    }

	public static String normalize(@Nullable String workingDirectory, String[] parts) {
        Stack<String> resolvedParts = new Stack<>();
		if (workingDirectory != null && !workingDirectory.isEmpty() && !workingDirectory.equals("."))
			resolvedParts.addAll(Arrays.asList(normalizeSlashes(workingDirectory).split("/")));

		if (parts.length > 0)
			parts[0] = resolveTilde(parts[0]);

		for (String part : parts) {
			if (part == null || part.isEmpty() || part.equals("."))
				continue;

			part = normalizeSlashes(part);

			if (isAbsolute(part))
				resolvedParts.clear();

			for (String normalizedPart : part.split("/")) {
				if (normalizedPart.equals("..") &&
					!resolvedParts.isEmpty() &&
					!resolvedParts.peek().equals("..")
				) {
					resolvedParts.pop();
				} else {
					resolvedParts.push(normalizedPart);
				}
			}
		}

		return String.join("/", resolvedParts);
	}

	private static String normalizeSlashes(String path) {
		if (Platform.get() == Platform.WINDOWS)
			return path.replace("\\", "/");
		return path;
	}

	private static String resolveTilde(String path) {
		// Note: We only support ~ and ~user tilde expansion
		if (path == null || !path.startsWith("~"))
			return path;

		int slashIndex = path.indexOf('/');
		String specifiedUser = path.substring(1, slashIndex == -1 ? path.length() : slashIndex);
		String userHome = System.getProperty("user.home");
		if (userHome == null)
			throw new RuntimeException("Unable to resolve tilde path: " + path);

		Path home = Paths.get(userHome);

		// Check if the home path of a different user was specified
		if (!specifiedUser.isEmpty()) {
			// Assume the username matches the home folder name,
			// and that it's located next to the current user's home directory
			home = home.resolve("../" + specifiedUser);
		}

		if (slashIndex == -1)
			return home.toString();
		return home.resolve(path.substring(slashIndex + 1)).toString();
	}

	private static String stripLeadingSlash(String path) {
		return path.startsWith("/") ? path.substring(1) : path;
	}

	/**
	 * Expects forward slashes as path delimiter, but accepts Windows-style drive letter prefixes.
	 */
	private static boolean isAbsolute(String path) {
		if (Platform.get() == Platform.WINDOWS)
			path = path.replaceFirst("^\\w:", "");
		return path.startsWith("/");
	}

	private static class ClassResourcePath extends ResourcePath {
		public final ClassLoader classLoader;

		private ClassResourcePath(@Nonnull ClassLoader classLoader, String... parts) {
			super(parts);
			this.classLoader = classLoader;
		}

		@Override
		public ResourcePath resolve(String... parts) {
			return new ClassResourcePath(classLoader, normalize(path, parts));
		}

		private URL toResource() {
			assert path != null;
			return classLoader.getResource(stripLeadingSlash(path));
		}

		@Override
		public boolean exists() {
			return toResource() != null;
		}

		@Override
		public InputStream toInputStream() throws IOException {
			var url = toResource();
			if (url == null)
				throw new IOException("Unable to load resource: " + this);
			return url.openStream();
		}

		@Override
		public boolean isFileSystemResource() {
			return false;
		}
	}
}
