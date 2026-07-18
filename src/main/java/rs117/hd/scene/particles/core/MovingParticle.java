/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles.core;

import static net.runelite.api.Constants.EXTENDED_SCENE_SIZE;
import static net.runelite.api.Perspective.LOCAL_TILE_SIZE;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nullable;
import rs117.hd.scene.particles.core.buffer.ParticleBuffer;
import rs117.hd.scene.particles.definition.ParticleDefinition;
import rs117.hd.scene.particles.emitter.ParticleEmitter;
import rs117.hd.scene.particles.effector.ActiveEffectorState;
import rs117.hd.scene.particles.effector.EffectorDefinition;
import rs117.hd.scene.particles.effector.EffectorDefinitionManager;
import rs117.hd.scene.particles.effector.EffectorEffectSpec;

/**
 * Advances a single particle. Returns true if it should be removed.
 */
public final class MovingParticle {

	private static final double[] VELOCITY_SCRATCH = new double[3];
	private static final int HELIX_LATCHED_FLAG = 4;
	private static final int HELIX_T_SHIFT = 3;
	private static final int HELIX_T_MASK = 0xFFFF;

	private static int clampColour16(int value) {
		if (value < 0) return 0;
		if (value > 65535) return 65535;
		return value;
	}

	public static boolean tick(
		ParticleBuffer buf,
		int i,
		int tickDelta,
		Map<String, List<ActiveEffectorState>> activeEffectorsById,
		EffectorDefinitionManager effectorDefinitions
	) {
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
		} else 		if (config != null && config.physics.distanceFalloffType == 2) {
			int dx = posX - (int) buf.emitterOriginX[i];
			int dy = posY - (int) buf.emitterOriginY[i];
			int dz = posZ - (int) buf.emitterOriginZ[i];
			int distSq = dx * dx + dy * dy + dz * dz;
			long falloff = (long) (config.physics.distanceFalloffStrength * distSq * tickDelta);
			buf.speedRef[i] -= (long) buf.speedRef[i] * falloff >> 28;
		}

		double[] v = VELOCITY_SCRATCH;
		v[0] = buf.velocityX[i];
		v[1] = buf.velocityY[i];
		v[2] = buf.velocityZ[i];
		boolean velocityUpdated = false;
		boolean pathGuided = false;
		boolean despawn = false;

		if (emitter != null && activeEffectorsById != null && !activeEffectorsById.isEmpty()) {
			List<String> localFilter = emitter.getLocalEffectorFilter();
			if (localFilter != null && !localFilter.isEmpty()) {
				for (String effectorId : localFilter) {
					List<ActiveEffectorState> states = activeEffectorsById.get(effectorId);
					if (states == null) continue;
					for (ActiveEffectorState state : states) {
						EffectorDefinition def = state.getDef();
						if (def == null || def.scope == 1) continue;
						int flags = applyWorldEffector(buf, i, tickDelta, posX, posY, posZ, state, def, v);
						velocityUpdated |= (flags & EFFECT_VELOCITY) != 0;
						pathGuided |= (flags & EFFECT_PATH_GUIDED) != 0;
						despawn |= (flags & EFFECT_DESPAWN) != 0;
					}
				}
			}

			List<String> globalIds = emitter.getGlobalEffectors();
			if (globalIds != null && !globalIds.isEmpty()) {
				for (String effectorId : globalIds) {
					List<ActiveEffectorState> states = activeEffectorsById.get(effectorId);
					if (states == null) continue;
					for (ActiveEffectorState state : states) {
						EffectorDefinition def = state.getDef();
						if (def == null) continue;
						int flags = applyWorldEffector(buf, i, tickDelta, posX, posY, posZ, state, def, v);
						velocityUpdated |= (flags & EFFECT_VELOCITY) != 0;
						pathGuided |= (flags & EFFECT_PATH_GUIDED) != 0;
						despawn |= (flags & EFFECT_DESPAWN) != 0;
					}
				}
			}

			List<String> embeddedIds = emitter.getEmbeddedEffectors();
			if (embeddedIds != null && !embeddedIds.isEmpty()) {
				for (String embeddedId : embeddedIds) {
					EffectorDefinition def = effectorDefinitions.getDefinition(embeddedId);
					if (def == null) continue;
					for (EffectorEffectSpec effect : def.effects) {
						velocityUpdated |= applyEmbeddedEffect(buf, i, tickDelta, effect, v);
					}
				}
			}
		}

