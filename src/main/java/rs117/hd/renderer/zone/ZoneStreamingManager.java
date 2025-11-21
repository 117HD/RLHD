package rs117.hd.renderer.zone;

import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.scene.ProceduralGenerator;

import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static rs117.hd.HdPlugin.PROCESSOR_COUNT;
import static rs117.hd.renderer.zone.ZoneRenderer.eboAlpha;

@Singleton
@Slf4j
public class ZoneStreamingManager {
	private static final LinkedBlockingDeque<WorkItem> WORK_ITEM_POOL = new LinkedBlockingDeque<>();
	private static final LinkedBlockingDeque<WorkHandle> WORK_HANDLE_POOL = new LinkedBlockingDeque<>();
	private static final LinkedBlockingDeque<ClientCallbackItem> CLIENT_CALLBACK_POOL = new LinkedBlockingDeque<>();

	static class WorkItem {
		public WorldViewContext viewContext;
		public ZoneSceneContext sceneContext;
		public int x, z;
		public Zone zone;
		public boolean highPriority;
		public WorkHandle handle;
	}

	static class ClientCallbackItem {
		final Semaphore seme = new Semaphore(0);
		Runnable callback;
		boolean processNextFrame;
	}

	public class WorkHandle {
		private boolean canceled;
		@Getter
		private boolean isComplete;
		private final Semaphore semaphore = new Semaphore(1);

		public void complete() throws InterruptedException {
			if(isComplete) return;
			if(client.isClientThread()) {
				while(semaphore.tryAcquire()) {
					processPendingClientCallbacks(false);
				}
			} else {
				semaphore.acquire();
			}
		}

		public void cancel() {
			canceled = true;
		}

		public void release() {
			if(canceled) return;
			WORK_HANDLE_POOL.push(this);
		}
	}

	@Inject
	private HdPlugin plugin;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ProceduralGenerator  proceduralGenerator;

	private final LinkedBlockingDeque<WorkItem> workQueue = new LinkedBlockingDeque<>();
	private final LinkedBlockingDeque<ClientCallbackItem> highPriorityClientCallbacks = new LinkedBlockingDeque<>();
	private final LinkedBlockingDeque<ClientCallbackItem> clientCallbacks = new LinkedBlockingDeque<>();

	private final int workerCount = PROCESSOR_COUNT - 1;
	private Thread[] workers;
	private final Semaphore resumeSema = new Semaphore(0);
	private transient boolean paused;
	private transient boolean active;
	private long clientCallbackElapsed;
	private boolean clientInvokeScheduled;

	public void initialize() {
		workers = new Thread[workerCount];
		paused = false;
		active = true;

		for(int i = 0; i < workerCount; i++) {
			Thread newWorker = workers[i] = new Thread(this::workerRun);
			newWorker.setPriority(Thread.MAX_PRIORITY);
			newWorker.setName("117HD - Streaming Worker  " + i);
			newWorker.start();
		}
	}

	@SneakyThrows
	public WorkHandle queueZone(WorldViewContext viewContext, ZoneSceneContext sceneContext, Zone zone, int x, int z, boolean highPriority){
		assert viewContext != null : "WorldViewContext cant be null";
		WorkItem newItem = WORK_ITEM_POOL.peek() != null ? WORK_ITEM_POOL.poll() : new WorkItem();
		newItem.viewContext = viewContext;
		newItem.sceneContext = sceneContext;
		newItem.zone = zone;
		newItem.zone.isDeferred = true;
		newItem.x = x;
		newItem.z = z;
		newItem.highPriority = highPriority;

		newItem.handle = WORK_HANDLE_POOL.peek() != null ? WORK_HANDLE_POOL.poll() : new WorkHandle();
		newItem.handle.canceled = false;
		newItem.handle.isComplete = false;
		newItem.handle.semaphore.drainPermits();

		if(highPriority) {
			workQueue.putFirst(newItem);
		} else {
			workQueue.putLast(newItem);
		}

		return newItem.handle;
	}

	public int getZoneStreamingCount() {
		return workQueue.size();
	}

	public void resumeStreaming() {
		if(!paused) return;
		paused = false;
		resumeSema.release(workerCount);
	}

	public boolean isPaused() { return paused; }

	@SneakyThrows
	public void pauseStreaming() {
		if(paused) return;
		resumeSema.drainPermits();
		paused = true;
		// TODO: Do we need to check that its actually paused or will be safe enough
	}

	@SneakyThrows
	public void shutdown() {
		active = false;
		workQueue.clear();
		for(int i = 0; i < workerCount; i++) {
			workers[i].interrupt();
			workers[i].join();
		}
	}

	@SneakyThrows
	private void workerHandlePaused() {
		if(!paused) return;
		// Signal that where paused, then wait for the resume signal
		resumeSema.acquire();
	}

