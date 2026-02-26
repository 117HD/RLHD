/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles;

import rs117.hd.scene.particles.emitter.ParticleEmitter;

/**
 * Structure-of-arrays storage for particles to improve cache locality in the update and render loops.
 */
public final class ParticleBuffer {

	private static final int INITIAL_CAPACITY = 512;

	public static final int FLAG_COLOUR_INCREMENT = 1;
	public static final int FLAG_HAS_TARGET_COLOR = 2;

	public int count;
	public int capacity;

	public float[] posX;
	public float[] posY;
	public float[] posZ;
	public float[] velX;
	public float[] velY;
	public float[] velZ;
	public float[] life;
	public float[] maxLife;
	public float[] size;
	public int[] plane;

	public float[] colorR;
	public float[] colorG;
	public float[] colorB;
	public float[] colorA;

	public float[] initialColorR;
	public float[] initialColorG;
	public float[] initialColorB;
	public float[] initialColorA;
	public float[] targetColorR;
	public float[] targetColorG;
	public float[] targetColorB;
	public float[] targetColorA;
	public float[] colorTransitionPct;
	public float[] alphaTransitionPct;

	public float[] colourIncR;
	public float[] colourIncG;
	public float[] colourIncB;
	public float[] colourIncA;
	public float[] colourTransitionEndLife;
	public float[] scaleTransitionEndLife;
	public float[] speedTransitionEndLife;
	public float[] scaleIncPerSec;
	public float[] speedIncPerSec;

	public float[] emitterOriginX;
	public float[] emitterOriginY;
	public float[] emitterOriginZ;
	public int[] distanceFalloffType;
	public int[] distanceFalloffStrength;
	public boolean[] hasLevelBounds;
	public int[] upperBoundLevel;
	public int[] lowerBoundLevel;

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

	public float[] flipbookFrame;

	public ParticleBuffer() {
		capacity = INITIAL_CAPACITY;
		posX = new float[capacity];
		posY = new float[capacity];
		posZ = new float[capacity];
		velX = new float[capacity];
		velY = new float[capacity];
		velZ = new float[capacity];
		life = new float[capacity];
		maxLife = new float[capacity];
		size = new float[capacity];
		plane = new int[capacity];
		colorR = new float[capacity];
		colorG = new float[capacity];
		colorB = new float[capacity];
		colorA = new float[capacity];
		initialColorR = new float[capacity];
		initialColorG = new float[capacity];
		initialColorB = new float[capacity];
		initialColorA = new float[capacity];
		targetColorR = new float[capacity];
		targetColorG = new float[capacity];
		targetColorB = new float[capacity];
		targetColorA = new float[capacity];
		colorTransitionPct = new float[capacity];
		alphaTransitionPct = new float[capacity];
		colourIncR = new float[capacity];
		colourIncG = new float[capacity];
		colourIncB = new float[capacity];
		colourIncA = new float[capacity];
		colourTransitionEndLife = new float[capacity];
		scaleTransitionEndLife = new float[capacity];
		speedTransitionEndLife = new float[capacity];
		scaleIncPerSec = new float[capacity];
		speedIncPerSec = new float[capacity];
		emitterOriginX = new float[capacity];
		emitterOriginY = new float[capacity];
		emitterOriginZ = new float[capacity];
		distanceFalloffType = new int[capacity];
		distanceFalloffStrength = new int[capacity];
		hasLevelBounds = new boolean[capacity];
		upperBoundLevel = new int[capacity];
		lowerBoundLevel = new int[capacity];
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
		flipbookFrame = new float[capacity];
	}


