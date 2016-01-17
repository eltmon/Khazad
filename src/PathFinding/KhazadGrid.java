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

package PathFinding;

import Map.Chunk;
import java.util.BitSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import Map.GameMap;
import Map.Coordinates.*;
import Map.BlockShape;
import Map.Sector;
import java.io.Serializable;

import gnu.trove.map.TShortIntMap;
import gnu.trove.map.hash.TShortIntHashMap;
import gnu.trove.map.hash.TShortShortHashMap;

/**
 * The primary implementation of Grid for Khazad pathfinding, it uses a GridChunk
 * class the spacially corresponds to the MapChunk class for interchangability of
 * Coordinates, but the GridChunk holds a single large BitSet of ~7000 bits that
 * corresponds to every Coordinates possible edge to neibhors. Also it records
 * a connectivity zone for each coordinate.
 *
 * The Grid class then stores a HashMap of these GridChunks, again mirroring the
 * structure used in GameMap, as well as a double HashMap and List which are
 * built in a single read pass on the Game Map, this allows for a constant time
 * connection query but is not dynamic.
 *
 * @author Impaler
 */
public class KhazadGrid implements GridInterface, Serializable {

	private static final long serialVersionUID = 1;

	protected class GridChunk {

		private TShortIntMap DirectionHashMap;
		private TShortShortHashMap ConnectivityHashMap;
		private ChunkCoordinate thisChunkCoodinates;

		GridChunk(ChunkCoordinate Coordinates) {
			DirectionHashMap = new TShortIntHashMap();
			ConnectivityHashMap = new TShortShortHashMap();

			thisChunkCoodinates = Coordinates;
		}

		public int getConnectivityZone(BlockCoordinate Block) {
			return ConnectivityHashMap.get(Block.getBlockIndex());
		}

		public void setConnectivityZone(BlockCoordinate Block, int Zone) {
			if (Zone != 0)
				ConnectivityHashMap.put(Block.getBlockIndex(), (short) Zone);
		}

		public int getBlockDirections(BlockCoordinate Block) {
			return DirectionHashMap.get(Block.getBlockIndex());
		}

		public boolean isEdge(BlockCoordinate Block, Direction testDirection) {
			int DirectionSet = DirectionHashMap.get(Block.getBlockIndex());
			return (DirectionSet & (1 << testDirection.ordinal())) != 0;
		}

		public void setBlockDirection(BlockCoordinate Block, Direction TargetDirection, boolean newValue) {
			int adjusted = DirectionHashMap.get(Block.getBlockIndex()) | (1 << TargetDirection.ordinal());
			DirectionHashMap.put(Block.getBlockIndex(), adjusted);
		}

		void setBlockDirections(BlockCoordinate Block, BitSet ArgumentSet) {
			int ArgumentInt = 0;
			for (int i = ArgumentSet.nextSetBit(0); i != -1; i = ArgumentSet.nextSetBit(i + 1)) {
				ArgumentInt |= (1 << i);
			}
			if (ArgumentInt != 0)
				DirectionHashMap.put(Block.getBlockIndex(), ArgumentInt);
		}

		void setBlockDirections(BlockCoordinate Block, int ArgumentSet) {
			if (ArgumentSet != 0)
				DirectionHashMap.put(Block.getBlockIndex(), ArgumentSet);
		}

		ChunkCoordinate getChunkCoordinates() {
			return thisChunkCoodinates;
		}
	}

	ConcurrentHashMap<ChunkCoordinate, GridChunk> GridChunks;
	ConcurrentLinkedDeque<MapCoordinate> DirtyLocations;
	// Connections between groups of Coordinates
	BlockShape TargetBlockShape, AboveBlockShape, AdjacentBlockShape;
	BitSet BlockDirections;
	ArrayList<Integer> ConnectivityCache;
	ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, Integer>> ConnectivityMap;
	MapCoordinate TestCoordinates;
	// The unique mix of movements this Grid is modeling
	MovementModality GridModality;
	GameMap SourceMap;
	final float root2 = (float) Math.sqrt(2);

