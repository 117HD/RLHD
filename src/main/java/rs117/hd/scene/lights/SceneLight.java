package rs117.hd.scene.lights;

import lombok.NonNull;
import net.runelite.api.GraphicsObject;
import net.runelite.api.NPC;
import net.runelite.api.Projectile;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import rs117.hd.utils.HDUtils;

public class SceneLight extends Light
{
	public final int randomOffset = HDUtils.rand.nextInt();

	public int currentSize;
	public float currentStrength;
	/**
	 * Linear color space RGBA in the range [0, 1]
	 */
	public float[] currentColor;
	public float currentAnimation = 0.5f;
	public int currentFadeIn = 0;
	public boolean visible = true;

	public WorldPoint worldPoint;
	public int x;
	public int y;
	public int z;
	public int distance = 0;
	public boolean belowFloor = false;
	public boolean aboveFloor = false;

	public Projectile projectile = null;
	public NPC npc = null;
	public TileObject object = null;
	public GraphicsObject graphicsObject = null;

	public SceneLight(Light l, WorldPoint worldPoint)
	{
		this(l.description, worldPoint.getX(), worldPoint.getY(), l.plane, l.height, l.alignment, l.radius,
			l.strength, l.color, l.type, l.duration, l.range, l.fadeInDuration);
		this.worldPoint = worldPoint;
	}

	public SceneLight(int worldX, int worldY, int plane, int height, @NonNull Alignment alignment, int radius, float strength, float[] color, LightType type, float duration, float range, int fadeInDuration)
	{
		this(null, worldX, worldY, plane, height, alignment, radius,
			strength, color, type, duration, range, fadeInDuration);
	}

	public SceneLight(String description, int worldX, int worldY, int plane, int height, @NonNull Alignment alignment, int radius, float strength, float[] color, LightType type, float duration, float range, int fadeInDuration)
	{
		super(description, worldX, worldY, plane, height, alignment, radius,
			strength, color, type, duration, range, fadeInDuration,
			null, null, null, null);

		this.currentSize = radius;
		this.currentStrength = strength;
		this.currentColor = color;

		if (type == LightType.PULSE)
		{
			this.currentAnimation = (float) Math.random();
		}
	}
}
