import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GitUtils {
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
