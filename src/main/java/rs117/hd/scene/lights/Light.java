package rs117.hd.scene.lights;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.data.NpcID;
import rs117.hd.data.ObjectID;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import static rs117.hd.utils.GsonUtils.parseIDArray;
import static rs117.hd.utils.GsonUtils.writeIDArray;

@Slf4j
public class Light
{
	public String description;
	public Integer worldX, worldY, plane, height;
	public Alignment alignment;
	public int radius;
	public float strength;
	/**
	 * Linear color space RGBA in the range [0, 1]
	 */
	public float[] color;
	public LightType type;
	public float duration;
	public float range;
	public int fadeInDuration;
	@JsonAdapter(NpcID.JsonAdapter.class)
	public HashSet<Integer> npcIds;
	@JsonAdapter(ObjectID.JsonAdapter.class)
	public HashSet<Integer> objectIds;
	@JsonAdapter(ProjectileJsonAdapter.class)
	public HashSet<Integer> projectileIds;

	// Called by GSON when parsing JSON
	public Light()
	{
		npcIds = new HashSet<>();
		objectIds = new HashSet<>();
		projectileIds = new HashSet<>();
	}

	public Light(String description, Integer worldX, Integer worldY, Integer plane, Integer height, Alignment alignment, int radius, float strength, float[] color, LightType type, float duration, float range, Integer fadeInDuration, HashSet<Integer> npcIds, HashSet<Integer> objectIds, HashSet<Integer> projectileIds)
	{
		this.description = description;
		this.worldX = worldX;
		this.worldY = worldY;
		this.plane = plane;
		this.height = height;
		this.alignment = alignment;
		this.radius = radius;
		this.strength = strength;
		this.color = color;
		this.type = type;
		this.duration = duration;
		this.range = range;
		this.fadeInDuration = fadeInDuration;
		this.npcIds = npcIds == null ? new HashSet<>() : npcIds;
		this.objectIds = objectIds == null ? new HashSet<>() : objectIds;
		this.projectileIds = projectileIds == null ? new HashSet<>() : projectileIds;
	}

	public static class ProjectileJsonAdapter extends TypeAdapter<HashSet<Integer>>
	{
		@Override
		public HashSet<Integer> read(JsonReader in) throws IOException
		{
			return parseIDArray(in, null);
		}

		@Override
		public void write(JsonWriter out, HashSet<Integer> value) throws IOException
		{
			writeIDArray(out, value, null);
		}
	}

	@Override
	public boolean equals(Object obj)
	{
		if (!(obj instanceof Light))
		{
			return false;
		}

		Light other = (Light) obj;
		return other.description.equals(description) &&
			equal(other.worldX, worldX) &&
			equal(other.worldY, worldY) &&
			equal(other.plane, plane) &&
			equal(other.height, height) &&
			other.alignment == alignment &&
			other.radius == radius &&
			other.strength == strength &&
			Arrays.equals(other.color, color) &&
			other.type == type &&
			other.duration == duration &&
			other.range == range &&
			equal(other.fadeInDuration, fadeInDuration) &&
			other.npcIds.equals(npcIds) &&
			other.objectIds.equals(objectIds) &&
			other.projectileIds.equals(projectileIds);
	}

	@Override
	public int hashCode()
	{
		int hash = 1;
		hash = hash * 37 + description.hashCode();
		hash = hash * 37 + (worldX == null ? 0 : worldX);
		hash = hash * 37 + (worldY == null ? 0 : worldY);
		hash = hash * 37 + (plane == null ? 0 : plane);
		hash = hash * 37 + (height == null ? 0 : height);
		hash = hash * 37 + (alignment == null ? 0 : alignment.hashCode());
		hash = hash * 37 + radius;
		hash = hash * 37 + (int) strength;
		for (float f : color)
		{
			hash = hash * 37 + (int) f;
		}
		hash = hash * 37 + (type == null ? 0 : type.hashCode());
		hash = hash * 37 + (int) duration;
		hash = hash * 37 + (int) range;
		hash = hash * 37 + fadeInDuration;
		hash = hash * 37 + npcIds.hashCode();
		hash = hash * 37 + objectIds.hashCode();
		hash = hash * 37 + projectileIds.hashCode();
		return hash;
	}

	private static boolean equal(Integer a, Integer b)
	{
		if (a != null && b != null)
		{
			return a.equals(b);
		}
		return a == null && b == null;
	}
}
