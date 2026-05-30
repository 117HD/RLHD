package rs117.hd.scene.particles.effector;

import lombok.Value;

@Value
public class ActiveEffectorState {
	String id;
	float x;
	float y;
	float z;
	EffectorDefinition def;
}
