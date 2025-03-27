package rs117.hd.data;

import java.util.function.Consumer;
import lombok.Setter;
import lombok.experimental.Accessors;
import rs117.hd.data.materials.Material;

import static rs117.hd.utils.ColorUtils.hsl;
import static rs117.hd.utils.ColorUtils.rgb;
import static rs117.hd.utils.ColorUtils.srgb;

public enum WaterType
{
	NONE,
	WATER,
	WATER_FLAT(WATER, true),
	SWAMP_WATER(b -> b
		.specularStrength(0.1f)
		.specularGloss(100)
		.normalStrength(0.05f)
		.baseOpacity(0.8f)
		.fresnelAmount(0.3f)
		.surfaceColor(srgb(23, 33, 20))
		.foamColor(srgb(115, 120, 101))
		.depthColor(srgb(41, 82, 26))
		.causticsStrength(0)
		.duration(1.2f)
		.fishingSpotRecolor(hsl("#04730d"))),
	SWAMP_WATER_FLAT(SWAMP_WATER, true),
	POISON_WASTE(b -> b
		.specularStrength(0.1f)
		.specularGloss(100)
		.normalStrength(0.05f)
		.baseOpacity(0.9f)
		.fresnelAmount(0.3f)
		.surfaceColor(srgb(22, 23, 13))
		.foamColor(srgb(106, 108, 100))
		.depthColor(srgb(50, 52, 46))
		.causticsStrength(0)
		.duration(1.6f)),
	BLACK_TAR_FLAT(b -> b
		.specularStrength(0.05f)
		.specularGloss(300)
		.normalStrength(0.05f)
		.baseOpacity(0.9f)
		.fresnelAmount(0.02f)
		.surfaceColor(rgb(38, 40, 43))
		.foamColor(rgb(0, 0, 0))
		.depthColor(rgb(38, 40, 43))
		.causticsStrength(0)
		.duration(1.6f)
		.flat(true)),
	BLOOD(b -> b
		.specularStrength(0.5f)
		.specularGloss(500)
		.normalStrength(0.05f)
		.baseOpacity(0.85f)
		.fresnelAmount(0)
		.surfaceColor(srgb(38, 0, 0))
		.foamColor(srgb(117, 63, 45))
		.depthColor(srgb(50, 26, 22))
		.causticsStrength(0)
		.duration(2)),
	ICE(b -> b
		.specularStrength(0.3f)
		.specularGloss(200)
		.normalStrength(0.04f)
		.baseOpacity(0.85f)
		.fresnelAmount(1)
		.foamColor(srgb(150, 150, 150))
		.depthColor(srgb(0, 117, 142))
		.causticsStrength(0.4f)
		.duration(0)
		.normalMap(Material.WATER_NORMAL_MAP_2)),
	ICE_FLAT(ICE, true),
	MUDDY_WATER(b -> b
		.specularStrength(0.1f)
		.specularGloss(100)
		.normalStrength(0.05f)
		.baseOpacity(0.7f)
		.fresnelAmount(0.3f)
		.surfaceColor(srgb(35, 10, 0))
		.foamColor(srgb(106, 108, 24))
		.depthColor(srgb(65, 23, 0))
		.causticsStrength(0)
		.duration(2.7f)),
	SCAR_SLUDGE(b -> b
		.specularStrength(0)
		.specularGloss(100)
		.normalStrength(0.05f)
		.baseOpacity(0.85f)
		.fresnelAmount(0.3f)
		.surfaceColor(srgb(0x26, 0x26, 0x23))
		.foamColor(srgb(0x69, 0x77, 0x5e))
		.depthColor(srgb(0x69, 0x77, 0x5e))
		.causticsStrength(0)
		.duration(1.2f)),
	ABYSS_BILE(b -> b
		.specularStrength(0.2f)
		.specularGloss(100)
		.normalStrength(0.08f)
		.baseOpacity(0.85f)
		.fresnelAmount(0.3f)
		.surfaceColor(rgb(120, 91, 0))
		.foamColor(rgb(120, 81, 0))
		.depthColor(rgb(120, 59, 0))
		.causticsStrength(0.4f)
		.duration(2.2f)),
	PLAIN_WATER(b -> b
		.depthColor(rgb(0, 0, 0))
		.foamColor(rgb(64, 64, 64))
		.causticsStrength(0)
		.flat(true)),
	DARK_BLUE_WATER(b -> b
		.specularStrength(0.1f)
		.specularGloss(100)
		.normalStrength(0.1f)
		.baseOpacity(0.8f)
		.fresnelAmount(0.2f)
		.surfaceColor(rgb("#07292f"))
		.foamColor(rgb(64, 64, 64))
		.depthColor(rgb("#000000"))
		.causticsStrength(0)
		.flat(true));

