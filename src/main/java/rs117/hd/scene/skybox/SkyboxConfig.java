package rs117.hd.scene.skybox;

import com.google.gson.annotations.JsonAdapter;
import java.util.List;
import lombok.Getter;
import rs117.hd.utils.GsonUtils;

@Getter
public class SkyboxConfig {
	public float[] vertices;
	public int resolution;
	public List<SkyboxEntry> skyboxes;

	@Getter
	public class SkyboxEntry {
		private String name;
		private String dir;
		@JsonAdapter(GsonUtils.DegreesToRadians.class)
		private float rotation;
		private SkyboxPostProcessingConfig postProcessing;

	}

	@Getter
	public class SkyboxPostProcessingConfig {
		private final float saturation = -1f;
		private final float hue = -1f;
		private final float lightness = -1f;

		private final float contrast = -1f;
		public float[] tintColor = {-1f, -1f, -1f};

	}

}
