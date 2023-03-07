package rs117.hd.scene.area;

import lombok.Data;
import net.runelite.api.Constants;
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
    private final int color;

	public int uploadFaces(GpuIntBuffer vertexBuffer, GpuFloatBuffer uvBuffer, GpuFloatBuffer normalBuffer) {
		int color = 127;
		int sceneMin = Perspective.LOCAL_TILE_SIZE;
		int sceneMax = (Constants.SCENE_SIZE - 1) * Perspective.LOCAL_TILE_SIZE;
		int maxDepth = ProceduralGenerator.depthLevelSlope[ProceduralGenerator.depthLevelSlope.length - 1];
		// TODO: Scaling up much further leads to precision issues. A different method is needed for a perfectly flat horizon
		int horizonRadius = 8000 * Perspective.LOCAL_TILE_SIZE;

		int materialData, terrainData;

		if (materialBelow != null) {
			materialData = SceneUploader.packMaterialData(Material.DIRT_1, false, ModelOverride.NONE);
			terrainData = SceneUploader.packTerrainData(maxDepth, waterType, 0);

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


		if (materialBelow != null) {
			materialData = SceneUploader.packMaterialData(Material.DIRT_1, false, ModelOverride.NONE);
			terrainData = SceneUploader.packTerrainData(maxDepth, waterType, 0);

			// Scene underwater bounding box
			vertexBuffer.put(sceneMax, maxDepth, sceneMin, color);
			vertexBuffer.put(sceneMin, maxDepth, sceneMin, color);
			vertexBuffer.put(sceneMin, 0, sceneMin, color);
			vertexBuffer.put(sceneMax, maxDepth, sceneMin, color);
			vertexBuffer.put(sceneMin, 0, sceneMin, color);
			vertexBuffer.put(sceneMax, 0, sceneMin, color);

			vertexBuffer.put(sceneMax, maxDepth, sceneMax, color);
			vertexBuffer.put(sceneMax, maxDepth, sceneMin, color);
			vertexBuffer.put(sceneMax, 0, sceneMin, color);
			vertexBuffer.put(sceneMax, maxDepth, sceneMax, color);
			vertexBuffer.put(sceneMax, 0, sceneMin, color);
			vertexBuffer.put(sceneMax, 0, sceneMax, color);

			vertexBuffer.put(sceneMin, maxDepth, sceneMax, color);
			vertexBuffer.put(sceneMax, maxDepth, sceneMax, color);
			vertexBuffer.put(sceneMax, 0, sceneMax, color);
			vertexBuffer.put(sceneMin, maxDepth, sceneMax, color);
			vertexBuffer.put(sceneMax, 0, sceneMax, color);
			vertexBuffer.put(sceneMin, 0, sceneMax, color);

			vertexBuffer.put(sceneMin, maxDepth, sceneMin, color);
			vertexBuffer.put(sceneMin, maxDepth, sceneMax, color);
			vertexBuffer.put(sceneMin, 0, sceneMax, color);
			vertexBuffer.put(sceneMin, maxDepth, sceneMin, color);
			vertexBuffer.put(sceneMin, 0, sceneMax, color);
			vertexBuffer.put(sceneMin, 0, sceneMin, color);

			for (int i = 0; i < 4 * 2 * 3; i++) {
				uvBuffer.put(materialData, 0, 0, 0);
				normalBuffer.put(0, 1, 0, terrainData);
			}

			// Black scene bottom
			color = 0; // black
			materialData = SceneUploader.packMaterialData(Material.NONE, false, ModelOverride.NONE);
			terrainData = SceneUploader.packTerrainData(0, WaterType.NONE, 0);

			vertexBuffer.put(sceneMin, maxDepth, sceneMin, color);
			vertexBuffer.put(sceneMax, maxDepth, sceneMin, color);
			vertexBuffer.put(sceneMax, maxDepth, sceneMax, color);
			vertexBuffer.put(sceneMin, maxDepth, sceneMin, color);
			vertexBuffer.put(sceneMax, maxDepth, sceneMax, color);
			vertexBuffer.put(sceneMin, maxDepth, sceneMax, color);

			for (int i = 0; i < 2 * 3; i++) {
				uvBuffer.put(materialData, 0, 0, 0);
				normalBuffer.put(0, 1, 0, terrainData);
			}
		}

		return materialBelow == null ? 4 : 14;
	}
}
