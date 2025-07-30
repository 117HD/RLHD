package rs117.hd.data;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum ObjectType {
	// Sourced from https://github.com/abextm/cache2/blob/2562ab769e4042667d6a10c3ab795b9622be6049/cache2-ts/src/types.ts#L119-L145
	WallStraight(0),
	WallDiagonalCorner(1),
	WallCorner(2),
	WallSquareCorner(3),
	WallDecorStraightNoOffset(4),
	WallDecorStraightOffset(5),
	WallDecorDiagonalOffset(6),
	WallDecorDiagonalNoOffset(7),
	WallDecorDiagonalBoth(8),
	WallDiagonal(9),
	CentrepieceStraight(10),
	CentrepieceDiagonal(11),
	RoofStraight(12),
	RoofDiagonalWithRoofEdge(13),
	RoofDiagonal(14),
	RoofCornerConcave(15),
	RoofCornerConvex(16),
	RoofFlat(17),
	RoofEdgeStraight(18),
	RoofEdgeDiagonalCorner(19),
	RoofEdgeCorner(20),
	RoofEdgeSquarecorner(21),
	GroundDecor(22),
	Unknown(-1);

	public final int id;

	public static ObjectType fromConfig(int config) {
		int type = config & 0x3F;
		if (type >= values().length - 1)
			return Unknown;
		return values()[type];
	}
}
