package rs117.hd.scene.lights;

import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import java.util.HashSet;
import javax.annotation.Nullable;
import rs117.hd.scene.areas.AABB;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.GsonUtils;

public class LightDefinition {
	public String description;
	@Nullable
	public Integer worldX, worldY;
	public int plane;
	public Alignment alignment = Alignment.CUSTOM;
	public int[] offset = new int[3];
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
	public int spawnDelay;
	public int despawnDelay;
	public boolean fixedDespawnTime;
	public boolean visibleFromOtherPlanes;
	public boolean ignoreActorHiding;
	public int renderableIndex = -1;

	@JsonAdapter(AABB.JsonAdapter.class)
	public AABB[] areas = {};
	@JsonAdapter(AABB.JsonAdapter.class)
	public AABB[] excludeAreas = {};
	@JsonAdapter(GsonUtils.StringSetAdapter.class)
	public HashSet<String> npcIds = new HashSet<>();
	@JsonAdapter(GsonUtils.StringSetAdapter.class)
	public HashSet<String> objectIds = new HashSet<>();
	@JsonAdapter(GsonUtils.StringSetAdapter.class)
	public HashSet<String> projectileIds = new HashSet<>();
	@JsonAdapter(GsonUtils.StringSetAdapter.class)
	@SerializedName("graphicsObjectIds") // TODO: rename this
	public HashSet<String> spotAnimIds = new HashSet<>();
	@JsonAdapter(GsonUtils.StringSetAdapter.class)
	public HashSet<String> animationIds = new HashSet<>();

	public void normalize() {
		if (description == null)
			description = "N/A";
		if (alignment == null || alignment == Alignment.CENTER)
			alignment = Alignment.CUSTOM;
		if (offset == null || offset.length != 3) {
			offset = new int[3];
		} else {
			offset[1] *= -1;
		}
		if (color == null || color.length != 3)
			color = new float[3];
		if (type == null)
			type = LightType.STATIC;
	}
}
