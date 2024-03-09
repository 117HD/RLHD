package rs117.hd.scene.lights;

import com.google.gson.annotations.JsonAdapter;
import java.util.HashSet;
import javax.annotation.Nullable;
import lombok.NoArgsConstructor;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.GsonUtils;

@NoArgsConstructor // Called by GSON when parsing JSON
public class LightDefinition {
	public String description;
	@Nullable
	public Integer worldX, worldY;
	public int plane;
	public Alignment alignment = Alignment.CENTER;
	public int height;
	public int radius = 300;
	public float strength = 5;
	@JsonAdapter(ColorUtils.SrgbToLinearAdapter.class)
	public float[] color;
	public LightType type = LightType.STATIC;
	public float duration;
	public float range;
	public int fadeInDuration = 50;
	public int fadeOutDuration = 50;
	public boolean fadeOverlap;
	public int spawnDelay;
	public int despawnDelay;
	public boolean fixedDespawnTime;
	public boolean visibleFromOtherPlanes = false;
	@JsonAdapter(GsonUtils.IntegerSetAdapter.class)
	public HashSet<Integer> npcIds = new HashSet<>();
	@JsonAdapter(GsonUtils.IntegerSetAdapter.class)
	public HashSet<Integer> objectIds = new HashSet<>();
	@JsonAdapter(GsonUtils.IntegerSetAdapter.class)
	public HashSet<Integer> projectileIds = new HashSet<>();
	@JsonAdapter(GsonUtils.IntegerSetAdapter.class)
	public HashSet<Integer> graphicsObjectIds = new HashSet<>();
	@JsonAdapter(GsonUtils.IntegerSetAdapter.class)
	public HashSet<Integer> animationIds = new HashSet<>();

	public void normalize() {
		if (description == null)
			description = "N/A";
		if (alignment == null)
			alignment = Alignment.CENTER;
		if (color == null)
			color = new float[3];
		if (type == null)
			type = LightType.STATIC;
	}
}
