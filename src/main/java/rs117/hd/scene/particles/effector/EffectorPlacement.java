package rs117.hd.scene.particles.effector;

import lombok.Value;

@Value
public class EffectorPlacement {
	int worldX;
	int worldY;
	int plane;
	String effectorId;
}
