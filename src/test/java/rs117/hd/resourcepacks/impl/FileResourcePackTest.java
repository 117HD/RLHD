package rs117.hd.resourcepacks.impl;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Assert;
import org.junit.Test;

public class FileResourcePackTest {
	@Test
	public void supportsEmptyManifestFreeDirectories() throws IOException {
		File directory = new File(Files.createTempDirectory("resource-pack").toFile(), "My Custom Pack");
		Assert.assertTrue(directory.mkdir());
		try {
			FileResourcePack pack = new FileResourcePack(directory);

			Assert.assertFalse(pack.isValid());
			Assert.assertEquals("My Custom Pack", pack.getManifest().getDisplayName());
			Assert.assertFalse(pack.hasContent());
		} finally {
			Assert.assertTrue(directory.delete());
			Assert.assertTrue(directory.getParentFile().delete());
		}
	}
}
