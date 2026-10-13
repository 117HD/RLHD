package rs117.hd.renderer.zone;

import javax.annotation.Nullable;
import rs117.hd.scene.model_overrides.ModelOverride;

/**
 * A placed instance of an alpha model. Static instances reference a shared {@link StaticAlphaModel}, which allows the
 * sorted face order to be computed once per camera orientation and reused by every instance of the same model.
 * Temporary (dynamic) instances have a {@code null} {@link #staticModel}.
 */
public final class AlphaModelInstance {
	int id;
	ModelOverride modelOverride;
	AsyncCachedModel asyncModel;
	@Nullable
	StaticAlphaModel staticModel;
	int startpos, endpos;
	short x, y, z; // local position
	short extentsX, extentsY, extentsZ;
	short rid;
	int vao;
	int tboF;
	byte level;
	byte lx, lz, ux, uz; // lower/upper zone coords
	byte zofx, zofz; // for temp alpha models, offset of source zone from target zone
	byte flags = VISIBLE;

	int dist;
	int asyncSortIdx = -1;
	int sortedIndiciesOffset; // offset into the sorted index buffer of the shared block
	int sortedIndiciesCount; // number of indices in the shared block
	int baseVertex; // vertex offset of this instance's geometry within the zone's alpha VBO

	static final int SKIP = 1; // temporary model is in a closer zone
	static final int TEMP = 2; // temporary model added to a closer zone
	static final int SORT_COMPLETED = 4;
	static final int VISIBLE = 8;

	void setSorted() {
		flags |= SORT_COMPLETED;
	}

	void setHidden() {
		flags &= ~VISIBLE;
	}

	boolean isVisible() {
		return (flags & VISIBLE) != 0;
	}

	boolean needsSorting() {
		return (flags & SORT_COMPLETED) == 0;
	}

	boolean isTemp() {
		return staticModel == null;
	}

	void createStaticModel(int[] packedFaces, int[] doubleSidedBitSet, char doubleSidedCount, int radius) {
		assert staticModel == null;
		staticModel = new StaticAlphaModel(packedFaces, doubleSidedBitSet, doubleSidedCount, radius);
	}

	void setView(DynamicModelVAO.View view) {
		vao = view.vao;
		tboF = view.tboTexId;
		startpos = view.getStartOffset();
		endpos = view.getEndOffset();
	}

	void calculateDist(int cx, int cy, int cz, int zx, int zz) {
		final int dx = (x + ((zx - zofx) << 10)) - cx;
		final int dy = y - cy;
		final int dz = (z + ((zz - zofz) << 10)) - cz;
		dist = dx * dx + dy * dy + dz * dz;
	}
}
