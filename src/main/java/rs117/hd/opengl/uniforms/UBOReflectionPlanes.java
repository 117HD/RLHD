package rs117.hd.opengl.uniforms;

import javax.inject.Singleton;
import rs117.hd.renderer.zone.passes.ReflectionPass;
import rs117.hd.utils.Camera;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL33C.*;

@Singleton
public class UBOReflectionPlanes extends UniformBuffer<GLBuffer> {
	public final WaterPlaneStruct[] planes = addStructs(new WaterPlaneStruct[ReflectionPass.MAX_REFLECTION_RENDERS], WaterPlaneStruct::new);
	public final Property activePlanes = addProperty(PropertyType.Int, "activePlanes");
	public final Property cullingPlane = addProperty(PropertyType.FVec4, "cullingPlane");

	public UBOReflectionPlanes() {
		super(GL_DYNAMIC_DRAW);
	}

	public class WaterPlaneStruct extends StructProperty {
		public Camera.CameraStruct camera = addStruct(new Camera.CameraStruct());
		public Property height = addProperty(PropertyType.Float, "height");
	}
}
