package rs117.hd.scene.lights;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.data.NpcID;
import rs117.hd.data.ObjectID;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import static rs117.hd.utils.GsonUtils.parseIDArray;
import static rs117.hd.utils.GsonUtils.writeIDArray;

@Slf4j
@NoArgsConstructor // Called by GSON when parsing JSON
public class Light
{
	public String description;
	@Nullable
	public Integer worldX, worldY;
	public int plane;
	@NonNull
	public Alignment alignment = Alignment.CENTER;
	public int height;
	public int radius;
	public float strength;
	/**
	 * Linear color space RGBA in the range [0, 1]
	 */
	public float[] color;
	@NonNull
	public LightType type = LightType.STATIC;
	public float duration;
	public float range;
	public int fadeInDuration;
	@JsonAdapter(NpcID.JsonAdapter.class)
	public HashSet<Integer> npcIds = new HashSet<>();
	@JsonAdapter(ObjectID.JsonAdapter.class)
	public HashSet<Integer> objectIds = new HashSet<>();
	@JsonAdapter(IntegerSetAdapter.class)
	public HashSet<Integer> projectileIds = new HashSet<>();
	@JsonAdapter(IntegerSetAdapter.class)
	public HashSet<Integer> graphicsObjectIds = new HashSet<>();

	public Light(String description, int worldX, int worldY, int plane, Integer height, @NonNull Alignment alignment, int radius, float strength, float[] color, @NonNull LightType type, float duration, float range, Integer fadeInDuration, HashSet<Integer> npcIds, HashSet<Integer> objectIds, HashSet<Integer> projectileIds, HashSet<Integer> graphicsObjectIds)
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
		this.graphicsObjectIds = graphicsObjectIds == null ? new HashSet<>() : graphicsObjectIds;
	}

	public static class IntegerSetAdapter extends TypeAdapter<HashSet<Integer>>
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
			other.projectileIds.equals(projectileIds) &&
			other.graphicsObjectIds.equals(graphicsObjectIds);
	}

	@Override
	public int hashCode()
	{
		int hash = 1;
		hash = hash * 37 + description.hashCode();
		hash = hash * 37 + (worldX == null ? 0 : worldX);
		hash = hash * 37 + (worldY == null ? 0 : worldY);
		hash = hash * 37 + plane;
		hash = hash * 37 + height;
		hash = hash * 37 + alignment.hashCode();
		hash = hash * 37 + radius;
		hash = hash * 37 + (int) strength;
		for (float f : color)
			hash = hash * 37 + Float.floatToIntBits(f);
		hash = hash * 37 + type.hashCode();
		hash = hash * 37 + Float.floatToIntBits(duration);
		hash = hash * 37 + Float.floatToIntBits(range);
		hash = hash * 37 + fadeInDuration;
		hash = hash * 37 + npcIds.hashCode();
		hash = hash * 37 + objectIds.hashCode();
		hash = hash * 37 + projectileIds.hashCode();
		hash = hash * 37 + graphicsObjectIds.hashCode();
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
