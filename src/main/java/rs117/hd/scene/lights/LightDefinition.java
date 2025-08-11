package rs117.hd.scene.lights;

import com.google.gson.annotations.JsonAdapter;
import java.util.HashSet;
import javax.annotation.Nullable;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.areas.AABB;
import rs117.hd.utils.ColorUtils;

public class LightDefinition {
	public String description;
	@Nullable
	public Integer worldX, worldY;
	public int plane;
	public Alignment alignment = Alignment.CUSTOM;
	public float[] offset = new float[3];
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

	@JsonAdapter(AABB.ArrayAdapter.class)
	public AABB[] areas = {};
	@JsonAdapter(AABB.ArrayAdapter.class)
	public AABB[] excludeAreas = {};
	@JsonAdapter(GamevalManager.NpcAdapter.class)
	public HashSet<Integer> npcIds = new HashSet<>();
	@JsonAdapter(GamevalManager.ObjectAdapter.class)
	public HashSet<Integer> objectIds = new HashSet<>();
	@JsonAdapter(GamevalManager.SpotanimAdapter.class)
	public HashSet<Integer> projectileIds = new HashSet<>();
	@JsonAdapter(GamevalManager.SpotanimAdapter.class)
	public HashSet<Integer> graphicsObjectIds = new HashSet<>();
	@JsonAdapter(GamevalManager.AnimationAdapter.class)
	public HashSet<Integer> animationIds = new HashSet<>();

	public void normalize() {
		if (description == null)
			description = "N/A";
		if (alignment == null || alignment == Alignment.CENTER)
			alignment = Alignment.CUSTOM;
		if (offset == null || offset.length != 3) {
			offset = new float[3];
		} else {
			offset[1] *= -1;
		}
		if (color == null || color.length != 3)
			color = new float[3];
		if (type == null)
			type = LightType.STATIC;
	}
}
