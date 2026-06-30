package rs117.hd.renderer.zone.passes;

import com.google.inject.Injector;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.renderer.zone.WorldViewContext;
import rs117.hd.renderer.zone.Zone;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.RenderState;

import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.utils.collections.Util.quickSort;

@Slf4j
@Singleton
public final class RenderPipeline {

	@Inject
	private Injector injector;

	@Inject
	private HdPlugin plugin;

	@Inject
	private FrameTimer frameTimer;

	public final InitializeFunction           initialize           = new InitializeFunction();
	public final InitializeShadersFunction    initializeShaders    = new InitializeShadersFunction();
	public final DestroyFunction              destroy              = new DestroyFunction();
	public final DestroyShadersFunction       destroyShaders       = new DestroyShadersFunction();
	public final AddShaderIncludesFunction    addShaderIncludes    = new AddShaderIncludesFunction();
	public final ProcessConfigChangesFunction processConfigChanges = new ProcessConfigChangesFunction();
	public final PreSceneDrawFunction         preSceneDraw         = new PreSceneDrawFunction();
	public final PostSceneDrawFunction        postSceneDraw        = new PostSceneDrawFunction();
	public final ZoneInFrustumFunction        zoneInFrustum        = new ZoneInFrustumFunction();
	public final DynamicInFrustumFunction     dynamicInFrustum     = new DynamicInFrustumFunction();
	public final DrawZoneOpaqueFunction       drawZoneOpaque       = new DrawZoneOpaqueFunction();
	public final DrawZoneAlphaFunction        drawZoneAlpha        = new DrawZoneAlphaFunction();
	public final DrawPassFunction             drawPass             = new DrawPassFunction();
	public final DrawFunction                 draw                 = new DrawFunction();
	public final PostDrawFunction             postDraw             = new PostDrawFunction();

	private final int passCount = RenderPass.TYPES.length;
	private final RenderPassType[] types = new RenderPassType[passCount];
	private final RenderPass[] passes = new RenderPass[passCount];

	public void initialize() {
		for(int i = 0; i < passCount; i++)
			passes[i] = injector.getInstance(RenderPass.TYPES[i].clazz);
		quickSort(passes, Comparator.comparingInt((A) -> A.getType().ordinal()));

		for(int i = 0; i < passCount; i++)
			types[i] = passes[i].getType();

		initialize.execute();
	}

	public void destroy() {
		destroy.execute();
		Arrays.fill(passes, null);
	}

	private boolean internalExecute(BaseRenderPassFunction function) {
		final boolean detailedTimers = plugin.enableDetailedTimers;
		boolean result = false;

		for (int i = 0; i < passCount; i++) {
			final RenderPass renderPass = passes[i];
			final RenderPassType type = types[i];

			if (renderPass == null || type == null)
				continue;

			try {
				if(detailedTimers)
					frameTimer.begin(type.timer);

				result |= function.consumer.accept(renderPass);
			} catch (Throwable e) {
				log.error("Error during {} for render pass {}:", function.action, type.name, e);
				if (!function.handleExceptions)
					throw new RuntimeException(e);
				plugin.requestPluginStop();
			} finally {
				if(detailedTimers)
					frameTimer.end(type.timer);

				if (function.checkGL)
					checkGLErrors(() -> type.name + "::" + function.action);
			}
		}
		return result;
	}

	public final class InitializeFunction extends BaseRenderPassFunction {
		private InitializeFunction() {
			super("initializeShaders", false, false);
			consumer = (renderPass) -> {
				renderPass.initialize();
				return true;
			};
		}
	}

	public final class InitializeShadersFunction extends BaseRenderPassFunction {

		private ShaderIncludes includes;

		private InitializeShadersFunction() {
			super("initializeShaders", false, false);
			consumer = renderPass -> {
				renderPass.initializeShaders(includes);
				return true;
			};
		}

		public void execute(ShaderIncludes includes) {
			this.includes = includes;
			execute();
		}
	}

	public final class DestroyFunction extends BaseRenderPassFunction {
		private DestroyFunction() {
			super("destroy", false, true);
			consumer = renderPass -> {
				renderPass.destroy();
				return true;
			};
		}
	}

	public final class DestroyShadersFunction extends BaseRenderPassFunction {
		private DestroyShadersFunction() {
			super("destroyShaders", false, true);
			consumer = renderPass -> {
				renderPass.destroyShaders();
				return true;
			};
		}
	}

	public final class AddShaderIncludesFunction extends BaseRenderPassFunction {

		private ShaderIncludes includes;

		private AddShaderIncludesFunction() {
			super("addShaderIncludes", false, false);
			consumer = renderPass -> {
				renderPass.addShaderIncludes(includes);
				return true;
			};
		}

		public void execute(ShaderIncludes includes) {
			this.includes = includes;
			execute();
		}
	}

	public final class ProcessConfigChangesFunction extends BaseRenderPassFunction {
		private Set<String> keys;

		private ProcessConfigChangesFunction() {
			super("processConfigChanges", false, false);
			consumer = renderPass -> {
				renderPass.processConfigChanges(keys);
				return true;
			};
		}

		public void execute(Set<String> keys) {
			this.keys = keys;
			execute();
		}
	}

	public final class PreSceneDrawFunction extends BaseRenderPassFunction {

