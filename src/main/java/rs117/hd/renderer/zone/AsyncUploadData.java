package rs117.hd.renderer.zone;

import com.google.inject.Injector;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.utils.PrimitiveIntArray;

@Slf4j
public class AsyncUploadData {
	@Inject
	SceneUploader sceneUploader;

	@Inject
	FacePrioritySorter facePrioritySorter;

	@Inject
	Client client;

	public final ReentrantLock syncLock = new ReentrantLock();
	public final PrimitiveIntArray visibleFaces = new PrimitiveIntArray();
	public final PrimitiveIntArray culledFaces = new PrimitiveIntArray();

	public final AsyncCachedModel[] models;
	public final ConcurrentLinkedDeque<AsyncCachedModel> freeModels = new ConcurrentLinkedDeque<>();
	public final AtomicInteger freeModelsCount = new AtomicInteger();

	AsyncUploadData(int bufferCount, Injector injector) {
		models = new AsyncCachedModel[bufferCount];
		for (int i = 0; i < bufferCount; i++) {
			models[i] = new AsyncCachedModel(this);
			freeModels.add(models[i]);
		}
		freeModelsCount.set(bufferCount);
		injector.injectMembers(this);
	}
}
