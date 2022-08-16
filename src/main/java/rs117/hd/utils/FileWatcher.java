/*
 * Copyright (c) 2022, Hooder <ahooder@protonmail.com>
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

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static java.nio.file.StandardWatchEventKinds.*;
import static rs117.hd.utils.ResourcePath.path;

@Slf4j
public class FileWatcher
{
	private static final WatchEvent.Kind<?>[] eventKinds = { ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY };

	private static Thread watcherThread;
	private static WatchService watchService;
	private static final HashMap<WatchKey, Path> watchKeys = new HashMap<>();
	private static final ListMultimap<String, Consumer<ResourcePath>> changeHandlers = ArrayListMultimap.create();

	private static void initialize()
	{
		try {
			watchService = FileSystems.getDefault().newWatchService();
			watcherThread = new Thread(() -> {
				try {
					WatchKey watchKey;
					while ((watchKey = watchService.take()) != null) {
						Path dir = watchKeys.get(watchKey);
						if (dir == null) {
							log.error("Unknown WatchKey: " + watchKey);
							continue;
						}
						for (WatchEvent<?> event : watchKey.pollEvents()) {
							if (event.kind() == OVERFLOW)
								continue;

							Path path = dir.resolve((Path) event.context());
							if (path.toString().endsWith("~")) // Ignore temp files
								continue;

							log.trace("WatchEvent of kind {} for path {}", event.kind(), path);

							// Manually register new sub folders if not watching a file tree
							if (event.kind() == ENTRY_CREATE && path.toFile().isDirectory())
								watchRecursively(path);

							String key = path.toRealPath().toString();

							ResourcePath resourcePath = path(key);
							if (path.toFile().isDirectory())
								key += File.separator;

							for (Map.Entry<String, Consumer<ResourcePath>> entry : changeHandlers.entries()) {
								if (key.startsWith(entry.getKey())) {
									entry.getValue().accept(resourcePath);
								}
							}
						}
						watchKey.reset();
					}
				}
				catch (ClosedWatchServiceException ignored) {}
				catch (InterruptedException ex) {
					throw new RuntimeException("Watcher thread interrupted", ex);
				} catch (IOException ex) {
					log.error("Error while handling file change event:", ex);
				}
			},  FileWatcher.class.getSimpleName() + " Thread");

			watcherThread.setDaemon(true);
			watcherThread.start();
		} catch (IOException ex) {
			log.error("Failed to start file watcher:", ex);
		}
	}

	public static void destroy() {
		if (watchService == null)
			return;

		try {
			changeHandlers.clear();
			watchKeys.clear();
			watchService.close();
			watcherThread.join();
		} catch (IOException | InterruptedException ex) {
			throw new RuntimeException("Error while closing " + FileWatcher.class.getSimpleName(), ex);
		}
	}

	public static Runnable watchPath(@NonNull ResourcePath resourcePath, @NonNull Consumer<ResourcePath> changeHandler)
	{
		if (resourcePath.isJarResource())
			throw new IllegalStateException("Cannot watch paths within jars");

		if (watchService == null)
			initialize();

		try {
			Path path = resourcePath.toPath();
			String key = path.toRealPath().toString();

			if (path.toFile().isDirectory()) {
				key += File.separator;
				watchRecursively(path);
			} else {
				watchFile(path);
			}
			String finalKey = key;

			changeHandlers.put(finalKey, changeHandler);
			return () -> changeHandlers.remove(finalKey, changeHandler);
		} catch (IOException ex) {
			throw new RuntimeException("Unable to create watch service for shader hot-swap compilation");
		}
	}

	private static void watchFile(Path path) {
		Path dir = path.getParent();
		try {
			watchKeys.put(dir.register(watchService, eventKinds), dir);
			log.trace("Watching {}", dir.toRealPath());
		} catch (IOException ex) {
			throw new RuntimeException("Failed to register file watcher for path: " + path, ex);
		}
	}

	private static void watchRecursively(Path path) {
		try {
			Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
					WatchKey key = dir.register(watchService, eventKinds);
					log.trace("Watching {}", dir.toRealPath());
					watchKeys.put(key, dir);
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException ex) {
			throw new RuntimeException("Failed to register recursive file watcher for path: " + path, ex);
		}
	}
}
