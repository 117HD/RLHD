package rs117.hd.scene.lights;

import com.google.gson.annotations.JsonAdapter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.data.NpcID;
import rs117.hd.data.ObjectID;
import rs117.hd.utils.GsonUtils;

@Slf4j
@NoArgsConstructor // Called by GSON when parsing JSON
@AllArgsConstructor
public class Light
{
	public String description;
	@Nullable
	public Integer worldX, worldY;
	public int plane;
	@Nonnull
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
	@JsonAdapter(GsonUtils.IntegerSetAdapter.class)
	public HashSet<Integer> projectileIds = new HashSet<>();
	@JsonAdapter(GsonUtils.IntegerSetAdapter.class)
	public HashSet<Integer> graphicsObjectIds = new HashSet<>();

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof Light)) {
			return false;
		}

		Light other = (Light) obj;
		return
			other.description.equals(description) &&
			Objects.equals(other.worldX, worldX) &&
			Objects.equals(other.worldY, worldY) &&
			other.plane == plane &&
			other.height == height &&
			other.alignment == alignment &&
			other.radius == radius &&
			other.strength == strength &&
			Arrays.equals(other.color, color) &&
			other.type == type &&
			other.duration == duration &&
			other.range == range &&
			other.fadeInDuration == fadeInDuration &&
			other.npcIds.equals(npcIds) &&
			other.objectIds.equals(objectIds) &&
			other.projectileIds.equals(projectileIds) &&
			other.graphicsObjectIds.equals(graphicsObjectIds);
	}

	@Override
	public int hashCode()
	{
		int hash = description.hashCode();
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
}
