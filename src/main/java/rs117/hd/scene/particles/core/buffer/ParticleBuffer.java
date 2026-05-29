/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles.core.buffer;

import rs117.hd.scene.particles.emitter.ParticleEmitter;

/**
 * Structure-of-arrays particle buffer. Fixed capacity, pre-allocated at construction.
 * Simulation uses fixed-point fields; render reads them via accessors (no duplicate float columns).
 */
public final class ParticleBuffer {

	public static final int PLANE_SHIFT = 0;
	public static final int PLANE_MASK = 0x3;

	public int count;
	public int capacity;

	/** Packed plane index (bits 0-1). */
	public int[] flags;
	public ParticleEmitter[] emitter;

	public long[] xFixed;
	public long[] yFixed;
	public long[] zFixed;
	public short[] velocityX;
	public short[] velocityY;
	public short[] velocityZ;
	public int[] speedRef;
	public int[] lifetimeTicks;
	public int[] remainingTicks;
	public int[] colourArgbRef;
	public int[] colourRgbLowRef;
	public int[] scaleRef;

	public float[] emitterOriginX;
	public float[] emitterOriginY;
	public float[] emitterOriginZ;

	/** Random flipbook frame index, or -1 when unused. */
	public int[] flipbookFrame;
	public float[] yaw;

	public ParticleBuffer(int capacity) {
		this.capacity = capacity;
		flags = new int[capacity];
		emitter = new ParticleEmitter[capacity];
		xFixed = new long[capacity];
		yFixed = new long[capacity];
		zFixed = new long[capacity];
		velocityX = new short[capacity];
		velocityY = new short[capacity];
		velocityZ = new short[capacity];
		speedRef = new int[capacity];
		lifetimeTicks = new int[capacity];
		remainingTicks = new int[capacity];
		colourArgbRef = new int[capacity];
		colourRgbLowRef = new int[capacity];
		scaleRef = new int[capacity];
		emitterOriginX = new float[capacity];
		emitterOriginY = new float[capacity];
		emitterOriginZ = new float[capacity];
		flipbookFrame = new int[capacity];
		yaw = new float[capacity];
	}

	public void ensureCapacity(int min) {}

	public int getPlane(int i) {
		return flags[i] & PLANE_MASK;
	}

	public float getPosX(int i) {
		return (float) (xFixed[i] >> 12);
	}

	public float getPosY(int i) {
		return (float) (yFixed[i] >> 12);
	}

	public float getPosZ(int i) {
		return (float) (zFixed[i] >> 12);
	}

	public float getLife(int i) {
		return (float) remainingTicks[i] / 50f;
	}

	public float getMaxLife(int i) {
		return (float) lifetimeTicks[i] / 50f;
	}

	public float getSize(int i) {
		return (float) scaleRef[i] / 16384f * 4f;
	}

	public boolean addSpawn(
		ParticleEmitter emitter,
		float posX, float posY, float posZ,
		float velX, float velY, float velZ,
		float life, float size,
		float colorR, float colorG, float colorB, float colorA,
		int plane, int flipbookFrameIndex,
		float originX, float originY, float originZ
	) {
		if (count >= capacity)
			return false;
		int i = count++;
		flags[i] = plane & PLANE_MASK;
		this.emitter[i] = emitter;
		emitterOriginX[i] = originX;
		emitterOriginY[i] = originY;
		emitterOriginZ[i] = originZ;

		xFixed[i] = (long) (posX * 4096);
		yFixed[i] = (long) (posY * 4096);
		zFixed[i] = (long) (posZ * 4096);
		float mag = (float) Math.sqrt((double) (velX * velX + velY * velY + velZ * velZ));
		if (mag > 1e-6f) {
			velocityX[i] = (short) Math.max(-32768, Math.min(32767, (int) (velX / mag * 32767)));
			velocityY[i] = (short) Math.max(-32768, Math.min(32767, (int) (velY / mag * 32767)));
			velocityZ[i] = (short) Math.max(-32768, Math.min(32767, (int) (velZ / mag * 32767)));
			speedRef[i] = (int) (mag * 16384);
		} else {
			velocityX[i] = 0;
			velocityY[i] = 0;
			velocityZ[i] = 0;
			speedRef[i] = 0;
		}
		lifetimeTicks[i] = (int) (life * 50f);
		remainingTicks[i] = lifetimeTicks[i];
		int r = (int) (colorR * 255f) & 0xff;
		int g = (int) (colorG * 255f) & 0xff;
		int b = (int) (colorB * 255f) & 0xff;
		int a = (int) (colorA * 255f) & 0xff;
		colourArgbRef[i] = (a << 24) | (r << 16) | (g << 8) | b;
		colourRgbLowRef[i] = 0;
		scaleRef[i] = (int) (size / 4f * 16384);
		flipbookFrame[i] = flipbookFrameIndex;
		yaw[i] = 0f;
		return true;
	}

	/**
	 * Writes current RGBA of particle at slot i into out[0..3].
	 */
	public void getCurrentColor(int i, float[] out) {
		int argb = colourArgbRef[i];
		int low = colourRgbLowRef[i];
		int red16 = (argb >> 8 & 0xff00) + (low >> 16 & 0xff);
		int green16 = (argb & 0xff00) + (low >> 8 & 0xff);
		int blue16 = (argb << 8 & 0xff00) + (low & 0xff);
		int alpha16 = (argb >> 16 & 0xff00) + (low >> 24 & 0xff);
		out[0] = (red16 >> 8) / 255f;
		out[1] = (green16 >> 8) / 255f;
		out[2] = (blue16 >> 8) / 255f;
		out[3] = (alpha16 >> 8) / 255f;
	}

	public void swap(int i, int j) {
		swapInt(flags, i, j);
		swapRef(emitter, i, j);
		swapLong(xFixed, i, j);
		swapLong(yFixed, i, j);
		swapLong(zFixed, i, j);
		swapShort(velocityX, i, j);
		swapShort(velocityY, i, j);
		swapShort(velocityZ, i, j);
		swapInt(speedRef, i, j);
		swapInt(lifetimeTicks, i, j);
		swapInt(remainingTicks, i, j);
		swapInt(colourArgbRef, i, j);
		swapInt(colourRgbLowRef, i, j);
		swapInt(scaleRef, i, j);
		swapFloat(emitterOriginX, i, j);
		swapFloat(emitterOriginY, i, j);
		swapFloat(emitterOriginZ, i, j);
		swapInt(flipbookFrame, i, j);
		swapFloat(yaw, i, j);
	}

	public void clear() {
		count = 0;
	}

	private static void swapLong(long[] a, int i, int j) {
		long t = a[i];
		a[i] = a[j];
		a[j] = t;
	}

	private static void swapShort(short[] a, int i, int j) {
		short t = a[i];
		a[i] = a[j];
		a[j] = t;
	}

	private static void swapFloat(float[] a, int i, int j) {
		float t = a[i];
		a[i] = a[j];
		a[j] = t;
	}

	private static void swapInt(int[] a, int i, int j) {
		int t = a[i];
		a[i] = a[j];
		a[j] = t;
	}

	private static void swapRef(ParticleEmitter[] a, int i, int j) {
		ParticleEmitter t = a[i];
		a[i] = a[j];
		a[j] = t;
	}
}