		if (despawn) {
			return true;
		}

		if (velocityUpdated) {
			while (v[0] > 32767.0 || v[1] > 32767.0 || v[2] > 32767.0 || v[0] < -32767.0 || v[1] < -32767.0 || v[2] < -32767.0) {
				v[0] /= 2.0;
				v[1] /= 2.0;
				v[2] /= 2.0;
			}
			buf.velocityX[i] = (short) (int) v[0];
			buf.velocityY[i] = (short) (int) v[1];
			buf.velocityZ[i] = (short) (int) v[2];
		}

		if (config != null && config.general.randomYawRotation > 0f) {
			float maxDelta = config.general.randomYawRotation * (tickDelta / 50f);
			buf.yaw[i] += (ThreadLocalRandom.current().nextFloat() * 2f - 1f) * maxDelta;
		}

		if (!pathGuided) {
			buf.xFixed[i] += ((long) buf.velocityX[i] * (long) (buf.speedRef[i] << 2) >> 23) * (long) tickDelta;
			buf.yFixed[i] += ((long) buf.velocityY[i] * (long) (buf.speedRef[i] << 2) >> 23) * (long) tickDelta;
			buf.zFixed[i] += ((long) buf.velocityZ[i] * (long) (buf.speedRef[i] << 2) >> 23) * (long) tickDelta;
		}

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

	private static final int EFFECT_VELOCITY = 1;
	private static final int EFFECT_PATH_GUIDED = 2;
	private static final int EFFECT_DESPAWN = 4;

	private static int applyWorldEffector(
		ParticleBuffer buf,
		int i,
		int tickDelta,
		int posX,
		int posY,
		int posZ,
		ActiveEffectorState state,
		EffectorDefinition def,
		double[] v
	) {
		double dx = posX - state.getX();
		double dy = posY - state.getY();
		double dz = posZ - state.getZ();
		double zoneRadius = def.maxRangeSq < Long.MAX_VALUE / 2L ? Math.sqrt(def.maxRangeSq) : 0.0;
		boolean whirlpoolZone = false;
		for (EffectorEffectSpec effect : def.effects) {
			if (effect.whirlpool) {
				whirlpoolZone = true;
				break;
			}
		}

		if (whirlpoolZone) {
			if (zoneRadius <= 0.0) {
				return 0;
			}
			double horizSq = dx * dx + dz * dz;
			if (horizSq > def.maxRangeSq) {
				clearHelixFlags(buf, i);
				return 0;
			}
			double skyY = state.getY() - zoneRadius * 0.85;
			double groundY = state.getY() + zoneRadius * 0.85;
			if (posY < skyY - zoneRadius * 0.35 || posY > groundY + zoneRadius * 0.15) {
				clearHelixFlags(buf, i);
				return 0;
			}
		} else {
			double distSq = dx * dx + dy * dy + dz * dz;
			if (distSq > def.maxRangeSq) {
				return 0;
			}
		}

		double distSq = dx * dx + dy * dy + dz * dz;
		double dist = Math.sqrt(distSq);
		if (dist == 0.0) {
			dist = 1.0;
		}

		int flags = 0;
		EffectorEffectSpec whirlpoolEffect = findWhirlpoolEffect(def);
		boolean whirlpoolGuides = whirlpoolEffect != null && !isImmuneToEffect(i, whirlpoolEffect);
		for (EffectorEffectSpec effect : def.effects) {
			if (whirlpoolGuides && !effect.whirlpool) {
				continue;
			}
			flags |= applyEffect(buf, i, tickDelta, dx, dy, dz, dist, zoneRadius, effect, v,
				state.getX(), state.getY(), state.getZ());
		}
		if (whirlpoolEffect != null && !whirlpoolGuides && zoneRadius > 0.0) {
			double coreRadius = zoneRadius * 0.12;
			if (distSq <= coreRadius * coreRadius) {
				flags |= EFFECT_DESPAWN;
			}
		}
		return flags;
	}

