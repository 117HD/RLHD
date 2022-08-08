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

    public static void setHotSwapping(boolean state) {
        hotswapping = state;
    }

    public static InputStream getResourceWatching(Class<?> clazz, String path) {
        return getResource(clazz,Paths.get(path).toAbsolutePath());
    }

    public static InputStream getResource(Class<?> clazz, Path path) {
        if (hotswapping) {
            Path filePath = Paths.get("src/main/resources")
                    .resolve(clazz.getPackage().getName().replace(".", "/"))
                    .resolve(path);
            try {
                filePath = filePath.toRealPath();
                return getResource(filePath);
            } catch (IOException ex) {
                log.error("Failed to load resource: {}", filePath, ex);
            }
        }
        return getJarResource(clazz, path);
    }

    public static InputStream getResource(Path path) {
        try {
            return Files.newInputStream(path);
        } catch (IOException ex) {
            throw new RuntimeException("Missing file: " + path, ex);
        }
    }

    public static InputStream getJarResource(Class<?> clazz, Path path) {
        InputStream is;
        is = clazz.getResourceAsStream(path.toString().replace('\\', '/'));
        if (is == null)
        {
            throw new RuntimeException("Missing resource: " + clazz);
        }
        return is;
    }

}
