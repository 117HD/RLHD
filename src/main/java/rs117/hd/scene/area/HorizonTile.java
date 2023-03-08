package rs117.hd.scene.area;

import lombok.Data;
import net.runelite.api.Perspective;
import rs117.hd.data.WaterType;
import rs117.hd.data.materials.Material;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.SceneUploader;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.buffer.GpuFloatBuffer;
import rs117.hd.utils.buffer.GpuIntBuffer;

@Data
public class HorizonTile {
    private final Material material;
    private final Material materialBelow;
    private final Boolean isOverlay;
    private final WaterType waterType;
    private final int height;
    private final int depth = ProceduralGenerator.depthLevelSlope[ProceduralGenerator.depthLevelSlope.length - 1];
    private final int color;

	public int uploadFaces(GpuIntBuffer vertexBuffer, GpuFloatBuffer uvBuffer, GpuFloatBuffer normalBuffer) {
		int color = 127;
		// TODO: Scaling up much further leads to precision issues. A different method is needed for a perfectly flat horizon
		int horizonRadius = 8000 * Perspective.LOCAL_TILE_SIZE;

		int materialData, terrainData, faceCount = 2;

		if (materialBelow != null) {
			faceCount += 2;
			materialData = SceneUploader.packMaterialData(Material.DIRT_1, false, ModelOverride.NONE);
			terrainData = SceneUploader.packTerrainData(depth, waterType, 0);

			// North-west
			vertexBuffer.put(-horizonRadius, 0, horizonRadius, color);
			uvBuffer.put(materialData, -horizonRadius, horizonRadius, 0);
			// South-west
			vertexBuffer.put(-horizonRadius, 0, -horizonRadius, color);
			uvBuffer.put(materialData, -horizonRadius, -horizonRadius, 0);
			// North-east
			vertexBuffer.put(horizonRadius, 0, horizonRadius, color);
			uvBuffer.put(materialData, horizonRadius, horizonRadius, 0);
			// South-west
			vertexBuffer.put(-horizonRadius, 0, -horizonRadius, color);
			uvBuffer.put(materialData, -horizonRadius, -horizonRadius, 0);
			// South-east
			vertexBuffer.put(horizonRadius, 0, -horizonRadius, color);
			uvBuffer.put(materialData, horizonRadius, -horizonRadius, 0);
			// North-east
			vertexBuffer.put(horizonRadius, 0, horizonRadius, color);
			uvBuffer.put(materialData, horizonRadius, horizonRadius, 0);
			for (int i = 0; i < 6; i++)
				normalBuffer.put(0, 1, 0, terrainData);
		}

		materialData = SceneUploader.packMaterialData(material, true, ModelOverride.NONE);
		terrainData = SceneUploader.packTerrainData(0, waterType, 0);

		// North-west
		vertexBuffer.put(-horizonRadius, 0, horizonRadius, color);
		uvBuffer.put(materialData, -horizonRadius, horizonRadius, 0);
		// South-west
		vertexBuffer.put(-horizonRadius, 0, -horizonRadius, color);
		uvBuffer.put(materialData, -horizonRadius, -horizonRadius, 0);
		// North-east
		vertexBuffer.put(horizonRadius, 0, horizonRadius, color);
		uvBuffer.put(materialData, horizonRadius, horizonRadius, 0);
		// South-west
		vertexBuffer.put(-horizonRadius, 0, -horizonRadius, color);
		uvBuffer.put(materialData, -horizonRadius, -horizonRadius, 0);
		// South-east
		vertexBuffer.put(horizonRadius, 0, -horizonRadius, color);
		uvBuffer.put(materialData, horizonRadius, -horizonRadius, 0);
		// North-east
		vertexBuffer.put(horizonRadius, 0, horizonRadius, color);
		uvBuffer.put(materialData, horizonRadius, horizonRadius, 0);

		for (int i = 0; i < 6; i++)
			normalBuffer.put(0, 1, 0, terrainData);

		return faceCount;
	}
}
