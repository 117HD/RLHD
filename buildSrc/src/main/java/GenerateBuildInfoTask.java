import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

public abstract class GenerateBuildInfoTask extends DefaultTask {
	private static final String BUILD_INFO_CLASS_PATH = "rs117/hd/BuildInfo.java";
	private static final String BUILD_INFO_SRC = """
		package rs117.hd;
		
		public final class BuildInfo {
		
		    public static final String VERSION = "%s";
		    public static final String TIMESTAMP = "%s";
		    public static final String BRANCH = "%s";
		    public static final String COMMIT_HASH = "%s";
		    public static final String ORIGIN = "%s";
		
		    private BuildInfo() {}
		}
		""";

	@Inject
	public GenerateBuildInfoTask() {
		getOutputDir().convention(
			getProject()
				.getLayout()
				.getBuildDirectory()
				.dir("generated/sources/buildinfo")
		);
	}

	@OutputDirectory
	public abstract DirectoryProperty getOutputDir();

	@TaskAction
	public void generate() throws IOException {
		final Logger log = getLogger();

		String[] gitBranchAndCommit = { "unknown", "unknown" };
		String origin = "unknown";
		try {
			File gitDir = new File(getProject().getRootDir(), ".git");

			gitBranchAndCommit = GitUtils.getGitBranchAndCommit(gitDir);
			origin = GitUtils.getGitOrigin(gitDir);
		} catch (IOException e) {
			log.warn("Failed to read git info", e);
		}

		File outputFile = new File(getOutputDir().get().getAsFile(), BUILD_INFO_CLASS_PATH);
		File outputDir = outputFile.getParentFile();
		if(outputDir != null && !outputDir.exists()) {
			log.info("Created directory: {}", outputDir.getAbsolutePath());
			outputDir.mkdirs();
		}

		String timestamp = Instant.now().toString();
		String content = BUILD_INFO_SRC
			.formatted(
				getProject().getVersion().toString(),
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
}