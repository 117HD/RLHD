package rs117.hd.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static rs117.hd.utils.ResourcePath.path;

public class ResourcePathTest {
	@Test
	public void testGetExtension() {
		assertEquals("txt", path("somefile.TXT").getExtension());
		assertEquals("txt", path("some.other.file.txt").getExtension());
		assertEquals("file", path("some.other.file.txt").getExtension(1));
		assertEquals("", path("some.txt").getExtension(1));
		assertEquals("", path("some.txt").getExtension(5));
		assertEquals("", path("some-file-without-ext").getExtension());
		assertEquals("", path("").getExtension());
		assertEquals("", path("").getExtension(5));
		assertEquals("", path(".").getExtension());
	}
}