	public KhazadGrid(GameMap TargetMap, MovementModality Modality) {
		GridChunks = new ConcurrentHashMap<ChunkCoordinate, GridChunk>();
		ConnectivityMap = new ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, Integer>>();
		ConnectivityCache = new ArrayList<Integer>();
		DirtyLocations = new ConcurrentLinkedDeque<MapCoordinate>();
		TestCoordinates = new MapCoordinate();
		GridModality = Modality;
		SourceMap = TargetMap;

		TargetBlockShape = new BlockShape(); 
		AboveBlockShape = new BlockShape(); 
		AdjacentBlockShape = new BlockShape();
		BlockDirections = new BitSet(Direction.ANGULAR_DIRECTIONS.length);

		for (Sector targetSector : TargetMap.getSectorCollection()) {
			TestCoordinates.Sector.copy(targetSector.getSectorCoordinates());
			for (Chunk TargetChunk : targetSector.getChunkCollection()) {
				TestCoordinates.Chunk.copy(TargetChunk.getChunkCoordinates());
				ChunkCoordinate ChunkCoords = TargetChunk.getChunkCoordinates();
				GridChunk NewGridChunk = addChunk(ChunkCoords);

				for (BlockCoordinate Index = new BlockCoordinate(); !Index.isEnd(); Index.next()) {
					TestCoordinates.Block.copy(Index);
					BlockDirections.clear();
					BitSet Flags = buildConnectivitySet(TestCoordinates, BlockDirections);
					NewGridChunk.setBlockDirections(Index, Flags);
				} 
			}
		}

		buildConnectivityZones();
	}

	final void buildConnectivityZones() {
		int ZoneCounter = 0;

		// Loop to do connectivity
		for (GridChunk TargetChunk : GridChunks.values()) {
			if (TargetChunk != null) {
				ChunkCoordinate ChunkCoords = TargetChunk.getChunkCoordinates();
				TestCoordinates.setChunkCoordinate(ChunkCoords);

				for (BlockCoordinate Index = new BlockCoordinate(); !Index.isEnd(); Index.next()) {
					TestCoordinates.setBlockCoordinate(Index);
					int Flags = getDirectionEdgeSet(TestCoordinates);

					if (Flags != 0) {
						if (TargetChunk.getConnectivityZone(Index) == 0) { // Start a new zone if not connected to another zone
							ZoneCounter++; // First zone will be 1, 0 will indicate un-ititialized
							TargetChunk.setConnectivityZone(Index, ZoneCounter);
						}
						// Push this current zone onto the adjacent area
						int CurrentZoneIndex = TargetChunk.getConnectivityZone(Index);

						for (Direction dir : Direction.ANGULAR_DIRECTIONS) {
							if ((Flags & (1 << dir.ordinal())) != 0) {
								// Find the Zone that the adjcent Tile has
								MapCoordinate AdjacentTileCoords = new MapCoordinate(ChunkCoords, Index);
								AdjacentTileCoords.translate(dir);
								int AdjacentZoneIndex = getConnectivityZone(AdjacentTileCoords);

								if (AdjacentZoneIndex == 0) {// The other location is unititialized
									setConnectivityZone(AdjacentTileCoords, CurrentZoneIndex); // Push this current zone onto the adjacent area
								} else {
									if (AdjacentZoneIndex != CurrentZoneIndex) { // A different zone update the Connectivity Map
										changeConnectivityMap(CurrentZoneIndex, AdjacentZoneIndex, 1);  // Add one connection between zones
									}
								}
							}
						}
					} else {
						//TargetChunk.setConnectivityZone(Index, 0);  // nessary?
					}
				}
			}
		}

		RebuildConnectivityCache(ZoneCounter);
	}

	private BitSet buildConnectivitySet(MapCoordinate TargetCoords, BitSet Flags) {
		//BitSet Flags = new BitSet(BlockCoordinate.BLOCKS_PER_CHUNK);
		SourceMap.getBlockShape(TargetCoords, TargetBlockShape);

		if (!TargetBlockShape.isSky() && !TargetBlockShape.hasCeiling()) {
			MapCoordinate OverheadTileCoords = TargetCoords.clone();
			OverheadTileCoords.translate(Direction.DIRECTION_UP);
			SourceMap.getBlockShape(OverheadTileCoords, AboveBlockShape);
			boolean OverheadPassable = !AboveBlockShape.isSolid();

			for (Direction dir : Direction.ANGULAR_DIRECTIONS) {
				MapCoordinate AdjacentTileCoords = TargetCoords.clone();
				AdjacentTileCoords.translate(dir);
				SourceMap.getBlockShape(AdjacentTileCoords, AdjacentBlockShape);

				if (!AdjacentBlockShape.isSky() && !AdjacentBlockShape.hasCeiling()) {
					if (dir.getValueonAxis(Axis.AXIS_Z) == 1) {
						if (OverheadPassable) {
							Flags.set(dir.ordinal());
						}
					} else {
						//If no vertical direction, we only care that this tile is passable
						Flags.set(dir.ordinal());
					}
				}
			}
		}
		return Flags;
	}

