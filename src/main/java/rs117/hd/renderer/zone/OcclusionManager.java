package rs117.hd.renderer.zone;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import org.lwjgl.system.MemoryStack;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.opengl.shader.OcclusionShaderProgram;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.opengl.uniforms.UBOWorldViews.WorldViewStruct;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.RenderState;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GpuFloatBuffer;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL20C.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20C.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30C.glGenVertexArrays;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL43.GL_ANY_SAMPLES_PASSED_CONSERVATIVE;
import static rs117.hd.HdPlugin.GL_CAPS;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public final class OcclusionManager {
	private static final int FRAMES_IN_FLIGHT = 2;

	@Getter
	private static OcclusionManager instance;
	private final ConcurrentHashMap<Long, OcclusionQuery> dynamicOcclusionQueries = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Long, OcclusionQuery> tempOcclusionQueries = new ConcurrentHashMap<>();
	private final Set<Map.Entry<Long, OcclusionQuery>> dynamicOcclusionQuerySet = dynamicOcclusionQueries.entrySet();
	private final Set<Map.Entry<Long, OcclusionQuery>> tempOcclusionQuerySet = tempOcclusionQueries.entrySet();
	private final ConcurrentLinkedQueue<OcclusionQuery> freeQueries = new ConcurrentLinkedQueue<>();
	private final List<OcclusionQuery> queuedQueries = new ArrayList<>();
	private final List<OcclusionQuery> prevQueuedQueries = new ArrayList<>();
	private final float[] vec = new float[4];
	private final float[][] sceneFrustumPlanes = new float[6][4];
	private final float[] directionalFwd = new float[3];
	private final float[] projected = new float[6];
	@Inject
	private HdPlugin plugin;
	@Inject
	private ZoneRenderer zoneRenderer;
	@Inject
	private HdPluginConfig config;
	@Inject
	private FrameTimer frameTimer;
	@Inject
	private OcclusionShaderProgram occlusionProgram;
	@Inject
	private OcclusionShaderProgram.Debug occlusionDebugProgram;
	private RenderState renderState;
	@Getter
	private boolean active;
	private int debugMode;
	private int debugVisibility;

	@Getter
	private int queryCount = 0;
	@Getter
	private int passedQueryCount;

	private GpuFloatBuffer aabbBuffer;

	private int fboOcclusionDepth = 0;
	private int rboOcclusionDepth = 0;

	private int occlusionWidth = 0;
	private int occlusionHeight = 0;

	private static final int OCCLUSION_DOWNSCALE = 4;
	private static final int MIN_OCCLUSION_SIZE = 256;
	private static final int MAX_OCCLUSION_SIZE = 1024;

	private int glCubeVAO;
	private GLBuffer glCubeVBO;
	private GLBuffer glCubeEBO;
	private GLBuffer glCubeInstanceData;
	private int anySamplesPassedTarget;

	public void toggleDebug() { debugMode = (debugMode + 1) % 3; }
	public void toggleDebugVisibility() {
		debugVisibility = (debugVisibility + 1) % 3;
	}

	public void initialize(RenderState renderState) {
		this.renderState = renderState;

		instance = this;
		active = config.occlusionCulling();

		aabbBuffer = new GpuFloatBuffer(1024);

		// Check if conservative queries are supported
		if (GL_CAPS.GL_ARB_occlusion_query2) {
			anySamplesPassedTarget = GL_ANY_SAMPLES_PASSED_CONSERVATIVE;
			log.info("Using GL_ANY_SAMPLES_PASSED_CONSERVATIVE for occlusion queries");
		} else {
			anySamplesPassedTarget = GL_ANY_SAMPLES_PASSED;
			log.info("Using fallback GL_ANY_SAMPLES_PASSED for occlusion queries");
		}

		glCubeVBO = new GLBuffer("Occlusion VBO", GL_ARRAY_BUFFER, GL_STATIC_DRAW).initialize();
		glCubeEBO = new GLBuffer("Occlusion EBO", GL_ELEMENT_ARRAY_BUFFER, GL_STATIC_DRAW).initialize();
		glCubeInstanceData = new GLBuffer("Occlusion Instance Data", GL_ARRAY_BUFFER, GL_DYNAMIC_DRAW).initialize();

		try (MemoryStack stack = MemoryStack.stackPush()) {
			// Create cube VAO
			glCubeVAO = glGenVertexArrays();
			glBindVertexArray(glCubeVAO);

			FloatBuffer vboCubeData = stack.mallocFloat(8 * 3)
				.put(new float[] {
					// 8 unique cube corners
					-1, -1, -1, // 0
					1, -1, -1, // 1
					1, 1, -1, // 2
					-1, 1, -1, // 3
					-1, -1, 1, // 4
					1, -1, 1, // 5
					1, 1, 1, // 6
					-1, 1, 1  // 7
				})
				.flip();
			glCubeVBO.upload(vboCubeData);

			IntBuffer eboCubeData = stack.mallocInt(36)
				.put(new int[] {
					// Front face (-Z)
					0, 1, 2,
					0, 2, 3,

					// Back face (+Z)
					4, 6, 5,
					4, 7, 6,

					// Left face (-X)
					0, 3, 7,
					0, 7, 4,

					// Right face (+X)
					1, 5, 6,
					1, 6, 2,

					// Bottom face (-Y)
					0, 4, 5,
					0, 5, 1,

					// Top face (+Y)
					3, 2, 6,
					3, 6, 7
				})
				.flip();
			glCubeEBO.upload(eboCubeData);

			// position attribute
			glEnableVertexAttribArray(0);
			glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);

			// aabb center attribute
			glEnableVertexAttribArray(1);
			glVertexAttribDivisor(1, 1);

			// aabb scale attribute
			glEnableVertexAttribArray(2);
			glVertexAttribDivisor(2, 1);

			// reset
			glBindBuffer(GL_ARRAY_BUFFER, 0);
			glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
			glBindVertexArray(0);
		}
	}

	public void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {
		occlusionProgram.compile(includes);
		occlusionDebugProgram.compile(includes);
	}

	public void destroyShaders() {
		occlusionProgram.destroy();
		occlusionDebugProgram.destroy();
	}

	public void destroy() {
		if (glCubeVAO != 0)
			glDeleteVertexArrays(glCubeVAO);
		glCubeVAO = 0;

		if (glCubeVBO != null)
			glCubeVBO.destroy();
		glCubeVBO = null;

		if (glCubeEBO != null)
			glCubeEBO.destroy();
		glCubeEBO = null;

		if (glCubeInstanceData != null)
			glCubeInstanceData.destroy();
		glCubeInstanceData = null;

		if(aabbBuffer != null)
			aabbBuffer.destroy();
		aabbBuffer = null;

		destroyOcclusionFbo();
		deleteQueries(freeQueries);
		deleteQueries(queuedQueries);
		deleteQueries(prevQueuedQueries);
	}

	private void deleteQueries(Collection<OcclusionQuery> queries) {
		for (OcclusionQuery query : queries) {
			if (query.id[0] != 0) {
				glDeleteQueries(query.id);
				Arrays.fill(query.id, 0);
			}
		}
		queries.clear();
	}

	public OcclusionQuery obtainOcclusionQuery(
		WorldViewContext ctx,
		long hash,
		Zone zone,
		int orientation,
		boolean isDynamic,
		Model m,
		float x,
		float y,
		float z
	) {
		final ConcurrentHashMap<Long, OcclusionQuery> occlusionQueries = isDynamic ? dynamicOcclusionQueries : tempOcclusionQueries;
		OcclusionQuery query = occlusionQueries.get(hash);
		if (query == null) {
			query = obtainQuery();
			occlusionQueries.put(hash, query);
		}
		query.setWorldView(ctx.uboWorldViewStruct);
		query.queue();
		if (!query.resetThisFrame)
			query.reset();
		if (active) {
			query.addAABB(m.getAABB(orientation), x, y, z);
			zone.additionalOcclusionQueries.add(query);
		}
		return query;
	}

	public OcclusionQuery obtainQuery() {
		OcclusionQuery query = freeQueries.poll();
		if (query == null)
			query = new OcclusionQuery();
		return query;
	}

	private void processDynamicOcclusionQueries(Iterator<ConcurrentHashMap.Entry<Long, OcclusionQuery>> iter) {
		while (iter.hasNext()) {
			final OcclusionQuery query = iter.next().getValue();
			if (query.isQueued()) {
				query.resetThisFrame = false;
				continue;
			}
			query.free();
			iter.remove();
		}
	}

	private void destroyOcclusionFbo() {
		if (rboOcclusionDepth != 0) {
			glDeleteRenderbuffers(rboOcclusionDepth);
			rboOcclusionDepth = 0;
		}

		if (fboOcclusionDepth != 0) {
			glDeleteFramebuffers(fboOcclusionDepth);
			fboOcclusionDepth = 0;
		}

		if (rboOcclusionDepth != 0) {
			glDeleteRenderbuffers(rboOcclusionDepth);
			rboOcclusionDepth = 0;
		}

		if (fboOcclusionDepth != 0) {
			glDeleteFramebuffers(fboOcclusionDepth);
			fboOcclusionDepth = 0;
		}
	}

	private void ensureOcclusionFbo() {
		int targetWidth = clamp(plugin.sceneResolution[0] / OCCLUSION_DOWNSCALE, MIN_OCCLUSION_SIZE, MAX_OCCLUSION_SIZE);
		int targetHeight = clamp(plugin.sceneResolution[1] / OCCLUSION_DOWNSCALE, MIN_OCCLUSION_SIZE, MAX_OCCLUSION_SIZE);

		if (fboOcclusionDepth != 0 &&
			targetWidth == occlusionWidth &&
			targetHeight == occlusionHeight) {
			return;
		}

		destroyOcclusionFbo();

		occlusionWidth = targetWidth;
		occlusionHeight = targetHeight;

		fboOcclusionDepth = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboOcclusionDepth);

		rboOcclusionDepth = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, rboOcclusionDepth);

		glRenderbufferStorage(
			GL_RENDERBUFFER,
			GL_DEPTH_COMPONENT24,
			occlusionWidth,
			occlusionHeight
		);

		glFramebufferRenderbuffer(
			GL_FRAMEBUFFER,
			GL_DEPTH_ATTACHMENT,
			GL_RENDERBUFFER,
			rboOcclusionDepth
		);

		glDrawBuffer(GL_NONE);
		glReadBuffer(GL_NONE);

		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE) {
			throw new RuntimeException("Occlusion FBO incomplete: " + status);
		}

		glBindFramebuffer(GL_FRAMEBUFFER, 0);
	}

	public void readbackQueries() {
		active = config.occlusionCulling() && occlusionProgram.isValid();

		processDynamicOcclusionQueries(dynamicOcclusionQuerySet.iterator());
		processDynamicOcclusionQueries(tempOcclusionQuerySet.iterator());

		if (prevQueuedQueries.isEmpty())
			return;

		frameTimer.begin(Timer.OCCLUSION_READBACK);
		queryCount = prevQueuedQueries.size();
		passedQueryCount = 0;
		for (int i = 0; i < queryCount; i++) {
			final OcclusionQuery query = prevQueuedQueries.get(i);
			if (!query.queued)
				continue;
			query.queued = false;

			final int id = query.getReadbackId();
			if (id == 0)
				continue;

			if (query.frustumCulled)
				continue;

			while(glGetQueryObjecti(id, GL_QUERY_RESULT_AVAILABLE) == 0)
				Thread.onSpinWait();

			query.occluded = glGetQueryObjecti(id, GL_QUERY_RESULT) == 0;
			if (!query.occluded)
				passedQueryCount++;
		}
		frameTimer.end(Timer.OCCLUSION_READBACK);
		prevQueuedQueries.clear();

		checkGLErrors();
	}

	public void occlusionDebugPass() {
		if (queuedQueries.isEmpty() || debugMode == 0)
			return;

		renderState.viewport.set(0, 0, plugin.sceneResolution[0], plugin.sceneResolution[1]);
		renderState.framebuffer.set(GL_DRAW_FRAMEBUFFER, plugin.fboScene);
		renderState.depthFunc.set(GL_GEQUAL);
		renderState.disable.set(GL_CULL_FACE);
		renderState.enable.set(GL_BLEND);
		renderState.enable.set(GL_DEPTH_TEST);
		renderState.depthMask.set(false);
		renderState.ebo.set(glCubeEBO.id);
		renderState.vao.set(glCubeVAO);
		renderState.apply();
		occlusionDebugProgram.use();

		if (debugMode == 1) {
			glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
			glLineWidth(2.5f);
		}

		processQueries(queuedQueries, true);

		glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);

		renderState.ebo.set(0);
		renderState.vao.set(0);
		renderState.disable.set(GL_BLEND);
		renderState.disable.set(GL_DEPTH_TEST);
		renderState.depthMask.set(true);
		renderState.apply();
	}

	public void occlusionPass() {
		if (queuedQueries.isEmpty())
			return;

		frameTimer.begin(Timer.RENDER_OCCLUSION);
		frameTimer.begin(Timer.DRAW_OCCLUSION);

		zoneRenderer.sceneCamera.getFrustumPlanes(sceneFrustumPlanes);
		zoneRenderer.directionalCamera.getForwardDirection(directionalFwd);
		normalize(directionalFwd, directionalFwd);

		ensureOcclusionFbo();

		glBindFramebuffer(GL_READ_FRAMEBUFFER, plugin.msaaSamples > 0 ? plugin.fboSceneDepthResolve : plugin.fboScene);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fboOcclusionDepth);

		glBlitFramebuffer(
			0, 0,
			plugin.sceneResolution[0], plugin.sceneResolution[1],
			0, 0,
			occlusionWidth, occlusionHeight,
			GL_DEPTH_BUFFER_BIT,
			GL_NEAREST
		);

		renderState.viewport.set(0, 0, occlusionWidth, occlusionHeight);
		renderState.depthFunc.set(GL_GEQUAL);
		renderState.disable.set(GL_CULL_FACE);
		renderState.enable.set(GL_DEPTH_TEST);
		renderState.depthMask.set(false);
		renderState.colorMask.set(false, false, false, false);
		renderState.ebo.set(glCubeEBO.id);
		renderState.vao.set(glCubeVAO);
		renderState.apply();
		occlusionProgram.use();

		processQueries(queuedQueries, false);

		renderState.ebo.set(0);
		renderState.vao.set(0);
		renderState.disable.set(GL_DEPTH_TEST);
		renderState.depthMask.set(true);
		renderState.colorMask.set(true, true, true, true);
		renderState.apply();

		prevQueuedQueries.addAll(queuedQueries);
		queuedQueries.clear();

		frameTimer.end(Timer.DRAW_OCCLUSION);
		frameTimer.end(Timer.RENDER_OCCLUSION);

		checkGLErrors();
	}

	private void processQueries(List<OcclusionQuery> queries, boolean isDebug) {
		for (int i = 0; i < queries.size(); i++) {
			final OcclusionQuery query = queries.get(i);
			if (query.count == 0)
				continue;

			if (query.id[0] == 0)
				glGenQueries(query.id);

			buildQueryAABBs(query, isDebug);
		}

		aabbBuffer.flip();
		glCubeInstanceData.upload(aabbBuffer);
		glCubeInstanceData.bind();
		aabbBuffer.clear();

		checkGLErrors();

		for (int i = 0; i < queries.size(); i++) {
			final OcclusionQuery query = queries.get(i);
			if (query.count <= 0 || query.frustumCulled)
				continue;

			glVertexAttribPointer(1, 3, GL_FLOAT, false, 24, query.vboOffset);
			glVertexAttribPointer(2, 3, GL_FLOAT, false, 24, query.vboOffset + 12);

			if (isDebug) {
				if(debugVisibility > 0) {
					if (debugVisibility == 1 && !query.isStatic)
						continue;

					if (debugVisibility == 2 && query.isStatic)
						continue;
				}
				occlusionDebugProgram.queryId.set(query.id[0]);
				glDrawElementsInstanced(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0, query.count);
			} else {
				glBeginQuery(anySamplesPassedTarget, query.getSampleId());
				glDrawElementsInstanced(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0, query.count);
				glEndQuery(anySamplesPassedTarget);
				query.advance();
			}
		}
		checkGLErrors();
	}

	private void buildQueryAABBs(OcclusionQuery query, boolean isDebug) {
		if (query.count <= 0)
			return;

		final float EXPAND_FACTOR = 4.0f;
		final float dirX = -abs(directionalFwd[0]);
		final float dirY = -abs(directionalFwd[1]);
		final float dirZ = -abs(directionalFwd[2]);

		if (!isDebug && query.globalAABB) {
			projectAABB(query, projected,
				query.offsetX + (query.globalMinX + query.globalMaxX) * 0.5f,
				query.offsetY + (query.globalMinY + query.globalMaxY) * 0.5f,
				query.offsetZ + (query.globalMinZ + query.globalMaxZ) * 0.5f,
				(query.globalMaxX - query.globalMinX) * 0.5f,
				(query.globalMaxY - query.globalMinY) * 0.5f,
				(query.globalMaxZ - query.globalMinZ) * 0.5f
			);

			if (plugin.configShadowsEnabled)
				expandAABBAlongShadow(projected, dirX, dirY, dirZ, EXPAND_FACTOR);

			query.frustumCulled = !isAABBVisible(projected);
		}

		if (query.frustumCulled)
			return;

		query.vboOffset = (long)aabbBuffer.position() * Float.BYTES;
		aabbBuffer.ensureCapacity(query.count * 8);
		int aabbEnd = query.count * 6;
		for (int base = 0; base < aabbEnd;) {
			projectAABB(query, projected,
				query.offsetX + query.aabb[base++],
				query.offsetY + query.aabb[base++],
				query.offsetZ + query.aabb[base++],
				query.aabb[base++],
				query.aabb[base++],
				query.aabb[base++]
			);

			if (plugin.configShadowsEnabled && !isDebug)
				expandAABBAlongShadow(projected, dirX, dirY, dirZ, EXPAND_FACTOR);

			if(query.count == 1)
				query.frustumCulled = !isAABBVisible(projected);

			aabbBuffer.put(projected);
		}
	}

	private boolean isAABBVisible(float[] aabb) {
		float minX = aabb[0] - aabb[3];
		float minY = aabb[1] - aabb[4];
		float minZ = aabb[2] - aabb[5];

		float maxX = aabb[0] + aabb[3];
		float maxY = aabb[1] + aabb[4];
		float maxZ = aabb[2] + aabb[5];

		return HDUtils.isAABBIntersectingFrustum(minX, minY, minZ, maxX, maxY, maxZ, sceneFrustumPlanes);
	}

	private void expandAABBAlongShadow(
		float[] aabb,
		float dirX, float dirY, float dirZ,
		float expandFactor
	) {
		float sizeX = aabb[3];
		float sizeY = aabb[4];
		float sizeZ = aabb[5];

		float projectedExtent = dirX * sizeX + dirY * sizeY + dirZ * sizeZ;
		float offset = projectedExtent * expandFactor;

		aabb[3] = sizeX + dirX * offset;
		aabb[4] = sizeY + dirY * offset;
		aabb[5] = sizeZ + dirZ * offset;

		aabb[0] += dirX * offset;
		aabb[1] += dirY * offset;
		aabb[2] += dirZ * offset;
	}

	private void projectAABB(
		OcclusionQuery query,
		float[] out,
		float posX, float posY, float posZ,
		float sizeX, float sizeY, float sizeZ
	) {
		if (query.worldView == null) {
			out[0] = posX;
			out[1] = posY;
			out[2] = posZ;
			out[3] = sizeX;
			out[4] = sizeY;
			out[5] = sizeZ;
			return;
		}

		query.worldView.project(vec4(
			vec,
			posX - sizeX,
			posY - sizeY,
			posZ - sizeZ,
			1.0f
		));

		float minX = vec[0];
		float minY = vec[1];
		float minZ = vec[2];

		query.worldView.project(vec4(
			vec,
			posX + sizeX,
			posY + sizeY,
			posZ + sizeZ,
			1.0f
		));

		float maxX = vec[0];
		float maxY = vec[1];
		float maxZ = vec[2];

		out[0] = (minX + maxX) * 0.5f;
		out[1] = (minY + maxY) * 0.5f;
		out[2] = (minZ + maxZ) * 0.5f;
		out[3] = (maxX - minX) * 0.5f;
		out[4] = (maxY - minY) * 0.5f;
		out[5] = (maxZ - minZ) * 0.5f;
	}

	public final class OcclusionQuery {
		private final int[] id = new int[FRAMES_IN_FLIGHT];
		private final boolean[] sampled = new boolean[FRAMES_IN_FLIGHT];

		@Getter
		private boolean queued;

		private boolean occluded;
		private boolean frustumCulled;
		private boolean resetThisFrame;
		private boolean globalAABB;
		private boolean isStatic;

		private int activeId;
		private long vboOffset;

		private float offsetX;
		private float offsetY;
		private float offsetZ;

		private float globalMinX = Float.POSITIVE_INFINITY;
		private float globalMinY = Float.POSITIVE_INFINITY;
		private float globalMinZ = Float.POSITIVE_INFINITY;
		private float globalMaxX = Float.NEGATIVE_INFINITY;
		private float globalMaxY = Float.NEGATIVE_INFINITY;
		private float globalMaxZ = Float.NEGATIVE_INFINITY;

		@Setter
		private WorldViewStruct worldView;

		private float[] aabb = new float[6];
		private int count = 0;

		private void advance() {
			activeId = (activeId + 1) % FRAMES_IN_FLIGHT;
		}

		private int getReadbackId() {
			int idx = (activeId + 1) % FRAMES_IN_FLIGHT;
			if (!sampled[idx])
				return 0;

			sampled[idx] = false;
			return id[idx];
		}

		private int getSampleId() {
			sampled[activeId] = true;
			return id[activeId];
		}

		public boolean isOccluded() {
			return (occluded || frustumCulled) && active;
		}

		public boolean isVisible() {
			return !isOccluded();
		}

		public void setOffset(float x, float y, float z) {
			offsetX = x;
			offsetY = y;
			offsetZ = z;
		}

		public void addSphere(float x, float y, float z, float radius) {
			addAABB(x, y, z, radius, radius, radius);
		}

		public void addAABB(AABB aabb) {
			addAABB(aabb, 0, 0, 0);
		}

		public void addAABB(AABB aabb, float x, float y, float z) {
			addAABB(
				x + aabb.getCenterX(),
				y + aabb.getCenterY(),
				z + aabb.getCenterZ(),
				aabb.getExtremeX(),
				aabb.getExtremeY(),
				aabb.getExtremeZ()
			);
		}

		public void addMinMax(
			float minX, float minY, float minZ,
			float maxX, float maxY, float maxZ
		) {
			float sizeX = (maxX - minX) * 0.5f;
			float sizeY = (maxY - minY) * 0.5f;
			float sizeZ = (maxZ - minZ) * 0.5f;

			addAABB(
				minX + sizeX,
				minY + sizeY,
				minZ + sizeZ,
				sizeX,
				sizeY,
				sizeZ
			);
		}

		public void addAABB(
			float posX, float posY, float posZ,
			float sizeX, float sizeY, float sizeZ
		) {
			assert !isStatic;

			if (count * 6 >= aabb.length) {
				aabb = Arrays.copyOf(aabb, aabb.length * 2);
			}

			int base = count * 6;

			aabb[base] = posX;
			aabb[base + 1] = posY;
			aabb[base + 2] = posZ;
			aabb[base + 3] = sizeX;
			aabb[base + 4] = sizeY;
			aabb[base + 5] = sizeZ;

			count++;
		}

		public void setStatic() {
			if (count == 0)
				return;

			globalMinX = globalMinY = globalMinZ = Float.POSITIVE_INFINITY;
			globalMaxX = globalMaxY = globalMaxZ = Float.NEGATIVE_INFINITY;
			isStatic = true;

			int writeIndex = 0;
			for (int i = 0; i < count; i++) {
				int base1 = i * 6;

				float posX1 = aabb[base1];
				float posY1 = aabb[base1 + 1];
				float posZ1 = aabb[base1 + 2];
				float sizeX1 = aabb[base1 + 3];
				float sizeY1 = aabb[base1 + 4];
				float sizeZ1 = aabb[base1 + 5];

				float minX1 = posX1 - sizeX1;
				float minY1 = posY1 - sizeY1;
				float minZ1 = posZ1 - sizeZ1;
				float maxX1 = posX1 + sizeX1;
				float maxY1 = posY1 + sizeY1;
				float maxZ1 = posZ1 + sizeZ1;

				boolean encapsulated = false;

				for (int j = 0; j < count; j++) {
					if (i == j)
						continue;

					int base2 = j * 6;
					float posX2 = aabb[base2];
					float posY2 = aabb[base2 + 1];
					float posZ2 = aabb[base2 + 2];
					float sizeX2 = aabb[base2 + 3];
					float sizeY2 = aabb[base2 + 4];
					float sizeZ2 = aabb[base2 + 5];

					float minX2 = posX2 - sizeX2;
					float minY2 = posY2 - sizeY2;
					float minZ2 = posZ2 - sizeZ2;
					float maxX2 = posX2 + sizeX2;
					float maxY2 = posY2 + sizeY2;
					float maxZ2 = posZ2 + sizeZ2;

					if (minX1 >= minX2 && maxX1 <= maxX2 &&
						minY1 >= minY2 && maxY1 <= maxY2 &&
						minZ1 >= minZ2 && maxZ1 <= maxZ2) {
						encapsulated = true;
						break;
					}
				}

				if (!encapsulated) {
					// Move this surviving AABB in-place to the writeIndex position
					if (writeIndex != i) {
						int baseWrite = writeIndex * 6;
						aabb[baseWrite] = posX1;
						aabb[baseWrite + 1] = posY1;
						aabb[baseWrite + 2] = posZ1;
						aabb[baseWrite + 3] = sizeX1;
						aabb[baseWrite + 4] = sizeY1;
						aabb[baseWrite + 5] = sizeZ1;
					}
					writeIndex++;

					// Update global bounds
					globalMinX = Math.min(globalMinX, minX1);
					globalMinY = Math.min(globalMinY, minY1);
					globalMinZ = Math.min(globalMinZ, minZ1);
					globalMaxX = Math.max(globalMaxX, maxX1);
					globalMaxY = Math.max(globalMaxY, maxY1);
					globalMaxZ = Math.max(globalMaxZ, maxZ1);
				}
			}
			globalAABB = true;
			count = writeIndex;
		}

		public void reset() {
			count = 0;
			resetThisFrame = true;
		}

		public void queue() {
			if (!active || queued)
				return;

			queued = true;

			synchronized (queuedQueries) {
				queuedQueries.add(this);
			}
		}

		public void free() {
			count = 0;
			queued = false;
			occluded = false;
			isStatic = false;
			frustumCulled = false;
			resetThisFrame = false;
			worldView = null;

			offsetX = offsetY = offsetZ = 0f;

			globalMinX = globalMinY = globalMinZ = Float.POSITIVE_INFINITY;
			globalMaxX = globalMaxY = globalMaxZ = Float.NEGATIVE_INFINITY;
			globalAABB = false;

			Arrays.fill(sampled, false);

			freeQueries.add(this);
		}
	}
}
