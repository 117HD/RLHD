package rs117.hd.renderer;

import rs117.hd.utils.buffer.GLVAOList;

public class ZoneCluster {
	public final int sizeX, sizeZ;
	public Zone[][] zones;
	public GLVAOList vaoO, vaoA;
	public GLVAOList vaoPO;

	public ZoneCluster(int sizeX, int sizeZ)
	{
		this.sizeX = sizeX;
		this.sizeZ = sizeZ;
		zones = new Zone[sizeX][sizeZ];
		for (int x = 0; x < sizeX; ++x)
		{
			for (int z = 0; z < sizeZ; ++z)
			{
				zones[x][z] = new Zone();
			}
		}
		vaoO = new GLVAOList();
		vaoA = new GLVAOList();
		vaoPO = new GLVAOList();
	}

	public void destroy()
	{
		for (int x = 0; x < sizeX; ++x)
		{
			for (int z = 0; z < sizeZ; ++z)
			{
				zones[x][z].destroy();
			}
		}
		vaoO.destroy();
		vaoA.destroy();
		vaoPO.destroy();
	}
}