	@Nullable
	private static EffectorEffectSpec findWhirlpoolEffect(EffectorDefinition def) {
		for (EffectorEffectSpec effect : def.effects) {
			if (effect.whirlpool) {
				return effect;
			}
		}
		return null;
	}

	private static boolean applyEmbeddedEffect(
		ParticleBuffer buf,
		int i,
		int tickDelta,
		EffectorEffectSpec effect,
		double[] v
	) {
		if (effect.signedMagnitude == 0 || effect.radial || isImmuneToEffect(i, effect)) {
			return false;
		}
		double strengthScale = effectStrengthScale(effect);
		if (strengthScale <= 0.0) {
			return false;
		}
		double intensity = effect.intensity > 0f ? effect.intensity : 1.0;
		if (!effect.positionMode) {
			v[0] += effect.forceDirectionX * intensity * strengthScale * tickDelta;
			v[1] += effect.forceDirectionY * intensity * strengthScale * tickDelta;
			v[2] += effect.forceDirectionZ * intensity * strengthScale * tickDelta;
		} else {
			buf.xFixed[i] += (long) (effect.forceDirectionX * intensity * strengthScale * tickDelta);
			buf.yFixed[i] += (long) (effect.forceDirectionY * intensity * strengthScale * tickDelta);
			buf.zFixed[i] += (long) (effect.forceDirectionZ * intensity * strengthScale * tickDelta);
		}
		return true;
	}

