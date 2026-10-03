package rs117.hd.resourcepacks;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class PackHashes {
	private PackHashes() {
	}

	static MessageDigest sha256Digest() throws IOException {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException ex) {
			throw new IOException("SHA-256 is unavailable", ex);
		}
	}

	static String sha256(File file) throws IOException {
		MessageDigest digest = sha256Digest();
		if (file.isDirectory())
			updateDirectory(digest, file.toPath());
		else
			updateFile(digest, file);
		return toHex(digest.digest());
	}

	static String sha256(String value) throws IOException {
		MessageDigest digest = sha256Digest();
		digest.update(value.getBytes(StandardCharsets.UTF_8));
		return toHex(digest.digest());
	}

	private static void updateFile(MessageDigest digest, File file) throws IOException {
		byte[] buffer = new byte[8192];
		try (FileInputStream input = new FileInputStream(file)) {
			for (int read; (read = input.read(buffer)) != -1;)
				digest.update(buffer, 0, read);
		}
	}

	@SuppressWarnings("NullableProblems")
	private static void updateDirectory(MessageDigest digest, Path root) throws IOException {
		List<Path> files = new ArrayList<>();
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
				return attributes.isSymbolicLink() ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
				if (attributes.isRegularFile() && !attributes.isSymbolicLink())
					files.add(file);
				return FileVisitResult.CONTINUE;
			}
		});
		files.sort(Comparator.comparing(path -> root.relativize(path).toString().replace(File.separatorChar, '/')));
		for (Path file : files) {
			digest.update(root.relativize(file).toString().replace(File.separatorChar, '/').getBytes(StandardCharsets.UTF_8));
			digest.update((byte) 0);
			updateFile(digest, file.toFile());
		}
	}

	static String toHex(byte[] bytes) {
		StringBuilder result = new StringBuilder(bytes.length * 2);
		for (byte value : bytes)
			result.append(String.format("%02x", value));
		return result.toString();
	}
}