	public void ensureCapacity(int min) {
		if (capacity >= min) return;
		capacity = Math.max(capacity * 2, min);
		capacity = Math.min(capacity, 4096);
		int n = capacity;
		int copy = count;
		posX = copyResize(posX, n, copy);
		posY = copyResize(posY, n, copy);
		posZ = copyResize(posZ, n, copy);
		velX = copyResize(velX, n, copy);
		velY = copyResize(velY, n, copy);
		velZ = copyResize(velZ, n, copy);
		life = copyResize(life, n, copy);
		maxLife = copyResize(maxLife, n, copy);
		size = copyResize(size, n, copy);
		plane = copyResize(plane, n, copy);
		colorR = copyResize(colorR, n, copy);
		colorG = copyResize(colorG, n, copy);
		colorB = copyResize(colorB, n, copy);
		colorA = copyResize(colorA, n, copy);
		initialColorR = copyResize(initialColorR, n, copy);
		initialColorG = copyResize(initialColorG, n, copy);
		initialColorB = copyResize(initialColorB, n, copy);
		initialColorA = copyResize(initialColorA, n, copy);
		targetColorR = copyResize(targetColorR, n, copy);
		targetColorG = copyResize(targetColorG, n, copy);
		targetColorB = copyResize(targetColorB, n, copy);
		targetColorA = copyResize(targetColorA, n, copy);
		colorTransitionPct = copyResize(colorTransitionPct, n, copy);
		alphaTransitionPct = copyResize(alphaTransitionPct, n, copy);
		colourIncR = copyResize(colourIncR, n, copy);
		colourIncG = copyResize(colourIncG, n, copy);
		colourIncB = copyResize(colourIncB, n, copy);
		colourIncA = copyResize(colourIncA, n, copy);
		colourTransitionEndLife = copyResize(colourTransitionEndLife, n, copy);
		scaleTransitionEndLife = copyResize(scaleTransitionEndLife, n, copy);
		speedTransitionEndLife = copyResize(speedTransitionEndLife, n, copy);
		scaleIncPerSec = copyResize(scaleIncPerSec, n, copy);
		speedIncPerSec = copyResize(speedIncPerSec, n, copy);
		emitterOriginX = copyResize(emitterOriginX, n, copy);
		emitterOriginY = copyResize(emitterOriginY, n, copy);
		emitterOriginZ = copyResize(emitterOriginZ, n, copy);
		distanceFalloffType = copyResize(distanceFalloffType, n, copy);
		distanceFalloffStrength = copyResize(distanceFalloffStrength, n, copy);
		upperBoundLevel = copyResize(upperBoundLevel, n, copy);
		lowerBoundLevel = copyResize(lowerBoundLevel, n, copy);
		flags = copyResize(flags, n, copy);
		hasLevelBounds = copyResize(hasLevelBounds, n, copy);
		emitter = copyResize(emitter, n, copy);
		xFixed = copyResizeLong(xFixed, n, copy);
		yFixed = copyResizeLong(yFixed, n, copy);
		zFixed = copyResizeLong(zFixed, n, copy);
		velocityX = copyResizeShort(velocityX, n, copy);
		velocityY = copyResizeShort(velocityY, n, copy);
		velocityZ = copyResizeShort(velocityZ, n, copy);
		speedRef = copyResize(speedRef, n, copy);
		lifetimeTicks = copyResize(lifetimeTicks, n, copy);
		remainingTicks = copyResize(remainingTicks, n, copy);
		colourArgbRef = copyResize(colourArgbRef, n, copy);
		colourRgbLowRef = copyResize(colourRgbLowRef, n, copy);
		scaleRef = copyResize(scaleRef, n, copy);
		flipbookFrame = copyResize(flipbookFrame, n, copy);
	}

	private static long[] copyResizeLong(long[] a, int newLen, int copy) {
		long[] b = new long[newLen];
		if (a != null && copy > 0) System.arraycopy(a, 0, b, 0, copy);
		return b;
	}

	private static short[] copyResizeShort(short[] a, int newLen, int copy) {
		short[] b = new short[newLen];
		if (a != null && copy > 0) System.arraycopy(a, 0, b, 0, copy);
		return b;
	}

	private static float[] copyResize(float[] a, int newLen, int copy) {
		float[] b = new float[newLen];
		if (a != null && copy > 0) System.arraycopy(a, 0, b, 0, copy);
		return b;
	}

	private static int[] copyResize(int[] a, int newLen, int copy) {
		int[] b = new int[newLen];
		if (a != null && copy > 0) System.arraycopy(a, 0, b, 0, copy);
		return b;
	}

	private static boolean[] copyResize(boolean[] a, int newLen, int copy) {
		boolean[] b = new boolean[newLen];
		if (a != null && copy > 0) System.arraycopy(a, 0, b, 0, copy);
		return b;
	}

	@SuppressWarnings("unchecked")
	private static ParticleEmitter[] copyResize(ParticleEmitter[] a, int newLen, int copy) {
		ParticleEmitter[] b = new ParticleEmitter[newLen];
		if (a != null && copy > 0) System.arraycopy(a, 0, b, 0, copy);
		return b;
	}

