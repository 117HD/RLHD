package rs117.hd.data;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum ObjectType {
	WallStraight(0), // uses field1007
	WallDiagonalCorner(1), // uses field1011
	WallCorner(2), // uses field1007
	WallSquareCorner(3), // uses field1011
	WallDecorStraightNoOffset(4), // uses field1007
	WallDecorStraightOffset(5), // uses field1007
	WallDecorDiagonalOffset(6), // 256, var5
	WallDecorDiagonalNoOffset(7), // 256, var5 + 2 & 3
	WallDecorDiagonalBoth(8), // 256, var5
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
