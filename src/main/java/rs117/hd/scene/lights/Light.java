package rs117.hd.scene.lights;

import net.runelite.api.*;
import net.runelite.api.coords.*;

import static rs117.hd.utils.MathUtils.*;

public class Light
{
	public static final float VISIBILITY_FADE = 0.1f;

	public final float randomOffset = RAND.nextFloat();
	public final LightDefinition def;

	public float radius;
	public float strength;
	/**
	 * Linear color space RGBA in the range [0, 1]
	 */
	public float[] color;
	public float animation = 0.5f;
	public float duration;
	public float fadeInDuration;
	public float fadeOutDuration;
	public float spawnDelay;
	public float despawnDelay;

	public boolean visible;
	public boolean parentExists;
	public boolean withinViewingDistance = true;
	public boolean hiddenTemporarily;
	public boolean markedForRemoval;
	public boolean persistent;
	public boolean replayable;

	public final boolean animationSpecific;
	public final boolean dynamicLifetime;

	public float elapsedTime;
	public float changedVisibilityAt = -1;
	public float lifetime = -1;

	public WorldPoint worldPoint;
	public boolean belowFloor;
	public boolean aboveFloor;
	public int plane;
	public int prevPlane = -1;
	public Alignment alignment;
	public float[] origin = new float[3];
	public float[] offset = new float[3];
	public float[] pos = new float[3];
	public int orientation;
	public float distanceSquared;

	public Actor actor;
	public Projectile projectile;
	public TileObject tileObject;
	public GraphicsObject graphicsObject;
	public int spotanimId = -1;
	public int[] projectileRefCounter;
	public long hash;

	public int sizeX = 1;
	public int sizeY = 1;

	public Light(LightDefinition def) {
		this.def = def;
		copyTo(offset, def.offset);
		duration = max(0, def.duration) / 1000f;
		fadeInDuration = max(0, def.fadeInDuration) / 1000f;
		fadeOutDuration = max(0, def.fadeOutDuration) / 1000f;
		spawnDelay = max(0, def.spawnDelay) / 1000f;
		despawnDelay = max(0, def.despawnDelay) / 1000f;
		color = def.color;
		radius = def.radius;
		strength = def.strength;
		alignment = def.alignment;
		plane = def.plane;
		if (def.type == LightType.PULSE)
			animation = (float) Math.random();

		// Old way of setting a fixed lifetime
		if (def.fixedDespawnTime)
			lifetime = spawnDelay + despawnDelay;

		if (lifetime == -1) {
			dynamicLifetime = true;
			// If the despawn is dynamic, ensure there's enough time for the light to fade out
			despawnDelay = max(despawnDelay, fadeOutDuration);
		} else {
			dynamicLifetime = false;
		}

		animationSpecific = !def.animationIds.isEmpty();
		if (animationSpecific) {
			persistent = replayable = true;
			// Initially hide the light
			if (dynamicLifetime) {
				lifetime = 0;
			} else {
				elapsedTime = lifetime;
			}
		}
	}

	public void toggleTemporaryVisibility(boolean changedPlanes) {
		hiddenTemporarily = !hiddenTemporarily;
		// If visibility changes due to something other than changing planes, fade in or out
		if (!changedPlanes) {
			// Begin fading in or out, while accounting for time already spent fading out or in respectively
			float beginFadeAt = elapsedTime;
			if (changedVisibilityAt != -1)
				beginFadeAt -= max(0, VISIBILITY_FADE - (elapsedTime - changedVisibilityAt));
			changedVisibilityAt = beginFadeAt;
		}
	}

	public float getTemporaryVisibilityFade() {
		float fade = 1;
		if (changedVisibilityAt != -1)
			fade = saturate((elapsedTime - changedVisibilityAt) / Light.VISIBILITY_FADE);
		if (hiddenTemporarily)
			fade = 1 - fade; // Fade out instead
		return fade;
	}

	public void applyTemporaryVisibilityFade() {
		strength *= getTemporaryVisibilityFade();
	}
}