	private static int applyEffect(
		ParticleBuffer buf,
		int i,
		int tickDelta,
		double dx,
		double dy,
		double dz,
		double dist,
		double zoneRadius,
		EffectorEffectSpec effect,
		double[] v,
		float centerX,
		float centerY,
		float centerZ
	) {
		if (isImmuneToEffect(i, effect)) {
			return 0;
		}
		if (effect.type != EffectorEffectSpec.EffectType.WIND && effect.signedMagnitude == 0) {
			return 0;
		}
		if (effect.type == EffectorEffectSpec.EffectType.WIND && !effect.hasWindDirection) {
			return 0;
		}

		double strengthScale = effectStrengthScale(effect);
		if (strengthScale <= 0.0) {
			return 0;
		}

		if (!effect.radial && !effect.whirlpool && effect.type == EffectorEffectSpec.EffectType.PUSH) {
			double dot = (dx * effect.forceDirectionX + dy * effect.forceDirectionY + dz * effect.forceDirectionZ)
				* 65535.0 / ((double) Math.abs(effect.signedMagnitude) * dist);
			if (dot < effect.coneAngleCosine) {
				return 0;
			}
		}

		double intensity = !effect.radial && !effect.whirlpool && effect.intensity > 0f ? effect.intensity : 1.0;

		if (effect.whirlpool) {
			boolean complete = applyWhirlpool(buf, i, tickDelta, dx, dy, dz, zoneRadius, effect, strengthScale, v, centerX, centerY, centerZ);
			if (effect.hasTargetColor) {
				boolean latched = (buf.flags[i] & HELIX_LATCHED_FLAG) != 0;
				double pathT = latched ? readHelixT(buf.flags[i]) : 0.0;
				applyEffectColor(buf, i, effect, pathT * strengthScale, tickDelta);
			}
			int flags = EFFECT_VELOCITY | EFFECT_PATH_GUIDED;
			if (complete) {
				flags |= EFFECT_DESPAWN;
			}
			return flags;
		} else if (effect.type == EffectorEffectSpec.EffectType.WIND) {
			return applyWindEffect(buf, i, tickDelta, dist, zoneRadius, effect, intensity, strengthScale, v);
		} else if (!effect.radial) {
			if (!effect.positionMode) {
				v[0] += effect.forceDirectionX * intensity * strengthScale * tickDelta;
				v[1] += effect.forceDirectionY * intensity * strengthScale * tickDelta;
				v[2] += effect.forceDirectionZ * intensity * strengthScale * tickDelta;
			} else {
				buf.xFixed[i] += (long) (effect.forceDirectionX * intensity * strengthScale * tickDelta);
				buf.yFixed[i] += (long) (effect.forceDirectionY * intensity * strengthScale * tickDelta);
				buf.zFixed[i] += (long) (effect.forceDirectionZ * intensity * strengthScale * tickDelta);
			}
			applyEffectColor(buf, i, effect, strengthScale, tickDelta);
		} else {
			boolean repel = effect.type == EffectorEffectSpec.EffectType.REPEL;
			boolean attract = effect.type == EffectorEffectSpec.EffectType.ATTRACT;
			boolean skyFunnel = attract && dy < 0;
			boolean horizontalRadial = repel || skyFunnel;

			double horizDist = Math.sqrt(dx * dx + dz * dz);
			double radialDist = horizontalRadial ? horizDist : dist;
			if (radialDist < 1.0) {
				radialDist = 1.0;
			}

			double forceScale = radialForceScale(effect, radialDist, zoneRadius);
			if (repel) {
				forceScale *= repelHeightFalloff(dy, zoneRadius);
				if (forceScale <= 0.0) {
					return 0;
				}
			} else if (skyFunnel) {
				forceScale *= skyFunnelStrength(horizDist, zoneRadius);
			}

			double magnitude = effect.signedMagnitude * forceScale * strengthScale;
			double rx;
			double ry;
			double rz;
			if (repel && horizDist < 64.0) {
				double blastAngle = stableParticleAngle(i);
				rx = Math.cos(blastAngle) * magnitude;
				ry = 0.0;
				rz = Math.sin(blastAngle) * magnitude;
			} else if (horizontalRadial) {
				rx = dx / radialDist * magnitude;
				ry = 0.0;
				rz = dz / radialDist * magnitude;
			} else {
				rx = dx / dist * magnitude;
				ry = dy / dist * magnitude;
				rz = dz / dist * magnitude;
			}
			if (!effect.positionMode) {
				v[0] += rx * tickDelta;
				v[1] += ry * tickDelta;
				v[2] += rz * tickDelta;
			} else {
				buf.xFixed[i] += (long) (rx * tickDelta);
				buf.yFixed[i] += (long) (ry * tickDelta);
				buf.zFixed[i] += (long) (rz * tickDelta);
			}
			applyEffectColor(buf, i, effect, forceScale * strengthScale, tickDelta);
		}
		return EFFECT_VELOCITY;
	}

