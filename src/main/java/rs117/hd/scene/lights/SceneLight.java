package rs117.hd.scene.lights;

import java.util.Random;

import lombok.NonNull;
import net.runelite.api.*;

public class SceneLight extends Light
{

	private static final Random randomizer = new Random();

	public final int randomOffset = randomizer.nextInt();

	public int currentSize;
	public float currentStrength;
	/**
	 * Linear color space RGBA in the range [0, 1]
	 */
	public float[] currentColor;
	public float currentAnimation = 0.5f;
	public int fadeStepIndex = 0;
	public long fadeStepTime = 0;
	public boolean visible = true;

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
	public Actor actor = null;
	public int actorGraphicId = -1;

	public SceneLight(Light l)
	{
		this(l.description, l.worldX, l.worldY, l.plane, l.height, l.alignment, l.radius,
			l.strength, l.color, l.type, l.duration, l.range, l.fadeStepsMs);
	}

	public SceneLight(int worldX, int worldY, int plane, int height, @NonNull Alignment alignment, int radius, float strength, float[] color, LightType type, float duration, float range, int[] fadeStepsMs)
	{
		this(null, worldX, worldY, plane, height, alignment, radius,
			strength, color, type, duration, range, fadeStepsMs);
	}

	public SceneLight(String description, int worldX, int worldY, int plane, int height, @NonNull Alignment alignment, int radius, float strength, float[] color, LightType type, float duration, float range, int[] fadeStepsMs)
	{
		super(description, worldX, worldY, plane, height, alignment, radius,
			strength, color, type, duration, range, fadeStepsMs);

		this.currentSize = radius;
		this.currentStrength = strength;
		this.currentColor = color;
		this.currentAnimation = (float) Math.random();
	}
}
