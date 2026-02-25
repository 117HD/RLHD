/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles;

import rs117.hd.scene.particles.emitter.ParticleEmitter;

/**
 * Per-particle update 1:1 with reference EmittedParticle.tick (rs-client-main).
 * Level bounds / validateAndEnqueueForRender are done elsewhere; not included here.
 */
public final class EmittedParticle {

	private static int clampColour16(int value) {
		if (value < 0) return 0;
		if (value > 65535) return 65535;
		return value;
	}

	/**
	 * Advances particle at buffer slot i by tickDelta ticks. Reference EmittedParticle.tick 1:1.
	 * Returns true if the particle should be removed (remainingTicks <= 0).
	 * Uses ref state (xFixed, velocityX/Y/Z, speedRef, remainingTicks, colourArgbRef, colourRgbLowRef, scaleRef).
	 * After tick, call buf.syncRefToFloat(i) so float state is updated for rendering.
	 */
	public static boolean tick(ParticleBuffer buf, int i, int tickDelta, ParticleManager manager) {
		if (buf.emitter[i] != null && manager.getEmittersCulledThisFrame().contains(buf.emitter[i])) {
			return true;
		}
		buf.remainingTicks[i] -= tickDelta;
		if (buf.remainingTicks[i] <= 0) {
			return true;
		}
		int posX = (int) (buf.xFixed[i] >> 12);
		int posY = (int) (buf.yFixed[i] >> 12);
		int posZ = (int) (buf.zFixed[i] >> 12);
		ParticleEmitter emitter = buf.emitter[i];
		ParticleDefinition config = emitter != null ? emitter.getDefinition() : null;

		if (config != null && config.targetColourArgb != 0) {
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
		if (config != null && config.targetSpeed >= 0f && buf.lifetimeTicks[i] - buf.remainingTicks[i] <= config.speedTransitionTicks) {
			buf.speedRef[i] += config.speedIncrementPerTick * tickDelta;
		}
		if (config != null && config.targetScale >= 0f) {
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

		if (config != null && config.distanceFalloffType == 1) {
			int dx = posX - (int) buf.emitterOriginX[i];
			int dy = posY - (int) buf.emitterOriginY[i];
			int dz = posZ - (int) buf.emitterOriginZ[i];
			int dist = (int) Math.sqrt((double) (dx * dx + dy * dy + dz * dz)) >> 2;
			long falloff = (long) (config.distanceFalloffStrength * dist * tickDelta);
			buf.speedRef[i] -= (long) buf.speedRef[i] * falloff >> 18;
		} else if (config != null && config.distanceFalloffType == 2) {
			int dx = posX - (int) buf.emitterOriginX[i];
			int dy = posY - (int) buf.emitterOriginY[i];
			int dz = posZ - (int) buf.emitterOriginZ[i];
			int distSq = dx * dx + dy * dy + dz * dz;
			long falloff = (long) (config.distanceFalloffStrength * distSq * tickDelta);
			buf.speedRef[i] -= (long) buf.speedRef[i] * falloff >> 28;
		}


		buf.xFixed[i] += ((long) buf.velocityX[i] * (long) (buf.speedRef[i] << 2) >> 23) * (long) tickDelta;
		buf.yFixed[i] += ((long) buf.velocityY[i] * (long) (buf.speedRef[i] << 2) >> 23) * (long) tickDelta;
		buf.zFixed[i] += ((long) buf.velocityZ[i] * (long) (buf.speedRef[i] << 2) >> 23) * (long) tickDelta;

		return false;
	}
}