	private static void applyEffectColor(
		ParticleBuffer buf,
		int i,
		EffectorEffectSpec effect,
		double weight,
		int tickDelta
	) {
		if (!effect.hasTargetColor || weight <= 0.0) {
			return;
		}

		double blend;
		if (effect.resolvedColorBlend == EffectorEffectSpec.ColorBlendMode.PATH) {
			blend = Math.min(1.0, weight);
		} else {
			blend = weight * (1.0 - Math.exp(-6.0 * tickDelta / 50.0));
			blend = Math.min(1.0, blend);
		}
		if (blend <= 0.0) {
			return;
		}

		int red16 = (buf.colourArgbRef[i] >> 8 & 0xff00) + (buf.colourRgbLowRef[i] >> 16 & 0xff);
		int green16 = (buf.colourArgbRef[i] & 0xff00) + (buf.colourRgbLowRef[i] >> 8 & 0xff);
		int blue16 = (buf.colourArgbRef[i] << 8 & 0xff00) + (buf.colourRgbLowRef[i] & 0xff);
		int alpha16 = (buf.colourArgbRef[i] >> 16 & 0xff00) + (buf.colourRgbLowRef[i] >> 24 & 0xff);
		red16 += (int) ((effect.targetRed16 - red16) * blend);
		green16 += (int) ((effect.targetGreen16 - green16) * blend);
		blue16 += (int) ((effect.targetBlue16 - blue16) * blend);
		alpha16 += (int) ((effect.targetAlpha16 - alpha16) * blend);
		red16 = clampColour16(red16);
		green16 = clampColour16(green16);
		blue16 = clampColour16(blue16);
		alpha16 = clampColour16(alpha16);
		buf.colourArgbRef[i] &= ~0xffffff;
		buf.colourArgbRef[i] |= ((red16 & 0xff00) << 8) + (green16 & 0xff00) + ((blue16 & 0xff00) >> 8);
		buf.colourArgbRef[i] &= 0xffffff;
		buf.colourArgbRef[i] |= (alpha16 & 0xff00) << 16;
		buf.colourRgbLowRef[i] &= ~0xffffff;
		buf.colourRgbLowRef[i] |= ((red16 & 0xff) << 16) + ((green16 & 0xff) << 8) + (blue16 & 0xff);
		buf.colourRgbLowRef[i] &= 0xffffff;
		buf.colourRgbLowRef[i] |= (alpha16 & 0xff) << 24;
	}

	private static int applyWindEffect(
		ParticleBuffer buf,
		int i,
		int tickDelta,
		double dist,
		double zoneRadius,
		EffectorEffectSpec effect,
		double intensity,
		double strengthScale,
		double[] v
	) {
		if (zoneRadius > 0.0 && dist > zoneRadius) {
			return 0;
		}

		double zoneWeight = 1.0;
		if (zoneRadius > 0.0 && effect.edgeFalloff) {
			double depth = 1.0 - dist / zoneRadius;
			if (depth <= 0.0) {
				return 0;
			}
			float power = effect.falloffPower > 0f ? effect.falloffPower : 1f;
			zoneWeight = Math.pow(depth, power);
		}

		double fx = effect.windDirX;
		double fy = effect.windDirY;
		double fz = effect.windDirZ;

		double variance = effect.directionVarianceScale;
		if (variance > 0.0) {
			double yaw = stableParticleAngle(i);
			double pitch = (stableParticleUnit(i, 31) - 0.5) * variance * Math.PI * 0.45;
			double spreadX = Math.cos(yaw) * variance;
			double spreadZ = Math.sin(yaw) * variance;
			fx += spreadX;
			fy += pitch;
			fz += spreadZ;
			double len = Math.sqrt(fx * fx + fy * fy + fz * fz);
			if (len > 1e-6) {
				fx /= len;
				fy /= len;
				fz /= len;
			}
		}

		double turb = effect.turbulenceScale;
		if (turb > 0.0) {
			double phase = (buf.lifetimeTicks[i] - buf.remainingTicks[i] + i * 13) * 0.11;
			fx += Math.sin(phase * 1.7) * turb * 0.22;
			fy += Math.cos(phase * 2.3) * turb * 0.12;
			fz += Math.sin(phase * 1.1) * turb * 0.22;
			double len = Math.sqrt(fx * fx + fy * fy + fz * fz);
			if (len > 1e-6) {
				fx /= len;
				fy /= len;
				fz /= len;
			}
		}

		double targetSpeed = 32767.0 * effect.speed * intensity * strengthScale * zoneWeight;
		double targetVx = fx * targetSpeed;
		double targetVy = fy * targetSpeed;
		double targetVz = fz * targetSpeed;

		double responsiveness = 6.0 * effect.speed * intensity * strengthScale;
		double blend = 1.0 - Math.exp(-responsiveness * tickDelta / 50.0);
		blend = Math.min(0.95, blend * zoneWeight);

		v[0] += (targetVx - v[0]) * blend;
		v[1] += (targetVy - v[1]) * blend;
		v[2] += (targetVz - v[2]) * blend;
		applyEffectColor(buf, i, effect, zoneWeight * strengthScale, tickDelta);
		return EFFECT_VELOCITY;
	}

