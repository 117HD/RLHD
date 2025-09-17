package rs117.hd.renderer;

import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;

@Slf4j
@Singleton
public class DebugRenderer implements Renderer {
	// With the ZBUF flag:
	// game state changed: LOADING (state: 25, 2 step: false)
	// Graphics reset!
	// draw(overlaySrgba=0)
	// draw(overlaySrgba=0)
	// Roof override duration: 1.700 ms
	// Roof building duration: 4.537 ms
	// loadScene(it@ef0197a)
	// loadScene(dr@34152eeb, it@ef0197a)
	// swapScene(it@ef0197a)
	// game state changed: LOGGED_IN (state: 30, 2 step: false)
	// invalidateZone(it@ef0197a, ...) # may invalidate the same zone multiple times
	// drawScene(cameraPos=[6366.9638671875, -2390.810546875, 5434.96826171875], cameraOri=[1.214730143547058, -0.12487385421991348], plane=0)
	// preSceneDraw(it@ef0197a, cameraPos=[6366.964, -2390.8105, 5434.9683], cameraOri=[1.2147301, -0.124873854], minLevel=0, level=0, maxLevel=0, hideRoofIds=[37])
	// drawTemp(iv@7318b76c, it@ef0197a, gameObject=..., model=jn@4c96e8d6) # probably drawing all temp models in the zone, unique gameobj, reuses model instance
	// drawZone(null, it@ef0197a, pass=0, zoneX=11, zoneZ=10)
	// drawTemp(iv@7318b76c, it@ef0197a, gameObject=jm@1a70310e, model=jn@4c96e8d6)
	// drawZone(null, it@ef0197a, pass=0, zoneX=10, zoneZ=10)
	// drawTemp(iv@7318b76c, it@ef0197a, gameObject=jm@43fb4ae6, model=jn@4c96e8d6)
	// drawZone(null, it@ef0197a, pass=0, zoneX=11, zoneZ=9)
	// drawZone(null, it@ef0197a, pass=0, zoneX=10, zoneZ=9)
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=..., renderable=..., model=jn@4c96e8d6, orientation=0, modelPos=[7104, -296, 6720]) # reuses model instance
	// drawTemp(iv@7318b76c, it@ef0197a, gameObject=..., model=jn@4c96e8d6)
	// drawZone(null, it@ef0197a, pass=0, zoneX=11, zoneZ=11)
	// drawTemp(iv@7318b76c, it@ef0197a, gameObject=..., model=jn@4c96e8d6)
	// drawZone(null, it@ef0197a, pass=0, zoneX=12, zoneZ=10)
	// drawTemp(iv@7318b76c, it@ef0197a, gameObject=jm@df9db7d, model=jn@4c96e8d6)
	// drawZone(null, it@ef0197a, pass=0, zoneX=10, zoneZ=11)
	// drawZone(null, it@ef0197a, pass=0, zoneX=12, zoneZ=9)
	// drawZone(null, it@ef0197a, pass=0, zoneX=9, zoneZ=10)
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=..., renderable=..., model=jn@4c96e8d6, orientation=0, modelPos=[7232, -296, 6720]) # reuses model instance
	// drawTemp(iv@7318b76c, it@ef0197a, gameObject=jm@451e9e61, model=jn@4c96e8d6)
	// drawZone(null, it@ef0197a, pass=0, zoneX=12, zoneZ=11)
	// drawZone(null, it@ef0197a, pass=0, zoneX=11, zoneZ=8)
	// drawZone(null, it@ef0197a, pass=0, zoneX=9, zoneZ=9)
	// drawZone(null, it@ef0197a, pass=0, zoneX=10, zoneZ=8)
	// drawZone(null, it@ef0197a, pass=0, zoneX=9, zoneZ=11)
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jm@2f1496a6, renderable=ci@26d7d4d, model=jn@4c96e8d6, orientation=0, modelPos=[6336, -296, 7616])
	// drawZone(null, it@ef0197a, pass=0, zoneX=11, zoneZ=12)
	// drawZone(null, it@ef0197a, pass=0, zoneX=12, zoneZ=8)
	// drawZone(null, it@ef0197a, pass=0, zoneX=13, zoneZ=10)
	// drawTemp(iv@7318b76c, it@ef0197a, gameObject=jm@7bdaec91, model=jn@4c96e8d6)
	// drawZone(null, it@ef0197a, pass=0, zoneX=10, zoneZ=12)
	// drawZone(null, it@ef0197a, pass=0, zoneX=13, zoneZ=9)
	// drawZone(null, it@ef0197a, pass=0, zoneX=9, zoneZ=8)
	// drawTemp(iv@7318b76c, it@ef0197a, gameObject=jm@4a86881d, model=jn@4c96e8d6)
	// drawZone(null, it@ef0197a, pass=0, zoneX=..., zoneZ=...)
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jm@41ac864d, renderable=ci@28420c20, model=jn@4c96e8d6, orientation=0, modelPos=[3904, -284, 3136])
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jm@4d0c3dd, renderable=ci@7a68753a, model=jn@4c96e8d6, orientation=0, modelPos=[4032, -284, 3136])
	// drawZone(null, it@ef0197a, pass=0, zoneX=8, zoneZ=8)
	// drawZone(null, it@ef0197a, pass=0, zoneX=10, zoneZ=13)
	// drawZone(null, it@ef0197a, pass=0, zoneX=14, zoneZ=10)
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jm@5f531ad4, renderable=ci@67c83e12, model=jn@4c96e8d6, orientation=0, modelPos=[4160, -270, 2880])
	// drawZone(null, it@ef0197a, pass=0, zoneX=..., zoneZ=...)
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jm@6504c71d, renderable=ci@5c7cbf22, model=jn@4c96e8d6, orientation=0, modelPos=[9536, -312, 4160])
	// drawZone(null, it@ef0197a, pass=0, zoneX=14, zoneZ=8)
	// drawZone(null, it@ef0197a, pass=0, zoneX=7, zoneZ=9)
	// drawZone(null, it@ef0197a, pass=0, zoneX=7, zoneZ=11)
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=..., renderable=..., model=jn@4c96e8d6, orientation=0, modelPos=[3392, -254, 2752])
	// drawZone(null, it@ef0197a, pass=0, zoneX=..., zoneZ=...)
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jm@531a0e3c, renderable=ci@b7f40ab, model=jn@4c96e8d6, orientation=0, modelPos=[2240, -284, 7232])
	// drawZone(null, it@ef0197a, pass=0, zoneX=..., zoneZ=...)
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jm@566fce09, renderable=ci@4a9d0a09, model=jn@4c96e8d6, orientation=0, modelPos=[1152, -256, 4480])
	// drawZone(null, it@ef0197a, pass=0, zoneX=..., zoneZ=...)
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jm@49759688, renderable=ci@38fefd15, model=jn@4c96e8d6, orientation=0, modelPos=[12288, -96, 2944])
	// drawZone(null, it@ef0197a, pass=0, zoneX=..., zoneZ=...)
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jm@10497385, renderable=ci@32a6fec7, model=jn@4c96e8d6, orientation=0, modelPos=[-192, -504, 7616])
	// drawZone(null, it@ef0197a, pass=0, zoneX=..., zoneZ=...)
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jr@5782efcd, renderable=ci@18dbb67d, model=jn@4c96e8d6, orientation=0, modelPos=[-64, -536, 9153])
	// drawZone(null, it@ef0197a, pass=0, zoneX=4, zoneZ=13)
	// drawZone(null, it@ef0197a, pass=0, zoneX=18, zoneZ=8)
	// drawZone(null, it@ef0197a, pass=0, zoneX=15, zoneZ=16)
	// drawZone(null, it@ef0197a, pass=0, zoneX=13, zoneZ=17)
	// drawZone(null, it@ef0197a, pass=0, zoneX=17, zoneZ=14)
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jm@43552bb8, renderable=ci@4600a5d3, model=jn@4c96e8d6, orientation=0, modelPos=[14080, -312, 7360])
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jm@63cc659, renderable=ci@4311f0bd, model=jn@4c96e8d6, orientation=0, modelPos=[14336, -312, 7360])
	// drawZone(null, it@ef0197a, pass=0, zoneX=18, zoneZ=12)
	// drawZone(null, it@ef0197a, pass=0, zoneX=8, zoneZ=17)
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jr@29731b8c, renderable=ci@6a9e0d4c, model=jn@4c96e8d6, orientation=0, modelPos=[-1857, -400, 5312])
	// drawZone(null, it@ef0197a, pass=0, zoneX=3, zoneZ=10)
	// drawZone(null, it@ef0197a, pass=0, zoneX=5, zoneZ=15)
	// drawZone(null, it@ef0197a, pass=0, zoneX=6, zoneZ=16)
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jr@a701048, renderable=ci@46289da3, model=jn@4c96e8d6, orientation=0, modelPos=[-1729, -504, 6336])
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jr@77365191, renderable=ci@6dd8c656, model=jn@4c96e8d6, orientation=0, modelPos=[-1729, -504, 6592])
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jr@67a069f5, renderable=ci@65e73527, model=jn@4c96e8d6, orientation=0, modelPos=[-1729, -504, 7104])
	// drawZone(null, it@ef0197a, pass=0, zoneX=3, zoneZ=11)
	// drawZone(null, it@ef0197a, pass=0, zoneX=14, zoneZ=17)
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=..., renderable=..., model=jn@4c96e8d6, orientation=0, modelPos=[-559, -536, 9792])
	// drawZone(null, it@ef0197a, pass=0, zoneX=4, zoneZ=14)
	// drawZone(null, it@ef0197a, pass=0, zoneX=18, zoneZ=13)
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jr@2f1e33d1, renderable=ci@19b5595f, model=jn@4c96e8d6, orientation=0, modelPos=[-1856, -504, 7615])
	// drawZone(null, it@ef0197a, pass=0, zoneX=..., zoneZ=...)
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jr@3a2901d4, renderable=ci@7b49f54, model=jn@4c96e8d6, orientation=0, modelPos=[-2607, -504, 6336])
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jr@59eaa4c8, renderable=ci@6aa6610b, model=jn@4c96e8d6, orientation=0, modelPos=[-2607, -504, 6720])
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jr@18f3fa2a, renderable=ci@3b1e985f, model=jn@4c96e8d6, orientation=0, modelPos=[-2240, -504, 6209])
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jm@375eb710, renderable=ci@6e2936c9, model=jn@4c96e8d6, orientation=0, modelPos=[-2496, -504, 6208])
	// drawZone(null, it@ef0197a, pass=0, zoneX=2, zoneZ=11)
	// drawZone(null, it@ef0197a, pass=0, zoneX=17, zoneZ=16)
	// drawZone(null, it@ef0197a, pass=0, zoneX=14, zoneZ=18)
	// drawZone(null, it@ef0197a, pass=0, zoneX=19, zoneZ=13)
	// drawZone(null, it@ef0197a, pass=0, zoneX=16, zoneZ=17)
	// drawZone(null, it@ef0197a, pass=0, zoneX=18, zoneZ=15)
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jr@690cb38c, renderable=ci@261f0292, model=jn@4c96e8d6, orientation=0, modelPos=[-2624, -504, 7615])
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jr@64bbc776, renderable=ci@43033a56, model=jn@4c96e8d6, orientation=0, modelPos=[-2240, -504, 7615])
	// drawZone(null, it@ef0197a, pass=0, zoneX=..., zoneZ=...)
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jm@4f9f6169, renderable=ci@11cae19a, model=jn@4c96e8d6, orientation=0, modelPos=[15808, -312, 5568])
	// drawZone(null, it@ef0197a, pass=0, zoneX=..., zoneZ=...)
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jr@2b7004a2, renderable=ci@717b4a10, model=jn@4c96e8d6, orientation=0, modelPos=[-3904, -504, 8641])
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jr@45dbf97f, renderable=ci@3db066f, model=jn@4c96e8d6, orientation=0, modelPos=[-3520, -504, 8640])
	// drawZone(null, it@ef0197a, pass=0, zoneX=1, zoneZ=13)
	// drawZone(null, it@ef0197a, pass=0, zoneX=18, zoneZ=17)
	// drawZone(null, it@ef0197a, pass=0, zoneX=12, zoneZ=20)
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jr@28a557a2, renderable=ci@325a36c, model=jn@4c96e8d6, orientation=0, modelPos=[16737, -312, 5568])
	// drawZone(null, it@ef0197a, pass=0, zoneX=21, zoneZ=10)
	// drawZone(null, it@ef0197a, pass=0, zoneX=17, zoneZ=18)
	// drawZone(null, it@ef0197a, pass=0, zoneX=6, zoneZ=19)
	// drawZone(null, it@ef0197a, pass=0, zoneX=9, zoneZ=20)
	// drawZone(null, it@ef0197a, pass=0, zoneX=19, zoneZ=16)
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jr@19afb276, renderable=ci@421c3752, model=jn@4c96e8d6, orientation=0, modelPos=[16737, -312, 6464])
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jr@408670f4, renderable=ci@6e96854b, model=jn@4c96e8d6, orientation=0, modelPos=[16737, -312, 6208])
	// drawZone(null, it@ef0197a, pass=0, zoneX=21, zoneZ=11)
	// drawZone(null, it@ef0197a, pass=0, zoneX=13, zoneZ=20)
	// drawZone(null, it@ef0197a, pass=0, zoneX=21, zoneZ=12)
	// drawZone(null, it@ef0197a, pass=0, zoneX=8, zoneZ=20)
	// drawZone(null, it@ef0197a, pass=0, zoneX=3, zoneZ=17)
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jr@a4eb6ed, renderable=ci@29cba651, model=jn@4c96e8d6, orientation=0, modelPos=[-3776, -504, 9407])
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jr@5ab9624c, renderable=ci@df188d, model=jn@4c96e8d6, orientation=0, modelPos=[-3520, -504, 9408])
	// drawZone(null, it@ef0197a, pass=0, zoneX=..., zoneZ=...)
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jr@7fd93fa5, renderable=ci@bd4f721, model=jn@4c96e8d6, orientation=0, modelPos=[-4544, -504, 8640])
	// drawZone(null, it@ef0197a, pass=0, zoneX=..., zoneZ=...)
	// drawDynamic(iv@7318b76c, it@ef0197a, tileObject=jr@1a6e5f15, renderable=ci@628d37a5, model=jn@4c96e8d6, orientation=0, modelPos=[-4544, -504, 9408])
	// drawZone(null, it@ef0197a, pass=0, zoneX=..., zoneZ=...)
	// drawPass(null, it@ef0197a, pass=0)
	// drawZone(null, it@ef0197a, pass=1, zoneX=..., zoneZ=...)
	// drawPass(null, it@ef0197a, pass=1)
	// postSceneDraw(it@ef0197a)
	// draw(overlaySrgba=0)
	// Item spawn 592 (1) location LocalPoint(x=7488, y=6720, worldView=-1)
	// invalidateZone(it@ef0197a, zoneX=12, zoneZ=11)
	// drawScene(cameraPos=[6366.9638671875, -2390.810546875, 5434.96826171875], cameraOri=[1.214730143547058, -0.12487385421991348], plane=0)
	// preSceneDraw(it@ef0197a, cameraPos=[6366.964, -2390.8105, 5434.9683], cameraOri=[1.2147301, -0.124873854], minLevel=0, level=0, maxLevel=0, hideRoofIds=[37])

	// With the ZBUF flag:
	// [Client    ] draw(overlaySrgba=0)
	// [Client    ] draw(overlaySrgba=0)
	// [Map Loader] - Roof override duration: 889.6 μs
	// [Map Loader] - Roof building duration: 1.101 ms
	// [Map Loader] loadScene(it@31e3657b)
	// [Map Loader] loadScene(dr@462d8718, it@31e3657b)
	// [Client    ] swapScene(it@31e3657b)
	// [Client    ] - Game state changed: LOGGED_IN (state: 30, 2 step: false)
	// [Client    ] - Item spawn 229 (1) location LocalPoint(x=6464, y=6208, worldView=-1)
	// [Client    ] invalidateZone(it@31e3657b, zoneX=11, zoneZ=11)
	// [Client    ] drawScene(cameraPos=[5883.29052734375, -1349.32373046875, 7674.08447265625], cameraOri=[0.6701943278312683, -2.64905047416687], plane=0
	// [Client    ] preSceneDraw(it@31e3657b, cameraPos=[5883.2905, -1349.3237, 7674.0845], cameraOri=[0.6701943, -2.6490505], minLevel=0, level=0, maxLevel=0, hideRoofIds=[37]
	// [Client    ] drawTemp(iv@191e479e, it@31e3657b, gameObject=jm@24a592d4, model=jn@41deed5f)
	// [Client    ] drawZone(null, it@31e3657b, pass=0, zoneX=10, zoneZ=12)
	// [Client    ] drawPass(null, it@31e3657b, pass=0)
	// [Client    ] drawZone(null, it@31e3657b, pass=1, zoneX=10, zoneZ=12)
	// [Client    ] drawPass(null, it@31e3657b, pass=1)
	// [Client    ] postSceneDraw(it@31e3657b)
	// [Client    ] draw(overlaySrgba=0)
	// [Client    ] drawScene(cameraPos=[5883.29052734375, -1349.32373046875, 7674.08447265625], cameraOri=[0.6701943278312683, -2.64905047416687], plane=0
	// [Client    ] preSceneDraw(it@31e3657b, cameraPos=[5883.2905, -1349.3237, 7674.0845], cameraOri=[0.6701943, -2.6490505], minLevel=0, level=0, maxLevel=0, hideRoofIds=[37]
	// [Client    ] drawTemp(iv@6c2028e, it@31e3657b, gameObject=jm@37f8a0c8, model=jn@41deed5f)
	// [Client    ] drawZone(null, it@31e3657b, pass=0, zoneX=10, zoneZ=12)
	// [Client    ] drawPass(null, it@31e3657b, pass=0)
	// [Client    ] drawZone(null, it@31e3657b, pass=1, zoneX=10, zoneZ=12)
	// [Client    ] drawPass(null, it@31e3657b, pass=1)
	// [Client    ] postSceneDraw(it@31e3657b)
	// [Client    ] draw(overlaySrgba=0)

	// With the ZBUF flag:
	// [Client    ] draw(overlaySrgba=0)
	// [Client    ] draw(overlaySrgba=0)
	// [Client    ] draw(overlaySrgba=0)
	// [Map Loader] Roof override duration: 4.096 ms
	// [Map Loader] Roof building duration: 4.299 ms
	// [Map Loader] loadScene(it@12b0bed4)
	// [Map Loader] loadScene(dr@1ce3a366, it@12b0bed4)
	// [Client    ] swapScene(it@12b0bed4)
	// [Client    ] Game state changed: LOGGED_IN (state: 30, 2 step: false)
	// [Client    ] drawScene(cameraPos=[5261.19873046875, -1496.7247314453125, 6792.40625], cameraOri=[0.749012291431427, -1.735896110534668], plane=0
	// [Client    ] preSceneDraw(it@12b0bed4, cameraPos=[5261.1987, -1496.7247, 6792.4062], cameraOri=[0.7490123, -1.7358961], minLevel=0, level=0, maxLevel=0, hideRoofIds=[37]
	// [Client    ] drawTemp(iv@71ca8f1f, it@12b0bed4, gameObject=jm@3ff5080, model=jn@22b22b95)
	// [Client    ] drawZone(null, it@12b0bed4, pass=0, zoneX=10, zoneZ=11)
	// [Client    ] drawPass(null, it@12b0bed4, pass=0)
	// [Client    ] drawZone(null, it@12b0bed4, pass=1, zoneX=10, zoneZ=11)
	// [Client    ] drawPass(null, it@12b0bed4, pass=1)
	// [Client    ] postSceneDraw(it@12b0bed4)
	// [Client    ] draw(overlaySrgba=0)
	// [Client    ] drawScene(cameraPos=[5261.19873046875, -1496.7247314453125, 6792.40625], cameraOri=[0.749012291431427, -1.735896110534668], plane=0
	// [Client    ] preSceneDraw(it@12b0bed4, cameraPos=[5261.1987, -1496.7247, 6792.4062], cameraOri=[0.7490123, -1.7358961], minLevel=0, level=0, maxLevel=0, hideRoofIds=[37]
	// [Client    ] drawTemp(iv@689a4299, it@12b0bed4, gameObject=jm@6ec783e1, model=jn@22b22b95)
	// [Client    ] drawZone(null, it@12b0bed4, pass=0, zoneX=10, zoneZ=11)
	// [Client    ] drawPass(null, it@12b0bed4, pass=0)
	// [Client    ] drawZone(null, it@12b0bed4, pass=1, zoneX=10, zoneZ=11)
	// [Client    ] drawPass(null, it@12b0bed4, pass=1)
	// [Client    ] postSceneDraw(it@12b0bed4)
	// [Client    ] draw(overlaySrgba=0)

	// With the ZBUF flag:
	// [Client    ] draw(overlaySrgba=0)
	// [Client    ] draw(overlaySrgba=0)
	// [Map Loader] Roof override duration: 1.777 ms
	// [Map Loader] Roof building duration: 4.134 ms
	// [Map Loader] loadScene(it@7b937885)
	// [Map Loader] loadScene(dr@1ce3a366, it@7b937885)
	// [Client    ] swapScene(it@7b937885)
	// [Client    ] Game state changed: LOGGED_IN (state: 30, 2 step: false)
	// [Client    ] drawScene(cameraPos=[6464.0, -2977.0, 6592.0], cameraOri=[1.570796251296997, 0.0], plane=0
	// [Client    ] preSceneDraw(it@7b937885, cameraPos=[6464.0, -2977.0, 6592.0], cameraOri=[1.5707963, 0.0], minLevel=0, level=0, maxLevel=0, hideRoofIds=[37]
	// [Client    ] drawTemp(iv@414bb9e1, it@7b937885, gameObject=jm@6c2c23a6, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@414bb9e1, it@7b937885, gameObject=jm@1ce2c20c, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@414bb9e1, it@7b937885, gameObject=jm@28239119, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@414bb9e1, it@7b937885, gameObject=jm@34bbb14d, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@414bb9e1, it@7b937885, gameObject=jm@3987856f, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@414bb9e1, it@7b937885, gameObject=jm@337fc2f1, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@414bb9e1, it@7b937885, gameObject=jm@76026de, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@414bb9e1, it@7b937885, gameObject=jm@6e38cea6, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@414bb9e1, it@7b937885, gameObject=jm@6b1192f3, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@414bb9e1, it@7b937885, gameObject=jm@2634ad2a, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@414bb9e1, it@7b937885, gameObject=jm@206439e4, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@414bb9e1, it@7b937885, gameObject=jm@8819eb0, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@414bb9e1, it@7b937885, gameObject=jm@6f601359, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@414bb9e1, it@7b937885, gameObject=jm@6c3ebe62, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@414bb9e1, it@7b937885, gameObject=jm@6d140118, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@414bb9e1, it@7b937885, gameObject=jm@69647614, model=jn@22b22b95)
	// [Client    ] drawZone(null, it@7b937885, pass=0, zoneX=11, zoneZ=11)
	// [Client    ] drawPass(null, it@7b937885, pass=0)
	// [Client    ] drawZone(null, it@7b937885, pass=1, zoneX=11, zoneZ=11)
	// [Client    ] drawPass(null, it@7b937885, pass=1)
	// [Client    ] postSceneDraw(it@7b937885)
	// [Client    ] draw(overlaySrgba=0)
	// [Client    ] drawScene(cameraPos=[6464.0, -2977.0, 6592.0], cameraOri=[1.570796251296997, 0.0], plane=0
	// [Client    ] preSceneDraw(it@7b937885, cameraPos=[6464.0, -2977.0, 6592.0], cameraOri=[1.5707963, 0.0], minLevel=0, level=0, maxLevel=0, hideRoofIds=[37]
	// [Client    ] drawTemp(iv@6f14aba, it@7b937885, gameObject=jm@6c2c23a6, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@6f14aba, it@7b937885, gameObject=jm@1ce2c20c, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@6f14aba, it@7b937885, gameObject=jm@28239119, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@6f14aba, it@7b937885, gameObject=jm@34bbb14d, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@6f14aba, it@7b937885, gameObject=jm@3987856f, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@6f14aba, it@7b937885, gameObject=jm@337fc2f1, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@6f14aba, it@7b937885, gameObject=jm@76026de, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@6f14aba, it@7b937885, gameObject=jm@6e38cea6, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@6f14aba, it@7b937885, gameObject=jm@6b1192f3, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@6f14aba, it@7b937885, gameObject=jm@2634ad2a, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@6f14aba, it@7b937885, gameObject=jm@206439e4, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@6f14aba, it@7b937885, gameObject=jm@8819eb0, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@6f14aba, it@7b937885, gameObject=jm@6f601359, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@6f14aba, it@7b937885, gameObject=jm@6c3ebe62, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@6f14aba, it@7b937885, gameObject=jm@6d140118, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@6f14aba, it@7b937885, gameObject=jm@69647614, model=jn@22b22b95)
	// [Client    ] drawZone(null, it@7b937885, pass=0, zoneX=11, zoneZ=11)
	// [Client    ] drawPass(null, it@7b937885, pass=0)
	// [Client    ] drawZone(null, it@7b937885, pass=1, zoneX=11, zoneZ=11)
	// [Client    ] drawPass(null, it@7b937885, pass=1)
	// [Client    ] postSceneDraw(it@7b937885)
	// [Client    ] draw(overlaySrgba=0)
	// [Client    ] drawScene(cameraPos=[6464.0, -2977.0, 6592.0], cameraOri=[1.570796251296997, 0.0], plane=0
	// [Client    ] preSceneDraw(it@7b937885, cameraPos=[6464.0, -2977.0, 6592.0], cameraOri=[1.5707963, 0.0], minLevel=0, level=0, maxLevel=0, hideRoofIds=[37]
	// [Client    ] drawTemp(iv@56d91bb0, it@7b937885, gameObject=jm@6c2c23a6, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@56d91bb0, it@7b937885, gameObject=jm@1ce2c20c, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@56d91bb0, it@7b937885, gameObject=jm@28239119, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@56d91bb0, it@7b937885, gameObject=jm@34bbb14d, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@56d91bb0, it@7b937885, gameObject=jm@3987856f, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@56d91bb0, it@7b937885, gameObject=jm@337fc2f1, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@56d91bb0, it@7b937885, gameObject=jm@76026de, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@56d91bb0, it@7b937885, gameObject=jm@6e38cea6, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@56d91bb0, it@7b937885, gameObject=jm@6b1192f3, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@56d91bb0, it@7b937885, gameObject=jm@2634ad2a, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@56d91bb0, it@7b937885, gameObject=jm@206439e4, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@56d91bb0, it@7b937885, gameObject=jm@8819eb0, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@56d91bb0, it@7b937885, gameObject=jm@6f601359, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@56d91bb0, it@7b937885, gameObject=jm@6c3ebe62, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@56d91bb0, it@7b937885, gameObject=jm@6d140118, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@56d91bb0, it@7b937885, gameObject=jm@69647614, model=jn@22b22b95)
	// [Client    ] drawZone(null, it@7b937885, pass=0, zoneX=11, zoneZ=11)
	// [Client    ] drawPass(null, it@7b937885, pass=0)
	// [Client    ] drawZone(null, it@7b937885, pass=1, zoneX=11, zoneZ=11)
	// [Client     ]ull, it@7b937885, pass=1)
	// [Client    ] postSceneDraw(it@7b937885)
	// [Client    ] draw(overlaySrgba=0)

	// With the ZBUF flag:
	// [Client    ] draw(overlaySrgba=0)
	// [Client    ] draw(overlaySrgba=0)
	// [Map Loader] Roof override duration: 853.7 μs
	// [Map Loader] Roof building duration: 1.065 ms
	// [Map Loader] loadScene(it@49895b41)
	// [Map Loader] loadScene(dr@1ce3a366, it@49895b41)
	// [Client    ] swapScene(it@49895b41)
	// [Client    ] Game state changed: LOGGED_IN (state: 30, 2 step: false)
	// [Client    ] Item spawn 1973 (1) location LocalPoint(x=4032, y=1728, worldView=-1)
	// [Client    ] Item spawn 1134 (1) location LocalPoint(x=6464, y=6208, worldView=-1)
	// [Client    ] Item spawn 5290 (2) location LocalPoint(x=6464, y=6208, worldView=-1)
	// [Client    ] Item spawn 7209 (2) location LocalPoint(x=6464, y=6208, worldView=-1)
	// [Client    ] Item spawn 3240 (15) location LocalPoint(x=6464, y=6208, worldView=-1)
	// [Client    ] Item spawn 229 (1) location LocalPoint(x=6464, y=6208, worldView=-1)
	// [Client    ] invalidateZone(it@49895b41, zoneX=8, zoneZ=7)
	// [Client    ] invalidateZone(it@49895b41, zoneX=8, zoneZ=7)
	// [Client    ] invalidateZone(it@49895b41, zoneX=8, zoneZ=7)
	// [Client    ] invalidateZone(it@49895b41, zoneX=8, zoneZ=7)
	// [Client    ] invalidateZone(it@49895b41, zoneX=8, zoneZ=7)
	// [Client    ] invalidateZone(it@49895b41, zoneX=8, zoneZ=7)
	// [Client    ] invalidateZone(it@49895b41, zoneX=8, zoneZ=7)
	// [Client    ] invalidateZone(it@49895b41, zoneX=8, zoneZ=7)
	// [Client    ] invalidateZone(it@49895b41, zoneX=8, zoneZ=7)
	// [Client    ] invalidateZone(it@49895b41, zoneX=8, zoneZ=7)
	// [Client    ] invalidateZone(it@49895b41, zoneX=8, zoneZ=7)
	// [Client    ] invalidateZone(it@49895b41, zoneX=8, zoneZ=7)
	// [Client    ] invalidateZone(it@49895b41, zoneX=8, zoneZ=7)
	// [Client    ] invalidateZone(it@49895b41, zoneX=8, zoneZ=7)
	// [Client    ] invalidateZone(it@49895b41, zoneX=8, zoneZ=7)
	// [Client    ] invalidateZone(it@49895b41, zoneX=8, zoneZ=7)
	// [Client    ] invalidateZone(it@49895b41, zoneX=8, zoneZ=7)
	// [Client    ] invalidateZone(it@49895b41, zoneX=8, zoneZ=7)
	// [Client    ] drawScene(cameraPos=[6720.0, -2986.0, 4416.0], cameraOri=[1.5707963705062866, 0.0], plane=3
	// [Client    ] preSceneDraw(it@49895b41, cameraPos=[6720.0, -2986.0, 4416.0], cameraOri=[1.5707964, 0.0], minLevel=0, level=0, maxLevel=3, hideRoofIds=[]
	// [Client    ] drawTemp(iv@14f6a9d4, it@49895b41, gameObject=jm@3c1d7c24, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@14f6a9d4, it@49895b41, gameObject=jm@403edf5e, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@14f6a9d4, it@49895b41, gameObject=jm@7fad8081, model=jn@22b22b95)
	// [Client    ] drawZone(null, it@49895b41, pass=0, zoneX=11, zoneZ=9)
	// [Client    ] drawPass(null, it@49895b41, pass=0)
	// [Client    ] drawZone(null, it@49895b41, pass=1, zoneX=11, zoneZ=9)
	// [Client    ] drawPass(null, it@49895b41, pass=1)
	// [Client    ] postSceneDraw(it@49895b41)
	// [Client    ] draw(overlaySrgba=0)
	// [Client    ] drawScene(cameraPos=[6720.0, -2986.0, 4416.0], cameraOri=[1.5707963705062866, 0.0], plane=3
	// [Client    ] preSceneDraw(it@49895b41, cameraPos=[6720.0, -2986.0, 4416.0], cameraOri=[1.5707964, 0.0], minLevel=0, level=0, maxLevel=3, hideRoofIds=[]
	// [Client    ] drawTemp(iv@5e343ea9, it@49895b41, gameObject=jm@3c1d7c24, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@5e343ea9, it@49895b41, gameObject=jm@99f9517, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@5e343ea9, it@49895b41, gameObject=jm@7fad8081, model=jn@22b22b95)
	// [Client    ] drawZone(null, it@49895b41, pass=0, zoneX=11, zoneZ=9)
	// [Client    ] drawPass(null, it@49895b41, pass=0)
	// [Client    ] drawZone(null, it@49895b41, pass=1, zoneX=11, zoneZ=9)
	// [Client    ] drawPass(null, it@49895b41, pass=1)
	// [Client    ] postSceneDraw(it@49895b41)
	// [Client    ] draw(overlaySrgba=0)
	// [Client    ] drawScene(cameraPos=[6720.0, -2986.0, 4416.0], cameraOri=[1.5707963705062866, 0.0], plane=3
	// [Client    ] preSceneDraw(it@49895b41, cameraPos=[6720.0, -2986.0, 4416.0], cameraOri=[1.5707964, 0.0], minLevel=0, level=0, maxLevel=3, hideRoofIds=[]
	// [Client    ] drawTemp(iv@7d5cc0c3, it@49895b41, gameObject=jm@3c1d7c24, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@7d5cc0c3, it@49895b41, gameObject=jm@99f9517, model=jn@22b22b95)
	// [Client    ] drawTemp(iv@7d5cc0c3, it@49895b41, gameObject=jm@7fad8081, model=jn@22b22b95)
	// [Client    ] drawZone(null, it@49895b41, pass=0, zoneX=11, zoneZ=9)
	// [Client    ] drawPass(null, it@49895b41, pass=0)
	// [Client      (null, it@49895b41, pass=1, zoneX=11, zoneZ=9)
	// [Client    ] drawPass(null, it@49895b41, pass=1)
	// [Client    ] postSceneDraw(it@49895b41)
	// [Client    ] draw(overlaySrgba=0)

	public void loadScene(WorldView worldView, Scene scene) {
		log.debug("loadScene({}, {})", worldView, scene);
	}

	@Override
	public void swapScene(Scene scene) {
		log.debug("swapScene({})", scene);
	}

	@Override
	public void invalidateZone(Scene scene, int zoneX, int zoneZ) {
		log.debug("invalidateZone({}, zoneX={}, zoneZ={})", scene, zoneX, zoneZ);
	}

	@Override
	public void despawnWorldView(WorldView worldView) {
		log.debug("despawnWorldView({})", worldView);
	}

	@Override
	public void preSceneDraw(
		Scene scene,
		float cameraX,
		float cameraY,
		float cameraZ,
		float cameraPitch,
		float cameraYaw,
		int minLevel,
		int level,
		int maxLevel,
		Set<Integer> hideRoofIds
	) {
		log.debug(
			"preSceneDraw({}, cameraPos=[{}, {}, {}], cameraOri=[{}, {}], minLevel={}, level={}, maxLevel={}, hideRoofIds=[{}])",
			scene,
			cameraX,
			cameraY,
			cameraZ,
			cameraPitch,
			cameraYaw,
			minLevel,
			level,
			maxLevel,
			hideRoofIds.stream().map(i -> Integer.toString(i)).collect(
				Collectors.joining(", "))
		);
		if (scene.getWorldViewId() == WorldView.TOPLEVEL)
			scene.setDrawDistance(Constants.EXTENDED_SCENE_SIZE);
	}

	@Override
	public void postSceneDraw(Scene scene) {
		log.debug("postSceneDraw({})", scene);
	}

	@Override
	public void drawPass(Projection entityProjection, Scene scene, int pass) {
		log.debug("drawPass({}, {}, pass={})", entityProjection, scene, pass);
	}

	@Override
	public void drawZoneOpaque(Projection entityProjection, Scene scene, int zx, int zz) {
		log.debug("drawZoneOpaque({}, {}, zx={}, zz={})", entityProjection, scene, zx, zz);
	}

	@Override
	public void drawZoneAlpha(Projection entityProjection, Scene scene, int level, int zx, int zz) {
		log.debug("drawZoneAlpha({}, {}, zx={}, zz={})", entityProjection, scene, zx, zz);
	}

	@Override
	public void drawDynamic(
		Projection worldProjection,
		Scene scene,
		TileObject tileObject,
		Renderable r,
		Model m,
		int orient,
		int x,
		int y,
		int z
	) {
		log.debug(
			"drawDynamic({}, {}, tileObject={}, renderable={}, model={}, orientation={}, modelPos=[{}, {}, {}])",
			worldProjection, scene, tileObject, r, m, orient, x, y, z
		);
	}

	@Override
	public void drawTemp(Projection worldProjection, Scene scene, GameObject gameObject, Model m) {
		log.debug("drawTemp({}, {}, gameObject={}, model={})", worldProjection, scene, gameObject, m);
	}

	@Override
	public void draw(int overlaySrgba) {
		log.debug("draw(overlaySrgba={})", overlaySrgba);
	}

	@Override
	public void draw(Projection projection, Scene scene, Renderable renderable, int orientation, int x, int y, int z, long hash) {
		log.debug(
			"draw({}, {}, {}, orientation={}, modelPos=[{}, {}, {}], hash={})",
			projection, scene, renderable, orientation, x, y, z, hash
		);
	}

	@Override
	public void drawScenePaint(Scene scene, SceneTilePaint paint, int plane, int tileX, int tileZ) {
		log.debug("drawScenePaint({}, {}, plane={}, tileX={}, tileZ={}", scene, paint, plane, tileX, tileZ);
	}

	@Override
	public void drawSceneTileModel(Scene scene, SceneTileModel model, int tileX, int tileZ) {
		log.debug("drawScenePaint({}, {}, tileX={}, tileZ={}", scene, model, tileX, tileZ);
	}

	@Override
	public void drawScene(double cameraX, double cameraY, double cameraZ, double cameraPitch, double cameraYaw, int plane) {
		log.debug(
			"drawScene(cameraPos=[{}, {}, {}], cameraOri=[{}, {}], plane={})",
			cameraX, cameraY, cameraZ, cameraPitch, cameraYaw, plane
		);
	}

	@Override
	public void postDrawScene() {
		log.debug("postDrawScene()");
	}

	@Override
	public void animate(Texture texture, int diff) {
		log.debug("animate({}, diff={})", texture, diff);
	}

	@Override
	public void loadScene(Scene scene) {
		log.debug("loadScene({})", scene);
		scene.getExtendedTiles();
		scene.getTileHeights();
	}

	@Override
	public boolean tileInFrustum(
		Scene scene,
		float pitchSin,
		float pitchCos,
		float yawSin,
		float yawCos,
		int cameraX,
		int cameraY,
		int cameraZ,
		int plane,
		int msx,
		int msy
	) {
		log.debug(
			"tileInFrustum({}, pitchSin={}, pitchCos={}, yawSin={}, yawCos={}, cameraPos=[{}, {}, {}], plane={}, msx={}, msy={}",
			scene, pitchSin, pitchCos, yawSin, yawCos, cameraX, cameraY, cameraZ, plane, msx, msy
		);
		return true;
	}
}
