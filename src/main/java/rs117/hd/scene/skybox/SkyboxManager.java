package rs117.hd.scene.skybox;

import com.google.gson.Gson;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.RasterFormatException;
import java.io.IOException;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.BufferUtils;
import rs117.hd.HdPlugin;
import rs117.hd.scene.LightManager;
import rs117.hd.scene.TextureManager;
import rs117.hd.utils.ImageUtils;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL12C.*;
import static org.lwjgl.opengl.GL13C.*;
import static org.lwjgl.opengl.GL21C.GL_SRGB8_ALPHA8;
import static org.lwjgl.opengl.GL40C.*;
import static org.lwjgl.opengl.GL42C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_SKYBOX;
import static rs117.hd.utils.ResourcePath.path;

@Slf4j
@Singleton
public class SkyboxManager {

	private static final String[] FACE_FILE_NAMES = { "px", "nx", "py", "ny", "pz", "nz" };
	private static final int SKYBOX_FACE_SIZE = 512;

	@Inject
	private HdPlugin plugin;

	private int textureSkybox;
	private SkyboxConfig skyboxConfig;
	private IntBuffer pixelBuffer;
	private BufferedImage scaledImage;

	public static final ResourcePath SKYBOX_PATH = Props.getPathOrDefault(
		"rlhd.skybox-path",
		() -> path(LightManager.class, "skybox.json")
	);


	private final Map<String, Integer> skyboxes = new HashMap<>();

	public void ensureSkyboxesAreLoaded() {
		if (skyboxConfig == null || skyboxConfig.skyboxes.isEmpty()) {
			return;
		}

		if(!HdPlugin.glCaps.OpenGL43){
			log.debug("Skipping loading sky boxes due to lack of OpenGL43");
			return;
		}

		plugin.updateSkyboxVerticies(skyboxConfig.vertices);

		if (textureSkybox != 0) {
			glDeleteTextures(textureSkybox);
		}

		textureSkybox = glGenTextures();
		glActiveTexture(TEXTURE_UNIT_SKYBOX);
		glBindTexture(GL_TEXTURE_CUBE_MAP_ARRAY, textureSkybox);
		glTexStorage3D(GL_TEXTURE_CUBE_MAP_ARRAY, 1, GL_SRGB8_ALPHA8,
			skyboxConfig.resolution, skyboxConfig.resolution, skyboxConfig.skyboxes.size() * 6);

		glTexParameteri(GL_TEXTURE_CUBE_MAP_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_CUBE_MAP_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_CUBE_MAP_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_CUBE_MAP_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_CUBE_MAP_ARRAY, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);

		pixelBuffer = BufferUtils.createIntBuffer(skyboxConfig.resolution * skyboxConfig.resolution);

		int validSkyboxCount = 0;

		skyboxes.clear();
		for (SkyboxConfig.SkyboxEntry skybox : skyboxConfig.skyboxes) {
			String dir = skybox.getDir();

			if (skyboxes.containsKey(dir)) {
				int reusedTextureID = getSkyboxTextureByDir(dir);
				log.debug("Skybox: {} previously uploaded entry using texture {}", skybox.getName(),reusedTextureID);
				skyboxes.put(skybox.getName(), getSkyboxTextureByDir(dir));
				continue;
			}

			BufferedImage[] faceImages = loadSkyboxFaces(dir);
			String loadedFaces = uploadSkyboxTextures(faceImages, validSkyboxCount);

			if (loadedFaces == null) {
				log.debug("Skybox: {} failed to load any face texture", dir);
				continue;
			}

			log.debug("Skybox: {} loaded faces [{}]", dir, loadedFaces);
			skyboxes.put(dir, validSkyboxCount++);
		}

		log.debug("Loaded {} Skybox's", skyboxes.size());
		plugin.checkGLErrors();

		pixelBuffer = null;
		scaledImage = null;
	}