	public final boolean flat;
	public final float specularStrength;
	public final float specularGloss;
	public final float normalStrength;
	public final float baseOpacity;
	public final float fresnelAmount;
	public final Material normalMap;
	public final float[] surfaceColor;
	public final float[] foamColor;
	public final float[] depthColor;
	public final float causticsStrength;
	public final boolean hasFoam;
	public final float duration;
	public final int fishingSpotRecolor;

	@Setter
	@Accessors(fluent = true)
	private static class Builder
	{
		private boolean flat = false;
		private float specularStrength = 0.5f;
		private float specularGloss = 500;
		private float normalStrength = 0.09f;
		private float baseOpacity = 0.5f;
		private float fresnelAmount = 1;
		private Material normalMap = Material.WATER_NORMAL_MAP_1;
		private float[] surfaceColor = { 1, 1, 1 };
		private float[] foamColor = srgb(176, 164, 146);
		private float[] depthColor = srgb(0, 117, 142);
		private float causticsStrength = 1;
		private boolean hasFoam = true;
		private float duration = 1;
		private int fishingSpotRecolor = -1;

		// Added: Extracted method for setting surface properties
		public Builder setSurfaceProperties(float specStrength, float specGloss, float normalStrength,
			float baseOpacity, float fresnelAmount, float[] surfaceColor,
			float[] foamColor, float[] depthColor, float causticsStrength, float duration) {
			this.specularStrength = specStrength;
			this.specularGloss = specGloss;
			this.normalStrength = normalStrength;
			this.baseOpacity = baseOpacity;
			this.fresnelAmount = fresnelAmount;
			this.surfaceColor = surfaceColor;
			this.foamColor = foamColor;
			this.depthColor = depthColor;
			this.causticsStrength = causticsStrength;
			this.duration = duration;
			return this;
		}
	}

	WaterType()
	{
		this(builder -> {});
	}

	WaterType(Consumer<Builder> consumer)
	{
		Builder builder = new Builder();
		consumer.accept(builder);
		this.flat = builder.flat;
		this.specularStrength = builder.specularStrength;
		this.specularGloss = builder.specularGloss;
		this.normalStrength = builder.normalStrength;
		this.baseOpacity = builder.baseOpacity;
		this.fresnelAmount = builder.fresnelAmount;
		this.normalMap = builder.normalMap;
		this.surfaceColor = builder.surfaceColor;
		this.foamColor = builder.foamColor;
		this.depthColor = builder.depthColor;
		this.causticsStrength = builder.causticsStrength;
		this.hasFoam = builder.hasFoam;
		this.duration = builder.duration;
		this.fishingSpotRecolor = builder.fishingSpotRecolor;
	}

	WaterType(WaterType parent, boolean flat)
	{
		this.flat = flat;
		this.specularStrength = parent.specularStrength;
		this.specularGloss = parent.specularGloss;
		this.normalStrength = parent.normalStrength;
		this.baseOpacity = parent.baseOpacity;
		this.fresnelAmount = parent.fresnelAmount;
		this.normalMap = parent.normalMap;
		this.surfaceColor = parent.surfaceColor;
		this.foamColor = parent.foamColor;
		this.depthColor = parent.depthColor;
		this.causticsStrength = parent.causticsStrength;
		this.hasFoam = parent.hasFoam;
		this.duration = parent.duration;
		this.fishingSpotRecolor = parent.fishingSpotRecolor;
	}
}