	public void addFrom(Particle p) {
		ensureCapacity(count + 1);
		int i = count++;
		posX[i] = p.position[0];
		posY[i] = p.position[1];
		posZ[i] = p.position[2];
		velX[i] = p.velocity[0];
		velY[i] = p.velocity[1];
		velZ[i] = p.velocity[2];
		life[i] = p.life;
		maxLife[i] = p.maxLife;
		size[i] = p.size;
		plane[i] = p.plane;
		colorR[i] = p.color[0];
		colorG[i] = p.color[1];
		colorB[i] = p.color[2];
		colorA[i] = p.color[3];
		initialColorR[i] = p.initialColor[0];
		initialColorG[i] = p.initialColor[1];
		initialColorB[i] = p.initialColor[2];
		initialColorA[i] = p.initialColor[3];
		colorTransitionPct[i] = p.colorTransitionPct;
		alphaTransitionPct[i] = p.alphaTransitionPct;
		emitterOriginX[i] = p.emitterOriginX;
		emitterOriginY[i] = p.emitterOriginY;
		emitterOriginZ[i] = p.emitterOriginZ;
		distanceFalloffType[i] = p.distanceFalloffType;
		distanceFalloffStrength[i] = p.distanceFalloffStrength;
		hasLevelBounds[i] = p.hasLevelBounds;
		upperBoundLevel[i] = p.upperBoundLevel;
		lowerBoundLevel[i] = p.lowerBoundLevel;
		emitter[i] = p.emitter;

		int f = 0;
		if (p.colourIncrementPerSecond != null) {
			f |= FLAG_COLOUR_INCREMENT;
			colourIncR[i] = p.colourIncrementPerSecond[0];
			colourIncG[i] = p.colourIncrementPerSecond[1];
			colourIncB[i] = p.colourIncrementPerSecond[2];
			colourIncA[i] = p.colourIncrementPerSecond[3];
			colourTransitionEndLife[i] = p.colourTransitionEndLife;
		} else {
			colourTransitionEndLife[i] = -1f;
		}
		if (p.targetColor != null) {
			f |= FLAG_HAS_TARGET_COLOR;
			targetColorR[i] = p.targetColor[0];
			targetColorG[i] = p.targetColor[1];
			targetColorB[i] = p.targetColor[2];
			targetColorA[i] = p.targetColor[3];
		}
		flags[i] = f;
		scaleIncPerSec[i] = p.scaleIncrementPerSecond;
		speedIncPerSec[i] = p.speedIncrementPerSecond;
		scaleTransitionEndLife[i] = p.scaleTransitionEndLife;
		speedTransitionEndLife[i] = p.speedTransitionEndLife;
		// Ref state from float (so EmittedParticle.tick 1:1 can run)
		xFixed[i] = (long) (p.position[0] * 4096);
		yFixed[i] = (long) (p.position[1] * 4096);
		zFixed[i] = (long) (p.position[2] * 4096);
		float vx = p.velocity[0], vy = p.velocity[1], vz = p.velocity[2];
		float mag = (float) Math.sqrt((double) (vx * vx + vy * vy + vz * vz));
		if (mag > 1e-6f) {
			velocityX[i] = (short) Math.max(-32768, Math.min(32767, (int) (vx / mag * 32767)));
			velocityY[i] = (short) Math.max(-32768, Math.min(32767, (int) (vy / mag * 32767)));
			velocityZ[i] = (short) Math.max(-32768, Math.min(32767, (int) (vz / mag * 32767)));
			speedRef[i] = (int) (mag * 16384);
		} else {
			velocityX[i] = 0;
			velocityY[i] = 0;
			velocityZ[i] = 0;
			speedRef[i] = 0;
		}
		lifetimeTicks[i] = (int) (p.maxLife * 50f);
		remainingTicks[i] = (int) (p.life * 50f);
		int r = (int) (p.color[0] * 255f) & 0xff;
		int g = (int) (p.color[1] * 255f) & 0xff;
		int b = (int) (p.color[2] * 255f) & 0xff;
		int a = (int) (p.color[3] * 255f) & 0xff;
		colourArgbRef[i] = (a << 24) | (r << 16) | (g << 8) | b;
		colourRgbLowRef[i] = 0;
		scaleRef[i] = (int) (p.size / 4f * 16384);
		flipbookFrame[i] = p.flipbookRandomFrame >= 0 ? (float) p.flipbookRandomFrame : -1f;
	}

