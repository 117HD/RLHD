import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

public abstract class GenerateBuildInfoTask extends DefaultTask {
	private static final String BUILD_INFO_CLASS_PATH = "rs117/hd/BuildInfo.java";
	private static final String BUILD_INFO_TEMPLATE =
		"package rs117.hd;\n" +
		"\n" +
		"public final class BuildInfo {\n" +
		"    public static final String VERSION = \"%s\";\n" +
		"    public static final String TIMESTAMP = \"%s\";\n" +
		"    public static final String COMMIT = \"%s\";\n" +
		"    public static final String BRANCH = \"%s\";\n" +
		"    public static final String REMOTE = \"%s\";\n" +
		"}\n";

	@OutputDirectory
	public abstract DirectoryProperty getOutputDir();

	@Inject
	public GenerateBuildInfoTask(ProjectLayout layout) {
		getOutputDir().convention(layout.getBuildDirectory().dir("generated/sources/" + getName() + "/main"));
	}

	private String sanitize(String s) {
		return s
			.replace("\\", "\\\\") // remove escape codes
			.replace("\"", "\\\"") // prevent escaping the string literal
			.replace("\n", "") // remove LF line breaks
			.replace("\r", "") // remove CR line breaks
			.trim();
	}

	private static class GitInfo {
		String COMMIT = "unknown";
		String BRANCH = "unknown";
		String REMOTE = "unknown";
	}

	@TaskAction
	public void generate() throws IOException {
		File outputFile = getOutputDir().file(BUILD_INFO_CLASS_PATH).get().getAsFile();
		// noinspection ResultOfMethodCallIgnored
		outputFile.getParentFile().mkdirs();

		GitInfo info = new GitInfo();
		try {
			populateGitInfo(info);
		} catch (Exception ignored) {
		}

		String content = String.format(
			BUILD_INFO_TEMPLATE,
			sanitize(getProject().getVersion().toString()),
			sanitize(Instant.now().toString()),
			sanitize(info.COMMIT),
			sanitize(info.BRANCH),
			sanitize(info.REMOTE)
		);
		Files.writeString(outputFile.toPath(), content);
	}

	private static final String REF_LOCAL = "ref: refs/heads/";

	private static boolean isCommitHash(String hash) {
		return
			(hash.length() == 40 || hash.length() == 64) &&
			hash.matches("^[0-9a-f]+$");
	}

	private void populateGitInfo(GitInfo info) throws Exception {
		File gitDir = new File(getProject().getRootDir(), ".git");
		File headFile = new File(gitDir, "HEAD");
		if (!headFile.exists())
			return;

		String head = Files.readString(headFile.toPath()).trim();
		if (head.startsWith(REF_LOCAL)) {
			info.BRANCH = head.substring(REF_LOCAL.length());
			File refFile = new File(gitDir, head.substring(5));
			if (refFile.exists())
				head = Files.readString(refFile.toPath()).trim();
		}

		if (!isCommitHash(head))
			return;

		info.COMMIT = head;

		File configFile = new File(gitDir, "config");
		if (!configFile.exists())
			return;

		String remote = null;
		String branch = null;

		Path remotesDir = gitDir.toPath().resolve("refs/remotes");
		Optional<Path> looseRef;
		try (var stream = Files.walk(remotesDir, 2)) {
			looseRef = stream
				.filter(Files::isRegularFile)
				.filter(path -> {
					try {
						return info.COMMIT.equals(Files.readString(path));
					} catch (IOException ignored) {
						return false;
					}
				})
				.findFirst();
		}

		if (looseRef.isPresent()) {
			var path = looseRef.get().relativize(remotesDir);
			remote = path.subpath(0, 1).toString();
			branch = path.subpath(1, 2).toString();
		} else {
			File packedRefsFile = new File(gitDir, "packed-refs");
			if (packedRefsFile.exists()) {
				String pattern = info.COMMIT + " refs/remotes/";
				var lines = Files.readAllLines(packedRefsFile.toPath());
				for (String line : lines) {
					if (!line.startsWith(pattern))
						continue;

					String packedRef = line.substring(pattern.length()).trim();
					String[] parts = packedRef.split("/");
					if (parts.length == 2) {
						remote = parts[0];
						branch = parts[1];
						break;
					}
				}
			}
		}

		if (remote != null) {
			String remoteHeader = "[remote \"" + remote + "\"]";

			boolean foundRemote = false;
			var lines = Files.readAllLines(configFile.toPath());
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				if (foundRemote) {
					if (line.startsWith("["))
						break;

					line = line.trim();
					if (line.startsWith("url = ")) {
						info.REMOTE = line.substring(6);
						info.BRANCH = branch;
						break;
					}
				} else if (line.equals(remoteHeader)) {
					foundRemote = true;
				}
			}
		}
	}
}