	public int getDirectionEdgeSet(MapCoordinate TargetCoords) {
		GridChunk TargetChunk = getChunk(TargetCoords.Chunk);
		return TargetChunk != null ? TargetChunk.getBlockDirections(TargetCoords.Block) : 0;

	}

	public MovementModality getModality() {
		return GridModality;
	}

	public boolean isEdge(MapCoordinate TargetCoords, Direction DirectionType) {
		GridChunk TargetChunk = getChunk(TargetCoords.Chunk);
		return TargetChunk != null ? TargetChunk.isEdge(TargetCoords.Block, DirectionType) : false;
	}

	public int getConnectivityZone(MapCoordinate TargetCoords) {
		GridChunk TargetChunk = getChunk(TargetCoords.Chunk);
		if (TargetChunk != null) {
			return TargetChunk.getConnectivityZone(TargetCoords.Block);
		}
		return 0;  // No connectivity zone because Chunk is invalid
	}

	void setConnectivityZone(MapCoordinate TargetCoords, int NewZone) {
		GridChunk TargetChunk = getChunk(TargetCoords.Chunk);
		if (TargetChunk != null) {
			TargetChunk.setConnectivityZone(TargetCoords.Block, NewZone);
		}
	}

	void setDirectionFlags(MapCoordinate TargetCoords, BitSet Flags) {
		GridChunk TargetChunk = getChunk(TargetCoords.Chunk);
		if (TargetChunk == null)
			TargetChunk = addChunk(TargetCoords.Chunk);
			
		TargetChunk.setBlockDirections(TargetCoords.Block, Flags);
	}

	ConcurrentHashMap<Integer, Integer> getConnectivtySubMap(int Zone) {
		ConcurrentHashMap<Integer, Integer> SubMap = ConnectivityMap.get(Zone);

		if (SubMap == null) {
			SubMap = new ConcurrentHashMap<Integer, Integer>();
			ConnectivityMap.put(Zone, SubMap);
			return SubMap;
		}
		return SubMap;
	}

	void changeConnectivityMap(int FirstZone, int SecondZone, int connectionChange) {
		if (FirstZone == SecondZone) {
			return;  // Cannot connect to self
		}

		ConcurrentHashMap<Integer, Integer> TargetMap = getConnectivtySubMap(FirstZone);
		Integer it = TargetMap.get(SecondZone);

		if (it == null) {
			if (connectionChange > 0) { // Do not allow negative connection counts
				TargetMap.put(SecondZone, connectionChange);
			}
		} else {
			if ((it.intValue() + connectionChange) <= 0) {
				TargetMap.remove(it); // Remove the connection entirely
			}
		}
	}

	public boolean contains(MapCoordinate TestCoords) {
		return getChunk(TestCoords.Chunk) != null;
	}

	GridChunk getChunk(ChunkCoordinate TestCoords) {
		return GridChunks.get(TestCoords);
	}

	GridChunk addChunk(ChunkCoordinate TargetCoords) {
		GridChunk TargetChunk = getChunk(TargetCoords);
		if (TargetChunk == null) {
			GridChunk NewGridChunk = new GridChunk(TargetCoords);
			GridChunks.put(TargetCoords, NewGridChunk);
			return NewGridChunk;
		}
		return TargetChunk;
	}

	public float getEdgeCost(MapCoordinate TestCoords, Direction DirectionType) {
		if (isEdge(TestCoords, DirectionType)) {
			if (DirectionType.getValueonAxis(Axis.AXIS_Z) != 0) // True for Up and Down
				return 2;

			boolean X = DirectionType.getValueonAxis(Axis.AXIS_X) != 0;
			boolean Y = DirectionType.getValueonAxis(Axis.AXIS_Y) != 0;

			if (X ^ Y) // N, S, E, W
				return 1;
			if (X & Y) {
				return root2;
			}

			if (DirectionType == Direction.DIRECTION_NONE)
				return 0;
			return 2;  // All other z axis diagonals
		}
		return -1;  // No Edge exists
	}

	public boolean isPathPossible(MovementModality MovementType, MapCoordinate StartCoords, MapCoordinate GoalCoords) {
		return getZoneEquivilency(StartCoords) == getZoneEquivilency(GoalCoords);
	}

