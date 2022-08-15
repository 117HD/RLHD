package rs117.hd.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
public class FileUtils {
    private static boolean hotswapping = false;

    public static void useHotswapping() {
        hotswapping = true;
    }

    public static InputStream getResource(Class<?> clazz, Path path) throws IOException {
        if (hotswapping) {
            Path filePath = Paths.get("src/main/resources")
                    .resolve(clazz.getPackage().getName().replace(".", "/"))
                    .resolve(path);
            try {
                filePath = filePath.toRealPath();
                return getResource(filePath);
            } catch (IOException ex) {
                log.trace("Failed to load resource: {}", filePath, ex);
            }
        }
        return getJarResource(clazz, path);
    }

    public static InputStream getResource(Path path) throws IOException {
        return Files.newInputStream(path);
    }

    public static InputStream getJarResource(Class<?> clazz, Path path) throws IOException
    {
        String resourcePath = path.toString().replace('\\', '/');
        InputStream is = clazz.getResourceAsStream(resourcePath);
        if (is == null)
        {
            throw new IOException(String.format("Failed to load resource: %s.%s",
                    clazz.getPackage().getName().replaceAll(".", "/"),
                    resourcePath));
        }
        return is;
    }

}