		private WorldViewContext ctx;
		private boolean isTopLevel;

		private PreSceneDrawFunction() {
			super("preSceneDraw", true, false);
			consumer = renderPass -> {
				renderPass.preSceneDraw(ctx, isTopLevel);
				return true;
			};
		}

		public void execute(WorldViewContext ctx, boolean isTopLevel) {
			this.ctx        = ctx;
			this.isTopLevel = isTopLevel;
			execute();
		}
	}

	public final class PostSceneDrawFunction extends BaseRenderPassFunction {

		private WorldViewContext ctx;

		private PostSceneDrawFunction() {
			super("postSceneDraw", true, false);
			consumer = renderPass -> {
				renderPass.postSceneDraw(ctx);
				return true;
			};
		}

		public void execute(WorldViewContext ctx) {
			this.ctx = ctx;
			execute();
		}
	}

	public final class ZoneInFrustumFunction extends BaseRenderPassFunction {

		private Zone zone;
		private int zx, zz, minX, minY, minZ, maxX, maxY, maxZ;

		private ZoneInFrustumFunction() {
			super("zoneInFrustum", false, true);
			consumer = renderPass -> renderPass.zoneInFrustum(zone, zx, zz, minX, minY, minZ, maxX, maxY, maxZ);
		}

		public boolean execute(Zone zone, int zx, int zz, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
			this.zone = zone;
			this.zx   = zx;
			this.zz   = zz;
			this.minX = minX;
			this.minY = minY;
			this.minZ = minZ;
			this.maxX = maxX;
			this.maxY = maxY;
			this.maxZ = maxZ;
			return execute();
		}
	}

	public final class DynamicInFrustumFunction extends BaseRenderPassFunction {

		private WorldViewContext ctx;
		private Renderable renderable;
		private Model model;
		private ModelOverride modelOverride;
		private int x, y, z;

		private DynamicInFrustumFunction() {
			super("dynamicInFrustum", false, true);
			consumer = renderPass -> renderPass.dynamicInFrustum(ctx, renderable, model, modelOverride, x, y, z);
		}

		public boolean execute(WorldViewContext ctx, Renderable renderable, Model model, ModelOverride modelOverride, int x, int y, int z) {
			this.ctx           = ctx;
			this.renderable    = renderable;
			this.model         = model;
			this.modelOverride = modelOverride;
			this.x             = x;
			this.y             = y;
			this.z             = z;
			return execute();
		}
	}

	public final class DrawZoneOpaqueFunction extends BaseRenderPassFunction {
		private WorldViewContext ctx;
		private Zone zone;
		private int zx, zz;

		private DrawZoneOpaqueFunction() {
			super("drawZoneOpaque", false, false);
			consumer = renderPass -> {
				renderPass.drawZoneOpaque(ctx, zone, zx, zz);
				return true;
			};
		}

		public void execute(WorldViewContext ctx, Zone zone, int zx, int zz) {
			this.ctx  = ctx;
			this.zone = zone;
			this.zx   = zx;
			this.zz   = zz;
			execute();
		}
	}

	public final class DrawZoneAlphaFunction extends BaseRenderPassFunction {
		private WorldViewContext ctx;
		private Zone zone;
		private int level, zx, zz;

		private DrawZoneAlphaFunction() {
			super("drawZoneAlpha", false, false);
			consumer = (renderPass) -> {
				renderPass.drawZoneAlpha(ctx, zone, level, zx, zz);
				return true;
			};
		}

		public void execute(WorldViewContext ctx, Zone zone, int level, int zx, int zz) {
			this.ctx   = ctx;
			this.zone  = zone;
			this.level = level;
			this.zx    = zx;
			this.zz    = zz;
			execute();
		}
	}

	public final class DrawPassFunction extends BaseRenderPassFunction {

		private WorldViewContext ctx;
		private int pass;

		private DrawPassFunction() {
			super("drawPass", true, false);
			consumer = (renderPass) -> {
				renderPass.drawPass(ctx, pass);
				return true;
			};
		}

		public void execute(WorldViewContext ctx, int pass) {
			this.ctx  = ctx;
			this.pass = pass;
			execute();
		}
	}

	public final class DrawFunction extends BaseRenderPassFunction {

		private RenderState renderState;
		private DrawFunction() {
			super("draw", true, false);

			consumer = (renderPass) -> {
				renderPass.draw(renderState);
				return true;
			};
		}

		public void execute(RenderState renderState) {
			this.renderState = renderState;
			execute();
		}
	}

	public final class PostDrawFunction extends BaseRenderPassFunction {
		private RenderState renderState;

		private PostDrawFunction() {
			super("postDraw", true, false);
			consumer = (renderPass) -> {
				renderPass.postDraw(renderState);
				return true;
			};
		}

		public void execute(RenderState renderState) {
			this.renderState = renderState;
			execute();
		}
	}

	@FunctionalInterface
	private interface RenderPassConsumer {
		boolean accept(RenderPass renderPass) throws Throwable;
	}

	@RequiredArgsConstructor
	public abstract class BaseRenderPassFunction {
		private final String  action;
		private final boolean checkGL;
		private final boolean handleExceptions;
		protected RenderPassConsumer consumer;

		public boolean execute() {
			return internalExecute(this);
		}
	}
}