	private static boolean applyWhirlpool(
		ParticleBuffer buf,
		int i,
		int tickDelta,
		double dx,
		double dy,
		double dz,
		double zoneRadius,
		EffectorEffectSpec effect,
		double strengthScale,
		double[] v,
		float centerX,
		float centerY,
		float centerZ
	) {
		if (zoneRadius <= 0.0) {
			return false;
		}

		double activity = strengthScale;
		double spinSign = effect.clockwise ? -1.0 : 1.0;
		boolean inverted = effect.inverted;

		double topOffset = zoneRadius * 0.85;
		double skyY = centerY - topOffset;
		double groundY = centerY + topOffset;
		double helixSpan = groundY - skyY;

		int coilIndex = Math.floorMod(i * 0x9E3779B9, 3);
		double phase = coilIndex * (Math.PI * 2.0 / 3.0);
		double entryGeomT = inverted ? 1.0 : 0.0;
		double entryAngle = phase + entryGeomT * Math.PI * 3.0 * spinSign;
		double entryRingR = zoneRadius * 0.65 * (1.0 - entryGeomT * 0.35);
		double[] entryHelix = applyHelixVariation(effect, i, zoneRadius, 0.0, entryAngle, entryRingR, entryGeomT);
		entryAngle = entryHelix[0];
		entryRingR = entryHelix[1];
		entryGeomT = entryHelix[2];

		double oldX = centerX + dx;
		double oldY = centerY + dy;
		double oldZ = centerZ + dz;

		double entryX = centerX + Math.cos(entryAngle) * entryRingR;
		double entryY = skyY + entryGeomT * helixSpan;
		double entryZ = centerZ + Math.sin(entryAngle) * entryRingR;

		double toEntryX = entryX - oldX;
		double toEntryY = entryY - oldY;
		double toEntryZ = entryZ - oldZ;
		double distToEntrySq = toEntryX * toEntryX + toEntryY * toEntryY + toEntryZ * toEntryZ;
		double latchRadius = zoneRadius * 0.18;
		double latchRadiusSq = latchRadius * latchRadius;

		int plane = buf.flags[i] & ParticleBuffer.PLANE_MASK;
		boolean latched = (buf.flags[i] & HELIX_LATCHED_FLAG) != 0;
		double t = readHelixT(buf.flags[i]);

		double newX;
		double newY;
		double newZ;

		if (!latched) {
			double pull = Math.min(1.0, 0.10 * activity * tickDelta + (effect.signedMagnitude / 24000.0) * activity);
			newX = oldX + toEntryX * pull;
			newY = oldY + toEntryY * pull;
			newZ = oldZ + toEntryZ * pull;
			if (distToEntrySq <= latchRadiusSq) {
				latched = true;
				t = 0.0;
				newX = entryX;
				newY = entryY;
				newZ = entryZ;
			}
		} else {
			if (t >= 1.0) {
				buf.flags[i] = writeHelixFlags(plane, latched, t);
				return true;
			}
			double dt = (effect.sinkStrength / (70.0 * helixSpan)) * activity * tickDelta;
			double travelT = Math.min(1.0, t + dt);
			double geomT = inverted ? (1.0 - travelT) : travelT;
			double angle = phase + geomT * Math.PI * 3.0 * spinSign;
			double ringR = zoneRadius * 0.65 * (1.0 - geomT * 0.35);
			double[] pathHelix = applyHelixVariation(effect, i, zoneRadius, travelT, angle, ringR, geomT);
			angle = pathHelix[0];
			ringR = pathHelix[1];
			geomT = pathHelix[2];
			newX = centerX + Math.cos(angle) * ringR;
			newY = skyY + geomT * helixSpan;
			newZ = centerZ + Math.sin(angle) * ringR;
			t = travelT;
		}

		buf.flags[i] = writeHelixFlags(plane, latched, t);
		buf.xFixed[i] = (long) (newX * 4096.0);
		buf.yFixed[i] = (long) (newY * 4096.0);
		buf.zFixed[i] = (long) (newZ * 4096.0);

		double invDt = tickDelta > 0 ? 1.0 / tickDelta : 1.0;
		v[0] = (newX - oldX) * invDt;
		v[1] = (newY - oldY) * invDt;
		v[2] = (newZ - oldZ) * invDt;
		return latched && t >= 1.0;
	}

