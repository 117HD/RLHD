package rs117.hd.scene.lights;

import net.runelite.api.*;
import net.runelite.api.coords.*;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.HDUtils;

public class Light
{
	public final int randomOffset = HDUtils.rand.nextInt();
	public final LightDefinition def;

	public int radius;
	public float strength;
	/**
	 * Linear color space RGBA in the range [0, 1]
	 */
	public float[] color;
	public float animation = 0.5f;
	public int currentFadeIn = 0;
	public int impostorObjectId;
	public boolean visible = true;

	public WorldPoint worldPoint;
	public int x;
	public int y;
	public int z;
	public int plane;
	public int distanceSquared = 0;
	public int fadeInDuration;
	public int fadeOutDuration;
	public boolean belowFloor = false;
	public boolean aboveFloor = false;

	public Projectile projectile;
	public NPC npc;
	public TileObject object;
	public GraphicsObject graphicsObject;

	public ModelOverride modelOverride = ModelOverride.NONE;

	public Light(LightDefinition def) {
		this.def = def;
		plane = def.plane;
		fadeInDuration = def.fadeInDuration;
		fadeOutDuration = def.fadeOutDuration;
		radius = def.radius;
		strength = def.strength;
		color = def.color;
		if (def.type == LightType.PULSE)
			animation = (float) Math.random();
	}
}
