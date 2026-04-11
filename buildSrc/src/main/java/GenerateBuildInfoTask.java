import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.logging.Logger;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

public abstract class GenerateBuildInfoTask extends DefaultTask {
	private static final String BUILD_INFO_CLASS_PATH = "rs117/hd/BuildInfo.java";
	private static final String BUILD_INFO_SRC =
		"package rs117.hd;\n" +
		"\n" +
		"public final class BuildInfo {\n" +
		"    public static final String VERSION = \"%s\";\n" +
		"    public static final String TIMESTAMP = \"%s\";\n" +
		"    public static final String BRANCH = \"%s\";\n" +
		"    public static final String COMMIT = \"%s\";\n" +
		"    public static final String ORIGIN = \"%s\";\n" +
		"}\n";

	private final Logger log = getLogger();

	@OutputDirectory
	public abstract DirectoryProperty getOutputDir();

	@Inject
	public GenerateBuildInfoTask(ProjectLayout layout) {
		getOutputDir().convention(layout.getBuildDirectory().dir("generated/sources/" + getName() + "/main"));
	}

	@TaskAction
	public void generate() throws IOException {
		String[] gitBranchAndCommit = { "unknown", "unknown" };
		String origin = "unknown";
		try {
			File gitDir = new File(getProject().getRootDir(), ".git");
			gitBranchAndCommit = getGitBranchAndCommit(gitDir);
			origin = getGitOrigin(gitDir);
		} catch (IOException e) {
			log.warn("Failed to read git info", e);
		}

		File outputFile = new File(getOutputDir().get().getAsFile(), BUILD_INFO_CLASS_PATH);
		File outputDir = outputFile.getParentFile();
		if (outputDir != null && !outputDir.exists()) {
			outputDir.mkdirs();
			log.info("Created directory: {}", outputDir.getAbsolutePath());
		}

		String timestamp = Instant.now().toString();
		String content = String.format(
			BUILD_INFO_SRC,
			getProject().getVersion(),
			timestamp,
			gitBranchAndCommit[0],
			gitBranchAndCommit[1],
			origin
		);
		Files.writeString(outputFile.toPath(), content);

		log.info("Generated {}", BUILD_INFO_CLASS_PATH);
		log.info("  * Version:   {}", getProject().getVersion());
		log.info("  * Timestamp: {}", timestamp);
		log.info("  * Branch:    {}", gitBranchAndCommit[0]);
		log.info("  * Commit:    {}", gitBranchAndCommit[1]);
		log.info("  * Origin:    {}", origin);
	}

	public static String[] getGitBranchAndCommit(File gitDir) throws IOException {
		File headFile = new File(gitDir, "HEAD");
		if (!headFile.exists())
			return new String[] { "unknown", "unknown" };

		String head = Files.readString(headFile.toPath()).trim();

		if (head.startsWith("ref: ")) {
			String refPath = head.substring(5);
			String branch = refPath.substring(refPath.lastIndexOf('/') + 1);
			String commit = readCommitFromRef(gitDir, refPath);

			return new String[] { branch, commit };
		}

		return new String[] { "detached", shortHash(head) };
	}

	public static String readCommitFromRef(File gitDir, String refPath) throws IOException {
		File refFile = new File(gitDir, refPath);
		if (refFile.exists())
			return shortHash(Files.readString(refFile.toPath()).trim());

		File packedRefs = new File(gitDir, "packed-refs");

		if (packedRefs.exists()) {
			List<String> lines = Files.readAllLines(packedRefs.toPath());

			for (String line : lines)
				if (!line.startsWith("#") && line.endsWith(refPath))
					return shortHash(line.split(" ")[0]);
		}

		return "unknown";
	}

	public static String getGitOrigin(File gitDir) throws IOException {
		File config = new File(gitDir, "config");
		if (!config.exists())
			return "unknown";

		String text = Files.readString(config.toPath());
		Pattern pattern = Pattern.compile("\\[remote\\s+\"origin\"]([\\s\\S]*?)url\\s*=\\s*(.+)");
		Matcher matcher = pattern.matcher(text);

		if (matcher.find())
			return matcher.group(2).trim();

		return "unknown";
	}

	public static String shortHash(String hash) { return hash.length() > 7 ? hash.substring(0, 7) : hash; }
}