	private void workerRun() {
		final SceneUploader uploader = plugin.getInjector().getInstance(SceneUploader.class);
		uploader.setStreamingUploaderCallback(this::workerHandlePaused);
		while (active) {
			final WorkItem work;
			try {
				if (workQueue.isEmpty())
					uploader.clear();
				workerHandlePaused();

				work = workQueue.take();
				workerHandlePaused();

				if (work.handle.canceled) {
					WORK_ITEM_POOL.put(work);
					WORK_HANDLE_POOL.put(work.handle);
					continue;
				}
			} catch (InterruptedException e) {
				log.debug("Interrupt caught");
				continue;
			}

			try {
				if (work.zone.needsTerrainGen) {
					proceduralGenerator.asyncProcGenTask.get(); // TODO: replace this with something like proceduralGenerator.waitForAsyncProcGen();
					proceduralGenerator.generateTerrainDataForZone(work.sceneContext, work.x, work.z);
					work.zone.needsTerrainGen = false;
				}

				final Zone zone = work.zone.isRebuild ? new Zone() : work.zone;

				uploader.setScene(work.sceneContext.scene);
				uploader.estimateZoneSize(work.sceneContext, zone, work.x, work.z);
				workerHandlePaused();

				queueClientCallback(
					work.highPriority, false, () -> {
						VBO o = null, a = null;
						int sz = zone.sizeO * Zone.VERT_SIZE * 3;
						if (sz > 0) {
							o = new VBO(sz);
							o.initialize(GL_STATIC_DRAW);
							o.map();
						}

						sz = zone.sizeA * Zone.VERT_SIZE * 3;
						if (sz > 0) {
							a = new VBO(sz);
							a.initialize(GL_STATIC_DRAW);
							a.map();
						}

						zone.initialize(o, a, eboAlpha);
						zone.setMetadata(work.viewContext, work.sceneContext, work.x, work.z);
					}
				);
				workerHandlePaused();

				uploader.uploadZone(work.sceneContext, zone, work.x, work.z);
				workerHandlePaused();

				queueClientCallback(
					work.highPriority, !work.highPriority, () -> {
						zone.unmap();
						zone.initialized = true;
						zone.dirty = work.zone.isRebuild;
						zone.isDeferred = false;

						if(work.zone.isRebuild) {
							work.viewContext.zones[work.x][work.z] = zone;
						}

						work.handle.semaphore.release();
					}
				);
				workerHandlePaused();

				WORK_ITEM_POOL.put(work);
			} catch (Exception ex) {
				log.warn("Caught exception whilst processing zone [{}, {}] worldId [{}]", work.x, work.z, work.viewContext.worldViewId, ex);
			}
		}
	}

	@SneakyThrows
	private void queueClientCallback(boolean highPriority, boolean processNextFrame, Runnable callback) {
		ClientCallbackItem newItem = CLIENT_CALLBACK_POOL.peek() != null ? CLIENT_CALLBACK_POOL.poll() : new ClientCallbackItem();
		newItem.seme.drainPermits();
		newItem.callback = callback;
		newItem.processNextFrame = processNextFrame;;

		if(highPriority) {
			highPriorityClientCallbacks.add(newItem);
		} else {
			clientCallbacks.add(newItem);
		}

		if(!clientInvokeScheduled) {
			clientInvokeScheduled = true;
			clientThread.invoke(() -> {
				clientInvokeScheduled = false;
				processPendingClientCallbacks(true);
			});
		}

		newItem.seme.acquire();
	}

	private void flushClientCallbackQueue(LinkedBlockingDeque<ClientCallbackItem> callbacks, boolean processNextFrame, long timeoutNs) {
		long start = System.nanoTime();
		int processCount = callbacks.size();
		int processed = 0;
		while(true) {
			if(processed >= processCount || (timeoutNs > 0 && clientCallbackElapsed >= timeoutNs)) {
				return;
			}

			ClientCallbackItem pair = callbacks.poll();
			if(pair == null) {
				return;
			}

			processed++;
			if(pair.processNextFrame && !processNextFrame) {
				callbacks.add(pair); // Add it back onto the end
				continue;
			}

			pair.callback.run();
			pair.seme.release();
			clientCallbackElapsed += System.nanoTime() - start;
		}
	}

	public void processPendingClientCallbacks(boolean processNextFrame) {
		if (clientCallbacks.isEmpty() && highPriorityClientCallbacks.isEmpty())
			return;

		if(processNextFrame)
			clientCallbackElapsed = 0;

		flushClientCallbackQueue(highPriorityClientCallbacks, processNextFrame, -1);
		flushClientCallbackQueue(clientCallbacks, processNextFrame, 500000); // Timeout after 0.5 ms
	}
}
