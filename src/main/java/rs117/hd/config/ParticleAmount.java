/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ParticleAmount
{
	NONE("Disabled", 0),
	FEW("Few", 1536),
	SOME("Some", 4096),
	MANY("Many", ParticleAmount.MAX_BUFFER_CAPACITY);

	public static final int MAX_BUFFER_CAPACITY = 6096;

	private final String name;
	private final int maxParticles;

	public boolean isEnabled()
	{
		return maxParticles > 0;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
