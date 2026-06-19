package rs117.hd.overlays;

import javax.annotation.Nullable;
import net.runelite.api.Client;
import rs117.hd.HdPlugin;
import rs117.hd.opengl.uniforms.UBOWorldViews;
import rs117.hd.utils.Mat4;

import static rs117.hd.utils.MathUtils.copyTo;

public final class SubSceneOverlayHelper {
	private SubSceneOverlayHelper() {}

	public static float[] buildScreenProjectionMatrix(Client client, HdPlugin plugin) {
		float[] projectionMatrix = Mat4.identity();
		int viewportWidth = client.getViewportWidth();
		int viewportHeight = client.getViewportHeight();
		Mat4.mul(projectionMatrix, Mat4.translate(client.getViewportXOffset(), client.getViewportYOffset(), 0));
		Mat4.mul(projectionMatrix, Mat4.scale(viewportWidth, viewportHeight, 1));
		Mat4.mul(projectionMatrix, Mat4.translate(.5f, .5f, .5f));
		Mat4.mul(projectionMatrix, Mat4.scale(.5f, -.5f, .5f));
		Mat4.mul(projectionMatrix, plugin.viewProjMatrix);
		return projectionMatrix;
	}

	public static void projectScenePoint(
		@Nullable UBOWorldViews.WorldViewStruct worldViewStruct,
		float[] pos
	) {
		if (worldViewStruct == null)
			return;

		float[] projected = { pos[0], pos[1], pos[2], 1.0f };
		worldViewStruct.project(projected);
		pos[0] = projected[0];
		pos[1] = projected[1];
		pos[2] = projected[2];
	}

	public static boolean projectScenePointToScreen(
		Client client,
		HdPlugin plugin,
		float[] scenePos,
		@Nullable UBOWorldViews.WorldViewStruct worldViewStruct,
		float[] projectionMatrix,
		float[] out
	) {
		copyTo(out, scenePos);
		out[3] = 1;
		projectScenePoint(worldViewStruct, out);
		Mat4.projectVec(out, projectionMatrix, out);
		return out[3] > 0;
	}
}
