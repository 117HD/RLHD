/*
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
 * Copyright (c) 2022, Hooder <ahooder@protonmail.com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package rs117.hd.data;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import rs117.hd.data.materials.Material;

import java.util.function.Consumer;

import static rs117.hd.utils.HDUtils.linearToSrgb;
import static rs117.hd.utils.HDUtils.rgb;

@Getter
@Accessors(fluent = true)
@NoArgsConstructor
@SuppressWarnings("NonFinalFieldInEnum")
public enum WaterType
{
	NONE,
	WATER,
	WATER_FLAT(b -> b
		.inheritFrom(WATER)
		.flat(true)),
	SWAMP_WATER(b -> b
		.specularStrength(.1f)
		.specularGloss(100)
		.normalStrength(.05f)
		.baseOpacity(.8f)
		.fresnelAmount(.3f)
		.surfaceColor(linearToSrgb(rgb(23, 33, 20)))
		.foamColor(linearToSrgb(rgb(115, 120, 101)))
		.depthColor(linearToSrgb(rgb(41, 82, 26)))
		.causticsStrength(0)
		.duration(1.2f)),
	SWAMP_WATER_FLAT(b -> b
		.inheritFrom(SWAMP_WATER)
		.flat(true)),
	POISON_WASTE(b -> b
		.specularStrength(.1f)
		.specularGloss(100)
		.normalStrength(.05f)
		.baseOpacity(.9f)
		.fresnelAmount(.3f)
		.surfaceColor(linearToSrgb(rgb(22, 23, 13)))
		.foamColor(linearToSrgb(rgb(106, 108, 100)))
		.depthColor(linearToSrgb(rgb(50, 52, 46)))
		.causticsStrength(0)
		.duration(1.6f)),
	POISON_WASTE_FLAT(b -> b
		.inheritFrom(POISON_WASTE)
		.flat(true)),
	BLOOD(b -> b
		.specularStrength(.5f)
		.specularGloss(500)
		.normalStrength(.05f)
		.baseOpacity(.8f)
		.fresnelAmount(.3f)
		.surfaceColor(linearToSrgb(rgb(38, 0, 0)))
		.foamColor(linearToSrgb(rgb(117, 63, 45)))
		.depthColor(linearToSrgb(rgb(50, 26, 22)))
		.causticsStrength(0)
		.duration(2)),
	ICE(b -> b
		.specularStrength(.3f)
		.specularGloss(200)
		.normalStrength(.04f)
		.baseOpacity(.85f)
		.fresnelAmount(1)
		.foamColor(linearToSrgb(rgb(150, 150, 150)))
		.depthColor(linearToSrgb(rgb(0, 117, 142)))
		.causticsStrength(.4f)
		.duration(0)
		.normalMap(Material.WATER_NORMAL_MAP_2)),
	ICE_FLAT(b -> b
		.inheritFrom(ICE)
		.flat(true));

	@Setter(AccessLevel.PRIVATE)
	private boolean flat = false;

	@Setter(AccessLevel.PRIVATE)
	private float specularStrength = .5f;

	@Setter(AccessLevel.PRIVATE)
	private float specularGloss = 500;

	@Setter(AccessLevel.PRIVATE)
	private float normalStrength = .09f;

	@Setter(AccessLevel.PRIVATE)
	private float baseOpacity = .5f;

	@Setter(AccessLevel.PRIVATE)
	private float fresnelAmount = 1;

	@Setter(AccessLevel.PRIVATE)
	private Material normalMap = Material.WATER_NORMAL_MAP_1;

	@Setter(AccessLevel.PRIVATE)
	private float[] surfaceColor = { 1, 1, 1 };

	@Setter(AccessLevel.PRIVATE)
	private float[] foamColor = linearToSrgb(rgb(176, 164, 146));

	@Setter(AccessLevel.PRIVATE)
	private float[] depthColor = linearToSrgb(rgb(0, 117, 142));

	@Setter(AccessLevel.PRIVATE)
	private float causticsStrength = 1;

	@Setter(AccessLevel.PRIVATE)
	private boolean hasFoam = true;

	@Setter(AccessLevel.PRIVATE)
	private float duration = 1;

	WaterType(Consumer<WaterType> consumer)
	{
		consumer.accept(this);
	}

	private WaterType inheritFrom(WaterType parent)
	{
		return this
			.flat(parent.flat)
			.specularStrength(parent.specularStrength)
			.specularGloss(parent.specularGloss)
			.normalStrength(parent.normalStrength)
			.baseOpacity(parent.baseOpacity)
			.fresnelAmount(parent.fresnelAmount)
			.surfaceColor(parent.surfaceColor)
			.foamColor(parent.foamColor)
			.hasFoam(parent.hasFoam)
			.duration(parent.duration)
			.normalMap(parent.normalMap);
	}
}
