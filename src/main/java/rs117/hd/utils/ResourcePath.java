/*
 * Copyright (c) 2022, Hooder <ahooder@protonmail.com>
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

import com.google.common.io.CharStreams;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;
import rs117.hd.opengl.shader.Template;

import javax.annotation.Nullable;
import javax.annotation.RegEx;
import java.io.*;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Stack;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ResourcePath {
    public static final Gson gson = new GsonBuilder().setLenient().create();
    private static final Runnable NOOP = () -> {};

    public static ResourcePath path(String... parts) {
        return path((Object) null, parts);
    }

    public static ResourcePath path(Class<?> root, String... parts) {
        return path((Object) root, parts);
    }

    public static ResourcePath path(ClassLoader root, String... parts)  {
        return path((Object) root, parts);
    }

    public static ResourcePath path(Object object, String... parts) {
        return new ResourcePath(object, parts);
    }

    public final Object root;
    public final String path;

    private ResourcePath(Path path) {
        this(path.toString());
    }

    private ResourcePath(String... parts) {
        this((Object) null, parts);
    }

    private ResourcePath(Class<?> root, String... parts) {
        this((Object) root, parts);
    }

    private ResourcePath(ClassLoader root, String... parts) {
        this((Object) root, parts);
    }

    private ResourcePath(Object object, String... parts) {
        Object root = null;
        String path = normalize(parts);
        if (object instanceof ResourcePath) {
            root = object;
        } else if (object instanceof Class<?> || object instanceof ClassLoader) {
            root = object;
        } else if (object instanceof String) {
            path = object + "/" + path;
        } else if (object != null) {
            root = object.getClass();
        }
        this.root = root;
        this.path = path;
    }

    public ResourcePath resolve(String... parts) {
        return new ResourcePath(root, path, normalize(parts));
    }

    public ResourcePath prepend(String... parts) {
        return new ResourcePath(normalize(parts)).resolve(path);
    }

    public ResourcePath replaceRoot(Class<?> newRoot) {
        return new ResourcePath(newRoot, path);
    }

    public ResourcePath replaceRoot(ClassLoader newRoot) {
        return new ResourcePath(newRoot, path);
    }

    public String getFilename() {
        String[] parts = path.split("/");
        if (parts.length == 0)
            return "";
        return parts[parts.length - 1];
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

    public ResourcePath replaceExtension(String newExtension) {
        String filename = getFilename();
        int i = filename.lastIndexOf('.');
        if (i != -1)
            filename = filename.substring(0, i);
        return resolve("..", filename + "." + newExtension);
    }

    public boolean matches(@RegEx String posixPathRegex) {
        return toPosixPath().matches(posixPathRegex);
    }

    public String toPosixPath() {
        if (root instanceof ResourcePath) {
            return normalize(((ResourcePath) root).toPosixPath(),
                    path.startsWith("/") ? path.substring(1) : path);
        } else if (root instanceof Class) {
            return normalize(((Class<?>) root).getPackage().getName().replace(".", "/"), path);
        }
        return path;
    }

    public Path toPath() {
        return Paths.get(toPosixPath());
    }

    @Override
    public String toString() {
        return toPosixPath();
    }

    public String loadString() throws IOException {
        try (BufferedReader reader = this.toReader()) {
            return reader.lines().collect(Collectors.joining(System.lineSeparator()));
        }
    }

    public <T> T loadJson(Class<T> type) throws IOException {
        try (BufferedReader reader = this.toReader()) {
            return gson.fromJson(reader, type);
        }
    }

    public boolean isJarResource() {
        if (root instanceof ResourcePath)
            return ((ResourcePath) root).isJarResource();
        return root instanceof Class || root instanceof ClassLoader;
    }

    /**
     * Run `pathConsumer` once, and every time the resource (or sub-resource) changes.
     * @param pathConsumer Callback to call once at the start and every time the resource changes
     * @return A runnable that can be called to unregister the watch callback
     */
    public Runnable watch(Consumer<ResourcePath> pathConsumer) {
        pathConsumer.accept(this);
        if (isJarResource())
            return NOOP;
        return FileWatcher.watchPath(this, pathConsumer);
    }

    private BufferedReader toReader() {
        return new BufferedReader(new InputStreamReader(this.toInputStream(), StandardCharsets.UTF_8));
    }

    private InputStream toInputStream() {
        try {
            InputStream is;
            if (root == null) {
                is = new FileInputStream(path);
            } else if (root instanceof ResourcePath) {
                String path = this.path;
                if (path.startsWith("/"))
                    path = path.substring(1);
                ResourcePath root = (ResourcePath) this.root;
                is = root.resolve(path).toInputStream();
            } else if (root instanceof Class) {
                is = ((Class<?>) root).getResourceAsStream(path);
            } else if (root instanceof ClassLoader) {
                is = ((ClassLoader) root).getResourceAsStream(path);
            } else {
                throw new IllegalStateException("Unknown resource path type: " + root);
            }
            if (is == null)
                throw new FileNotFoundException("No resource found for path: " + this);
            return is;
        } catch (FileNotFoundException ex) {
            throw new RuntimeException("Missing resource: " + this, ex);
        }
    }

    /**
     * Reads the full InputStream into a garbage-collected ByteBuffer allocated with BufferUtils.
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
            return buffer.flip();
        }
    }

    /**
     * Reads the full InputStream into a garbage-collected ByteBuffer allocated with BufferUtils.
     * @param is the InputStream
     * @return a ByteBuffer
     * @throws IOException if the InputStream cannot be read
     */
    private static ByteBuffer readInputStream(InputStream is) throws IOException {
        return readInputStream(is, BufferUtils::createByteBuffer, null);
    }

    /**
     * Reads the full InputStream into a ByteBuffer allocated with the provided MemoryStack.
     * @param is the InputStream
     * @return a ByteBuffer
     * @throws IOException if the InputStream cannot be read
     */
    private static ByteBuffer readInputStream(MemoryStack stack, InputStream is) throws IOException {
        return readInputStream(is, stack::calloc, null);
    }

    /**
     * Reads the full InputStream into a ByteBuffer allocated with malloc, which must be explicitly freed.
     * @param is the InputStream
     * @return a ByteBuffer
     * @throws IOException if the InputStream cannot be read
     */
    private static ByteBuffer readInputStreamMalloc(InputStream is) throws IOException {
        return readInputStream(is, MemoryUtil::memAlloc, MemoryUtil::memRealloc);
    }

    private static String normalize(String... parts) {
        if (Platform.get() == Platform.WINDOWS)
            for (int i = 0; i < parts.length; i++)
                parts[i] = parts[i].replace('\\', '/');

        Stack<String> resolvedParts = new Stack<>();
        for (String part : parts) {
            if (isAbsolute(part))
                resolvedParts.clear();

            for (String normalizedPart : part.split("/")) {
                if (normalizedPart.equals("..") &&
                        resolvedParts.size() > 0 &&
                        !resolvedParts.peek().equals("..")) {
                    resolvedParts.pop();
                } else {
                    resolvedParts.push(normalizedPart);
                }
            }
        }

        return String.join("/", resolvedParts);
    }

    /**
     * Expects forward slashes as path delimiter, but accepts Windows-style drive letter prefixes.
     */
    private static boolean isAbsolute(String path) {
        if (Platform.get() == Platform.WINDOWS)
            path = path.replaceFirst("^\\w:", "");
        return path.startsWith("/");
    }
}