	public void syncRefToFloat(int i) {
		posX[i] = (float) (xFixed[i] >> 12);
		posY[i] = (float) (yFixed[i] >> 12);
		posZ[i] = (float) (zFixed[i] >> 12);
		life[i] = (float) remainingTicks[i] / 50f;
		maxLife[i] = (float) lifetimeTicks[i] / 50f;
		size[i] = (float) scaleRef[i] / 16384f * 4f;
		int argb = colourArgbRef[i];
		int low = colourRgbLowRef[i];
		int red16 = (argb >> 8 & 0xff00) + (low >> 16 & 0xff);
		int green16 = (argb & 0xff00) + (low >> 8 & 0xff);
		int blue16 = (argb << 8 & 0xff00) + (low & 0xff);
		int alpha16 = (argb >> 16 & 0xff00) + (low >> 24 & 0xff);
		colorR[i] = (red16 >> 8) / 255f;
		colorG[i] = (green16 >> 8) / 255f;
		colorB[i] = (blue16 >> 8) / 255f;
		colorA[i] = (alpha16 >> 8) / 255f;
	}

	public void swap(int i, int j) {
		swapFloat(posX, i, j);
		swapFloat(posY, i, j);
		swapFloat(posZ, i, j);
		swapFloat(velX, i, j);
		swapFloat(velY, i, j);
		swapFloat(velZ, i, j);
		swapFloat(life, i, j);
		swapFloat(maxLife, i, j);
		swapFloat(size, i, j);
		swapInt(plane, i, j);
		swapFloat(colorR, i, j);
		swapFloat(colorG, i, j);
		swapFloat(colorB, i, j);
		swapFloat(colorA, i, j);
		swapFloat(initialColorR, i, j);
		swapFloat(initialColorG, i, j);
		swapFloat(initialColorB, i, j);
		swapFloat(initialColorA, i, j);
		swapFloat(targetColorR, i, j);
		swapFloat(targetColorG, i, j);
		swapFloat(targetColorB, i, j);
		swapFloat(targetColorA, i, j);
		swapFloat(colorTransitionPct, i, j);
		swapFloat(alphaTransitionPct, i, j);
		swapFloat(colourIncR, i, j);
		swapFloat(colourIncG, i, j);
		swapFloat(colourIncB, i, j);
		swapFloat(colourIncA, i, j);
		swapFloat(colourTransitionEndLife, i, j);
		swapFloat(scaleTransitionEndLife, i, j);
		swapFloat(speedTransitionEndLife, i, j);
		swapFloat(scaleIncPerSec, i, j);
		swapFloat(speedIncPerSec, i, j);
		swapFloat(emitterOriginX, i, j);
		swapFloat(emitterOriginY, i, j);
		swapFloat(emitterOriginZ, i, j);
		swapInt(distanceFalloffType, i, j);
		swapInt(distanceFalloffStrength, i, j);
		swapBool(hasLevelBounds, i, j);
		swapInt(upperBoundLevel, i, j);
		swapInt(lowerBoundLevel, i, j);
		swapInt(flags, i, j);
		ParticleEmitter e = emitter[i];
		emitter[i] = emitter[j];
		emitter[j] = e;
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
		swapFloat(flipbookFrame, i, j);
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

	private static void swapBool(boolean[] a, int i, int j) {
		boolean t = a[i];
		a[i] = a[j];
		a[j] = t;
	}

	public void getCurrentColor(int i, float[] out) {
		if ((flags[i] & FLAG_COLOUR_INCREMENT) != 0) {
			out[0] = colorR[i];
			out[1] = colorG[i];
			out[2] = colorB[i];
			out[3] = colorA[i];
			return;
		}
		float m = maxLife[i];
		if (m <= 0) {
			out[0] = colorR[i];
			out[1] = colorG[i];
			out[2] = colorB[i];
			out[3] = Math.max(0f, Math.min(1f, colorA[i]));
			return;
		}
		float t = 1f - life[i] / m;
		if ((flags[i] & FLAG_HAS_TARGET_COLOR) == 0 || colorTransitionPct[i] <= 0) {
			out[0] = colorR[i];
			out[1] = colorG[i];
			out[2] = colorB[i];
			out[3] = Math.max(0f, Math.min(1f, colorA[i]));
			return;
		}
		float blend = Particle.transitionBlend(t, colorTransitionPct[i]);
		float aBlend = Particle.transitionBlend(t, alphaTransitionPct[i]);
		out[0] = initialColorR[i] + (targetColorR[i] - initialColorR[i]) * blend;
		out[1] = initialColorG[i] + (targetColorG[i] - initialColorG[i]) * blend;
		out[2] = initialColorB[i] + (targetColorB[i] - initialColorB[i]) * blend;
		out[3] = initialColorA[i] + (targetColorA[i] - initialColorA[i]) * aBlend;
	}

	public void clear() {
		count = 0;
	}
}
