/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles.core;

import static net.runelite.api.Constants.EXTENDED_SCENE_SIZE;
import static net.runelite.api.Perspective.LOCAL_TILE_SIZE;

import rs117.hd.scene.particles.core.buffer.ParticleBuffer;
import rs117.hd.scene.particles.definition.ParticleDefinition;
import rs117.hd.scene.particles.emitter.ParticleEmitter;

/**
 * Advances a single particle. Returns true if it should be removed.
 */
public final class MovingParticle {

	private static int clampColour16(int value) {
		if (value < 0) return 0;
		if (value > 65535) return 65535;
		return value;
	}

	public static boolean tick(ParticleBuffer buf, int i, int tickDelta) {
		buf.remainingTicks[i] -= tickDelta;
		if (buf.remainingTicks[i] <= 0) {
			return true;
		}
		int posX = (int) (buf.xFixed[i] >> 12);
		int posY = (int) (buf.yFixed[i] >> 12);
		int posZ = (int) (buf.zFixed[i] >> 12);
		ParticleEmitter emitter = buf.emitter[i];
		ParticleDefinition config = emitter != null ? emitter.getDefinition() : null;

		if (config != null && config.colours.targetColourArgb != 0) {
			int elapsed = buf.lifetimeTicks[i] - buf.remainingTicks[i];
			if (elapsed <= config.colourTransitionTicks) {
				int red16 = (buf.colourArgbRef[i] >> 8 & 0xff00) + (buf.colourRgbLowRef[i] >> 16 & 0xff) + config.redIncrementPerTick * tickDelta;
				int green16 = (buf.colourArgbRef[i] & 0xff00) + (buf.colourRgbLowRef[i] >> 8 & 0xff) + config.greenIncrementPerTick * tickDelta;
				int blue16 = (buf.colourArgbRef[i] << 8 & 0xff00) + (buf.colourRgbLowRef[i] & 0xff) + config.blueIncrementPerTick * tickDelta;
				red16 = clampColour16(red16);
				green16 = clampColour16(green16);
				blue16 = clampColour16(blue16);
				buf.colourArgbRef[i] &= ~0xffffff;
				buf.colourArgbRef[i] |= ((red16 & 0xff00) << 8) + (green16 & 0xff00) + ((blue16 & 0xff00) >> 8);
				buf.colourRgbLowRef[i] &= ~0xffffff;
				buf.colourRgbLowRef[i] |= ((red16 & 0xff) << 16) + ((green16 & 0xff) << 8) + (blue16 & 0xff);
			}
			if (elapsed <= config.alphaTransitionTicks) {
				int alpha16 = (buf.colourArgbRef[i] >> 16 & 0xff00) + (buf.colourRgbLowRef[i] >> 24 & 0xff) + config.alphaIncrementPerTick * tickDelta;
				alpha16 = clampColour16(alpha16);
				buf.colourArgbRef[i] &= 0xffffff;
				buf.colourArgbRef[i] |= (alpha16 & 0xff00) << 16;
				buf.colourRgbLowRef[i] &= 0xffffff;
				buf.colourRgbLowRef[i] |= (alpha16 & 0xff) << 24;
			}
		}
		if (config != null && config.speed.targetSpeed >= 0 && buf.lifetimeTicks[i] - buf.remainingTicks[i] <= config.speedTransitionTicks) {
			buf.speedRef[i] += config.speedIncrementPerTick * tickDelta;
		}
		if (config != null && config.scale.targetScale >= 0) {
			int elapsed = buf.lifetimeTicks[i] - buf.remainingTicks[i];
			if (elapsed <= config.scaleTransitionTicks) {
				int ticksRemaining = config.scaleTransitionTicks - elapsed;
				int targetRef = config.targetScaleRef;
				int delta = targetRef - buf.scaleRef[i];
				if (ticksRemaining > 0 && delta != 0) {
					int step = (int) Math.round((double) delta * tickDelta / ticksRemaining);
					buf.scaleRef[i] += step;
					if (delta > 0 && buf.scaleRef[i] > targetRef) buf.scaleRef[i] = targetRef;
					else if (delta < 0 && buf.scaleRef[i] < targetRef) buf.scaleRef[i] = targetRef;
				}
			}
		}

		if (config != null && config.physics.distanceFalloffType == 1) {
			int dx = posX - (int) buf.emitterOriginX[i];
			int dy = posY - (int) buf.emitterOriginY[i];
			int dz = posZ - (int) buf.emitterOriginZ[i];
			int dist = (int) Math.sqrt((double) (dx * dx + dy * dy + dz * dz)) >> 2;
			long falloff = (long) (config.physics.distanceFalloffStrength * dist * tickDelta);
			buf.speedRef[i] -= (long) buf.speedRef[i] * falloff >> 18;
		} else if (config != null && config.physics.distanceFalloffType == 2) {
			int dx = posX - (int) buf.emitterOriginX[i];
			int dy = posY - (int) buf.emitterOriginY[i];
			int dz = posZ - (int) buf.emitterOriginZ[i];
			int distSq = dx * dx + dy * dy + dz * dz;
			long falloff = (long) (config.physics.distanceFalloffStrength * distSq * tickDelta);
			buf.speedRef[i] -= (long) buf.speedRef[i] * falloff >> 28;
		}


		buf.xFixed[i] += ((long) buf.velocityX[i] * (long) (buf.speedRef[i] << 2) >> 23) * (long) tickDelta;
		buf.yFixed[i] += ((long) buf.velocityY[i] * (long) (buf.speedRef[i] << 2) >> 23) * (long) tickDelta;
		buf.zFixed[i] += ((long) buf.velocityZ[i] * (long) (buf.speedRef[i] << 2) >> 23) * (long) tickDelta;

		int east = (int) (buf.xFixed[i] >> 12);
		int north = (int) (buf.zFixed[i] >> 12);
		int height = (int) (buf.yFixed[i] >> 12);
		int sceneSizeXZ = EXTENDED_SCENE_SIZE * LOCAL_TILE_SIZE;
		if (east < 0 || east >= sceneSizeXZ || north < 0 || north >= sceneSizeXZ
			|| height < -262144 || height > 262144) {
			return true;
		}
		return false;
	}
}