	public void dirtyMapCoordinate(MapCoordinate[] DirtyCoords) {
		Collections.addAll(DirtyLocations, DirtyCoords);

		do {
			MapCoordinate TargetCoords = DirtyLocations.poll();
			BlockDirections.clear();
			BitSet NewConnectivitySet = buildConnectivitySet(TargetCoords, BlockDirections);

			ChunkCoordinate TargetChunk = TargetCoords.Chunk;
			GridChunk TargetGridChunk = getChunk(TargetChunk);
			int CurrentConnectivity = getDirectionEdgeSet(TargetCoords);
			int SourceZone = getConnectivityZone(TargetCoords);

			for (Direction dir : Direction.ANGULAR_DIRECTIONS) {
				boolean NewConnectionValue = NewConnectivitySet.get(dir.ordinal());
				MapCoordinate AdjacentTileCoords = TargetCoords.clone();
				AdjacentTileCoords.translate(dir);
				ChunkCoordinate AdjacentChunk = AdjacentTileCoords.Chunk;
				GridChunk AdjacentGridChunk = getChunk(AdjacentChunk);
				int AdjacentZone = getConnectivityZone(AdjacentTileCoords);

				if (NewConnectionValue != ((CurrentConnectivity & (1 << dir.ordinal())) != 0)) {
					if (NewConnectionValue) { // Connection created
						if (SourceZone == 0 && AdjacentZone != 0) {  // Match the zone any adjacent connected zone
							TargetGridChunk.setConnectivityZone(TargetCoords.Block, AdjacentZone);
						}

						if (AdjacentZone == 0 && SourceZone != 0) {  // Alternativly push the existing zone into unitialized space
							TargetGridChunk.setConnectivityZone(AdjacentTileCoords.Block, SourceZone);
						}
					}

					if (SourceZone != AdjacentZone) {
						if (NewConnectionValue) {
							changeConnectivityMap(SourceZone, AdjacentZone, 1);
						} else {
							changeConnectivityMap(SourceZone, AdjacentZone, -1);
						}
					}
					AdjacentGridChunk.setBlockDirection(AdjacentTileCoords.Block, dir.invert(), NewConnectionValue);
					Sector AdjacentSector = SourceMap.getSector(AdjacentTileCoords.Sector);
					AdjacentSector.getChunk(AdjacentChunk).setDirtyPathingRendering(true);
				}
			}
			TargetGridChunk.setBlockDirections(TargetCoords.Block, NewConnectivitySet);
			Sector AdjacentSector = SourceMap.getSector(TargetCoords.Sector);
			AdjacentSector.getChunk(TargetCoords.Chunk).setDirtyPathingRendering(true);

		} while (!DirtyLocations.isEmpty());
	}

	int getZoneEquivilency(MapCoordinate TargetCoords) {
		return ConnectivityCache.get(getConnectivityZone(TargetCoords));
	}

	int getZoneEquivilency(int Zone) {
		return ConnectivityCache.get(Zone);
	}

	void updateConnectivityCache(int Zone, int ZoneEquivilency) {
		ConnectivityCache.set(Zone, ZoneEquivilency);

		ConcurrentHashMap<Integer, Integer> TargetMap = getConnectivtySubMap(Zone);
		for (Integer i : TargetMap.keySet()) {
			if (ConnectivityCache.get(i.intValue()) == 0) {
				updateConnectivityCache(i, ZoneEquivilency);
			}
		}
	}

	void RebuildConnectivityCache(int ZoneCount) {
		ConnectivityCache = new ArrayList<Integer>(Collections.nCopies(ZoneCount + 1, 0));

		for (int i : ConnectivityCache) {  // Skip zero, no such zone
			if (ConnectivityCache.get(i) == 0) {
				updateConnectivityCache(i, i);
			}
		}
	}

	public ArrayList<MapCoordinate> getPassableCoordinates() {
		ArrayList<MapCoordinate> TestCoords = new ArrayList<MapCoordinate>();
		for (KhazadGrid.GridChunk TargetChunk : GridChunks.values()) {
			ChunkCoordinate ChunkCoords = TargetChunk.getChunkCoordinates();

			BitSet DirectionSet;
			for (BlockCoordinate Index = new BlockCoordinate(); !Index.isEnd(); Index.next()) {
				if (TargetChunk.getBlockDirections(Index) != 0) {  // Any Valid Edge Exists
					MapCoordinate PassableCoordinates = new MapCoordinate(ChunkCoords, Index);
					TestCoords.add(PassableCoordinates);
				}
			}
		}
		return TestCoords;
	}
}
