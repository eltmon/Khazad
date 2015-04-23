/* Copyright 2010 Kenneth 'Impaler' Ferland

 This file is part of Khazad.

 Khazad is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Khazad is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Khazad.  If not, see <http://www.gnu.org/licenses/> */

package Map;

import java.io.Serializable;

/**
 * Used to determine the Mesh used to build a Rendering of a Cell
 *
 * @author Impaler
 */
public class FaceShape implements Serializable {

	private static final long serialVersionUID = 1;
	private short SourceCubeData;
	private short AdjacentCubeData;
	private byte FaceDirection;

	public FaceShape() {
		SourceCubeData = CubeShape.EMPTY_CUBE_DATA;
		AdjacentCubeData = CubeShape.EMPTY_CUBE_DATA;
		FaceDirection = (byte) Direction.DIRECTION_DESTINATION.ordinal();
	}

	public FaceShape(CubeShape SourceShapeType, CubeShape AdjacentShapeType, Direction DirectionType) {
		SourceCubeData = SourceShapeType.Data;
		if (AdjacentShapeType != null)
			AdjacentCubeData = AdjacentShapeType.Data;
		FaceDirection = (byte) DirectionType.ordinal();		
	}

	boolean equals(FaceShape ArgumentShape) {
		boolean AdjacentEqual, SourceEqual, FaceEqual;
		if (ArgumentShape != null) {
			AdjacentEqual = ArgumentShape.AdjacentCubeData == AdjacentCubeData;
			SourceEqual = SourceCubeData == ArgumentShape.SourceCubeData;
			FaceEqual = FaceDirection == ArgumentShape.FaceDirection;
		} else {
			AdjacentEqual = AdjacentCubeData == CubeShape.EMPTY_CUBE_DATA;
			SourceEqual =  SourceCubeData == CubeShape.EMPTY_CUBE_DATA;
			FaceEqual = FaceDirection == Direction.DIRECTION_NONE.ordinal();
		}
		return SourceEqual && AdjacentEqual && FaceEqual;
	}

	boolean notequal(FaceShape ArgumentShape) {
		return SourceCubeData != ArgumentShape.SourceCubeData || AdjacentCubeData != ArgumentShape.AdjacentCubeData || FaceDirection != ArgumentShape.FaceDirection;
	}

	public Direction getFaceDirection() {
		return Direction.ANGULAR_DIRECTIONS[FaceDirection];
	}
	
	public CubeShape getSourceCubeShape() {
		return new CubeShape(SourceCubeData);
	}

	public CubeShape getAdjacentCubeShape() {
		return new CubeShape(AdjacentCubeData);
	}
}
