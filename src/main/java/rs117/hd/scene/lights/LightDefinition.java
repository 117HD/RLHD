package rs117.hd.scene.lights;

import com.google.gson.JsonElement;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import java.util.Collections;
import java.util.Set;
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
	public boolean despawnWithParent;
	public boolean visibleFromOtherPlanes;
	public boolean ignoreActorHiding;
	public int renderableIndex = -1;
	public boolean waitForAnimation;
	public float nightMultiplier = 1;
	public float specularMultiplier = 1;
	@Nullable
	@JsonAdapter(LightSchedule.Adapter.class)
	public LightSchedule schedule;
	@Nullable
	@SerializedName("outdoorLighting")
	private JsonElement outdoorLightingRaw;
	public transient boolean outdoorLighting;
	@Nullable
	public transient int[] outdoorLightingSampleWorldPos;

	@JsonAdapter(AABB.ArrayAdapter.class)
	public AABB[] areas = {};
	@JsonAdapter(AABB.ArrayAdapter.class)
	public AABB[] excludeAreas = {};
	@JsonAdapter(GamevalManager.NpcAdapter.class)
	public Set<Integer> npcIds = Collections.emptySet();
	@JsonAdapter(GamevalManager.ObjectAdapter.class)
	public Set<Integer> objectIds = Collections.emptySet();
	@JsonAdapter(GamevalManager.SpotanimAdapter.class)
	public Set<Integer> projectileIds = Collections.emptySet();
	@JsonAdapter(GamevalManager.SpotanimAdapter.class)
	public Set<Integer> graphicsObjectIds = Collections.emptySet();
	@JsonAdapter(GamevalManager.AnimationAdapter.class)
	public Set<Integer> animationIds = Collections.emptySet();
	@JsonAdapter(GamevalManager.AnimationAdapter.class)
	public Set<Integer> excludeAnimationIds = Collections.emptySet();

	public boolean hasAnimationFilter() {
		return !animationIds.isEmpty() || !excludeAnimationIds.isEmpty();
	}

	public boolean matchesAnimation(int animationId, boolean isPlaying) {
		if (waitForAnimation && !isPlaying)
			return false;
		if (excludeAnimationIds.contains(animationId))
			return false;
		return animationIds.isEmpty() || animationIds.contains(animationId);
	}

	public void normalize() {
		if (description == null)
			description = "N/A";
		if (alignment == null)
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

		if (outdoorLightingRaw == null || outdoorLightingRaw.isJsonNull()) {
			outdoorLightingRaw = null;
		} else if (outdoorLightingRaw.isJsonPrimitive()) {
			if (!outdoorLightingRaw.getAsJsonPrimitive().isBoolean())
				throw new IllegalStateException("outdoorLighting must be a boolean or [ worldX, worldY, plane ]");
			outdoorLighting = outdoorLightingRaw.getAsBoolean();
			if (!outdoorLighting)
				outdoorLightingRaw = null;
		} else {
			if (!outdoorLightingRaw.isJsonArray() || outdoorLightingRaw.getAsJsonArray().size() != 3)
				throw new IllegalStateException("outdoorLighting position must contain three coordinates");

			var sampleWorldPos = outdoorLightingRaw.getAsJsonArray();
			outdoorLightingSampleWorldPos = new int[3];
			for (int i = 0; i < outdoorLightingSampleWorldPos.length; i++) {
				JsonElement coordinate = sampleWorldPos.get(i);
				if (!coordinate.isJsonPrimitive() || !coordinate.getAsJsonPrimitive().isNumber())
					throw new IllegalStateException("outdoorLighting position must contain integer coordinates");
				int value = coordinate.getAsInt();
				if (value != coordinate.getAsDouble())
					throw new IllegalStateException("outdoorLighting position must contain integer coordinates");
				outdoorLightingSampleWorldPos[i] = value;
			}
			outdoorLighting = true;
		}

		npcIds = Set.copyOf(npcIds);
		objectIds = Set.copyOf(objectIds);
		projectileIds = Set.copyOf(projectileIds);
		graphicsObjectIds = Set.copyOf(graphicsObjectIds);
		animationIds = Set.copyOf(animationIds);
		excludeAnimationIds = Set.copyOf(excludeAnimationIds);
	}
}