	private String uploadSkyboxTextures(BufferedImage[] faceImages, int skyboxIndex) {
		StringBuilder loadedFaces = new StringBuilder();

		for (int faceIdx = 0; faceIdx < 6; faceIdx++) {
			BufferedImage faceImage = faceImages[faceIdx];
			if (faceImage == null) continue;

			if (loadedFaces.length() > 0) loadedFaces.append(", ");
			loadedFaces.append(FACE_FILE_NAMES[faceIdx]);

			BufferedImage scaled = ImageUtils.scaleTextureSimple(scaledImage,faceImage, skyboxConfig.resolution, false, false);
			int[] pixels = ((DataBufferInt) scaled.getRaster().getDataBuffer()).getData();
			pixelBuffer.put(pixels).flip();

			glTexSubImage3D(
				GL_TEXTURE_CUBE_MAP_ARRAY, 0, 0, 0,
				skyboxIndex * 6 + faceIdx,
				skyboxConfig.resolution, skyboxConfig.resolution, 1,
				GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, pixelBuffer
			);
		}

		return loadedFaces.length() > 0 ? loadedFaces.toString() : null;
	}


	public void loadSkyboxConfig(Gson gson, ResourcePath path) {
		try {
			skyboxConfig = path.loadJson(gson, SkyboxConfig.class);
			if (skyboxConfig == null) {
				log.warn("Skipping empty skybox.json");
			}
		} catch (IOException ex) {
			log.error("Failed to load skybox config", ex);
		}

	}

	private BufferedImage[] loadSkyboxFaces(String dir) {
		BufferedImage[] faceImages = new BufferedImage[6];

		BufferedImage atlas = ImageUtils.loadTextureImage(TextureManager.TEXTURE_PATH,"skybox/" + dir + "/skybox");
		if (extractFacesFromAtlas(atlas, faceImages)) {
			return faceImages;
		}

		for (int i = 0; i < 6; i++) {
			faceImages[i] = ImageUtils.loadTextureImage(TextureManager.TEXTURE_PATH,"skybox/" + dir + "/" + FACE_FILE_NAMES[i]);
		}

		return faceImages;
	}

	private boolean extractFacesFromAtlas(BufferedImage atlas, BufferedImage[] outFaces) {
		if (atlas == null) return false;

		int face = SKYBOX_FACE_SIZE;

		if (atlas.getWidth() < face * 4 || atlas.getHeight() < face * 2) {
			log.warn("Unexpected atlas dimensions: {}x{} (expected ≥{}x{})", atlas.getWidth(), atlas.getHeight(), face * 4, face * 2);
			return false;
		}

		if (atlas.getWidth() % SKYBOX_FACE_SIZE != 0 || atlas.getHeight() % SKYBOX_FACE_SIZE != 0) {
			log.warn("Atlas size is not evenly divisible by face size: {}x{} with tile {}", atlas.getWidth(), atlas.getHeight(), SKYBOX_FACE_SIZE);
		}

		try {
			// Maintain your original face order
			outFaces[0] = atlas.getSubimage(0, 0, face, face);
			outFaces[5] = atlas.getSubimage(face, 0, face, face);
			outFaces[1] = atlas.getSubimage(face * 2, 0, face, face);
			outFaces[4] = atlas.getSubimage(face * 3, 0, face, face);
			outFaces[2] = atlas.getSubimage(0, face, face, face);
			outFaces[3] = atlas.getSubimage(face, face, face, face);

			return true;
		} catch (RasterFormatException e) {
			log.warn("Invalid subimage layout in atlas: {}", e.toString());
			return false;
		}
	}

	public SkyboxConfig.SkyboxEntry getSkybox(String skyboxId) {
		if (skyboxConfig == null || skyboxId == null) {
			return null;
		}

		return skyboxConfig.skyboxes.stream()
			.filter(skybox -> skyboxId.equalsIgnoreCase(skybox.getName()))
			.findFirst()
			.orElse(null);
	}


	public SkyboxConfig.SkyboxEntry getSkyboxTextureByName(String name) {
		return getSkybox(name);
	}

	public Integer getSkyboxTextureByDir(String dir) {
		return skyboxes.getOrDefault(dir,-1);
	}

	public int getSkyboxCount() {
		return skyboxConfig != null ? skyboxConfig.skyboxes.size() : 0;
	}

	public void freeTextures() {
		if(textureSkybox != 0)
			glDeleteTextures(textureSkybox);
		textureSkybox = 0;
	}

}