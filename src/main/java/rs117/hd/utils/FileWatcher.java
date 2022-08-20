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

	private static void initialize() throws IOException {
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

						try {
							// Manually register new sub folders if not watching a file tree
							if (event.kind() == ENTRY_CREATE && path.toFile().isDirectory())
								watchRecursively(path);

							String key = path.toString();
							ResourcePath resourcePath = path(key);
							if (path.toFile().isDirectory())
								key += File.separator;

							for (Map.Entry<String, Consumer<ResourcePath>> entry : changeHandlers.entries()) {
								if (key.startsWith(entry.getKey())) {
									try {
										entry.getValue().accept(resourcePath);
									} catch (Throwable throwable) {
										log.error("Error in change handler for path: {}", dir, throwable);
									}
								}
							}
						} catch (Exception ex) {
							log.error("Error while handling file change event:", ex);
						}
					}
					watchKey.reset();
				}
			}
			catch (ClosedWatchServiceException ignored) {}
			catch (InterruptedException ex) {
				log.error("Watcher thread interrupted", ex);
			}
		},  FileWatcher.class.getSimpleName() + " Thread");
		watcherThread.setDaemon(true);
		watcherThread.start();
	}

	public static void destroy() {
		if (watchService == null)
			return;

		try {
			log.debug("Shutting down {}", FileWatcher.class.getSimpleName());
			changeHandlers.clear();
			watchKeys.clear();
			watchService.close();
			watchService = null;
			if (watcherThread.isAlive())
				watcherThread.join();
		} catch (IOException | InterruptedException ex) {
			throw new RuntimeException("Error while closing " + FileWatcher.class.getSimpleName(), ex);
		}
	}

	@FunctionalInterface
	public interface UnregisterCallback {
		void unregister();
	}

	public static UnregisterCallback watchPath(@NonNull ResourcePath resourcePath, @NonNull Consumer<ResourcePath> changeHandler)
	{
		if (!resourcePath.isFileSystemResource())
			throw new IllegalStateException("Only resources on the file system can be watched: " + resourcePath);

		try {
			if (watchService == null)
				initialize();

			Path path = resourcePath.toPath();

			final String key;
			final Consumer<ResourcePath> handler;
			if (path.toFile().isDirectory()) {
				watchRecursively(path);
				key = path + File.separator;
				handler = changeHandler;
			} else {
				watchFile(path);
				key = path.toString();
				handler = changed -> {
					try {
						if (Files.isSameFile(changed.toPath(), resourcePath.toPath()))
							changeHandler.accept(changed);
					} catch (IOException ex) {
						throw new RuntimeException(ex);
					}
				};
			}

			changeHandlers.put(key, handler);
			return () -> changeHandlers.remove(key, handler);
		} catch (IOException ex) {
			throw new RuntimeException("Failed to initialize " + FileWatcher.class.getSimpleName(), ex);
		}
	}

	private static void watchFile(Path path) {
		Path dir = path.getParent();
		try {
			watchKeys.put(dir.register(watchService, eventKinds), dir);
			log.debug("Watching {}", dir);
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
					log.debug("Watching {}", dir);
					watchKeys.put(key, dir);
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException ex) {
			throw new RuntimeException("Failed to register recursive file watcher for path: " + path, ex);
		}
	}
}
