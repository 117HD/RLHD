package rs117.hd.scene;

public class CustomSkyboxEntry {
	public String name;
	public String type; // "equirect" | "cubemap" | "cubemap_cross"
	public String file; // equirect / cubemap_cross
	public String[] faces; // cubemap, length 6: +X,-X,+Y,-Y,+Z,-Z
}
