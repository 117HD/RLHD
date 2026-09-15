package rs117.hd.resourcepacks;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Assert;
import org.junit.Test;

public class PackHashesTest {
	@Test
	public void computesSha256() throws Exception {
		File file = File.createTempFile("resource-pack", ".zip");
		try {
			try (FileOutputStream output = new FileOutputStream(file)) {
				output.write("abc".getBytes(StandardCharsets.UTF_8));
			}
			Assert.assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", PackHashes.sha256(file));
		} finally {
			Assert.assertTrue(file.delete());
		}
	}
}