	private static double[] applyHelixVariation(
		EffectorEffectSpec effect,
		int particleIndex,
		double zoneRadius,
		double travelT,
		double angle,
		double ringR,
		double geomT
	) {
		double variation = effect.pathVariationScale;
		if (variation <= 0f) {
			return new double[] { angle, ringR, geomT };
		}

		double u1 = stableParticleUnit(particleIndex, 11) * 2.0 - 1.0;
		double u2 = stableParticleUnit(particleIndex, 29) * 2.0 - 1.0;
		double u3 = stableParticleUnit(particleIndex, 47) * 2.0 - 1.0;
		double wobble = Math.sin(travelT * Math.PI * 5.0 + stableParticleAngle(particleIndex)) * variation;

		double variedAngle = angle + u1 * variation * 0.35 + wobble * 0.18;
		double variedRingR = ringR * (1.0 + u2 * variation * 0.14 + wobble * 0.06);
		double variedGeomT = Math.max(0.0, Math.min(1.0, geomT + u3 * variation * 0.05 + wobble * 0.025));
		return new double[] { variedAngle, Math.max(0.0, variedRingR), variedGeomT };
	}

	private static double stableParticleUnit(int particleIndex, int salt) {
		return ((particleIndex * 1103515245 + salt * 12345) & 0x7fffffff) / (double) 0x7fffffff;
	}

	private static void clearHelixFlags(ParticleBuffer buf, int i) {
		buf.flags[i] &= ParticleBuffer.PLANE_MASK;
	}

	private static double readHelixT(int flags) {
		return ((flags >> HELIX_T_SHIFT) & HELIX_T_MASK) / 65535.0;
	}

	private static int writeHelixFlags(int plane, boolean latched, double t) {
		int tFixed = (int) Math.max(0, Math.min(65535, Math.round(t * 65535.0)));
		return (plane & ParticleBuffer.PLANE_MASK)
			| (latched ? HELIX_LATCHED_FLAG : 0)
			| (tFixed << HELIX_T_SHIFT);
	}

	private static double repelHeightFalloff(double dy, double zoneRadius) {
		double band = zoneRadius * 0.32;
		double depth = 1.0 - Math.abs(dy) / band;
		if (depth <= 0.0) {
			return 0.0;
		}
		return depth * depth;
	}

	private static double skyFunnelStrength(double horizDist, double zoneRadius) {
		if (zoneRadius <= 0.0) {
			return 0.0;
		}
		double t = horizDist / (zoneRadius * 0.75);
		return Math.max(0.15, Math.min(1.0, t));
	}

	private static double stableParticleAngle(int particleIndex) {
		return ((particleIndex * 1103515245 + 12345) & 0x7fffffff) * (Math.PI * 2.0 / 0x80000000L);
	}

	private static double radialForceScale(EffectorEffectSpec effect, double dist, double zoneRadius) {
		if (!effect.radial || !effect.edgeFalloff || zoneRadius <= 0.0) {
			return 1.0;
		}
		double depth = 1.0 - dist / zoneRadius;
		if (depth <= 0.0) {
			return 0.0;
		}
		double power = effect.falloffPower > 0f ? effect.falloffPower : 1f;
		return Math.pow(depth, power);
	}

	private static double effectStrengthScale(EffectorEffectSpec effect) {
		return effect.effectPercent / 100.0;
	}

	private static boolean isImmuneToEffect(int particleIndex, EffectorEffectSpec effect) {
		if (effect.immunePercent <= 0f) {
			return false;
		}
		int bucket = (particleIndex * 1103515245 + 12345) >>> 1;
		return (bucket % 100) < (int) effect.immunePercent;
	}
}
