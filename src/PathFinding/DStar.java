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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.BitSet;
import java.util.PriorityQueue;

import Map.Coordinates.Direction;
import Map.Coordinates.MapCoordinate;
import java.io.Serializable;

import java.util.concurrent.Callable;

/**
 * An improved A* implementation, it utilizes it's own Node type. The data structures
 * are highly optimized, HashSet for visited Nodes and a combination of Deque 
 * over a Heap for Fringe Nodes.
 *
 * Pathing can be done for a limited number of nodes, or with a zero argument
 * searching will continue until their is either a complete path or the
 * Fringe is exhausted which indicates that no path is possible. As all
 * attempts to path should have been proceeded by a connectivity check an
 * exhaustion is probably indicative of a flaw in connectivity checking.
 *
 * @author Impaler
 */
public class DStar extends PathAlgorithm implements Callable, Serializable {

	private static final long serialVersionUID = 1;
	// Core storage sturctures of AStar
	PriorityQueue<PathingNode> FringeHeap;
	LinkedListDeque<PathingNode> FringeDeque;
	HashSet<MapCoordinate> VisitedCoordinates;
	MapCoordinate NeiboringCoordinates;

	// Values used in iteration loop
	PathingNode CurrentNode;
	boolean FringeExausted;
	// Memory optimizing pool for Node supply
	Pool<PathingNode> NodePool;

	DStar(GridInterface TargetSearchGraph) {
		FringeHeap = new PriorityQueue<PathingNode>(100);
		VisitedCoordinates = new HashSet<MapCoordinate>(100);
		NeiboringCoordinates = new MapCoordinate();

		SearchGraph = TargetSearchGraph;
		FinalPath = null;
	}

	public void assignResources(Pool TargetPool, LinkedListDeque<PathingNode> List) {
		NodePool = TargetPool;
		FringeDeque = List;
		NodePool.setFactory(this);
	}

	@Override
	void setEndPoints(MapCoordinate StartCoords, MapCoordinate GoalCoords) {
		StartCoordinates = StartCoords;
		GoalCoordinates = GoalCoords;
		GraphReads = ExpandedNodes = 0;
		FinalPath = null;
		FringeExausted = false;

		FringeHeap.clear();
		VisitedCoordinates.clear();

		PathingNode StartNode = NodePool.provide();
		StartNode.set(StartCoordinates, null, Direction.DIRECTION_NONE, 0, MainHeuristic.estimate(StartCoords, GoalCoords), TieBreakerHeuristic.estimate(StartCoords, GoalCoords));

		FringeDeque.insertFront(StartNode);
	}

	boolean searchPath(int NodesToExpand) {
		if (FringeExausted) {
			return false; // No more searching can be done
		}

		if (FinalPath == null) {
			boolean GoalFound;
			if (NodesToExpand > 0) { // Search for a limited time
				for (int RemainingNodes = NodesToExpand; RemainingNodes > 0; RemainingNodes--) {
					if (FringeDeque.isEmpty() && FringeHeap.isEmpty()) {
						FringeExausted = true; // Path could not be found
						return false;
					}

					if (expandNode()) {
						return true; // Path found, skip to finish
					}
				}
				return false; // Path not yet found
			} else { // Search untill Path is found or Fringe is exhausted
				while (!FringeDeque.isEmpty() || !FringeHeap.isEmpty()) {
					if (expandNode()) {
						return true;
					}
				}
				FringeExausted = true;
				return false;
			}
		}
		return true;  // Final Path already found don't do any more searching
	}

	MapPath findPath(int NodesToExpand) {
		if (FringeExausted) {
			return null; // Fringe Exhastion, don't return a useless path
		}

		boolean FinalPathFound = searchPath(NodesToExpand);

		if (FinalPath == null) {
			MapPath CurrentPath = generateVectorPath();

			if (FinalPathFound)
				FinalPath = CurrentPath;

			NodePool.release();	 // Nodes can be released now that a final path has been found
			return CurrentPath;
		}
		NodePool.release();
		FringeDeque.clear();
		return FinalPath;
	}

	boolean expandNode() {
		CurrentNode = FringeDeque.removeFirst();
		if (CurrentNode == null)
			CurrentNode = FringeHeap.poll();

		MapCoordinate TestCoordinates = CurrentNode.LocationCoordinates;
		Direction ParentDirection = CurrentNode.ParentDirection.invert();

		if (VisitedCoordinates.contains(TestCoordinates))
			return false;

		if (TestCoordinates.equals(GoalCoordinates))
			return true;

		// mark as VisitedCoordinates if not already Visited
		VisitedCoordinates.add(TestCoordinates);
		int TestDirections = SearchGraph.getDirectionEdgeSet(TestCoordinates);

		// Check all Neibors
		for (int i = 1; i < 32; i++) { // Skip DIRECTION_NONE
			if ((TestDirections & (1 << i)) != 0) {
				Direction DirectionType = Direction.ANGULAR_DIRECTIONS[i];
				if (DirectionType == ParentDirection)
					continue;

				NeiboringCoordinates.copy(TestCoordinates);
				NeiboringCoordinates.translate(DirectionType);

				// If Coordinate is not already on the VisitedCoordinates list
				if (VisitedCoordinates.contains(NeiboringCoordinates) == false) {
					float EdgeCost = SearchGraph.getEdgeCost(TestCoordinates, DirectionType);
					GraphReads++;

					PathingNode NewNode = NodePool.provide();
					float mainHeuristicValue = MainHeuristic.estimate(NeiboringCoordinates, GoalCoordinates);
					float secondaryHeuristicValue = TieBreakerHeuristic.estimate(NeiboringCoordinates, GoalCoordinates);
					NewNode.set(NeiboringCoordinates, CurrentNode, DirectionType, CurrentNode.PathLengthFromStart + EdgeCost, mainHeuristicValue * 1.1f, secondaryHeuristicValue);
					
					FringeDeque.insertFront(NewNode);
				} // modify existing node with new distance?
			}
		}

		while(FringeDeque.size() > 1) {
			FringeHeap.add(FringeDeque.removeLast());
		}

		PathingNode DequeNode = FringeDeque.peekFirst();
		PathingNode HeapNode = FringeHeap.peek();
		if (DequeNode != null && HeapNode != null) {
			if (DequeNode.compareTo(HeapNode) > 0) {
				FringeHeap.add(FringeDeque.removeLast());
			}
		}

		return false; // Goal was not found
	}

	CoordinatePath generateFullPath() {
		ExpandedNodes = VisitedCoordinates.size();

		float PathLength = CurrentNode.PathLengthFromStart;
		ArrayList<MapCoordinate> Course = new ArrayList();

		while (CurrentNode != null) {
			Course.add(CurrentNode.LocationCoordinates);
			CurrentNode = CurrentNode.Parent;
		}
		Course.add(StartCoordinates);

		Collections.reverse(Course);
		return new CoordinatePath(PathLength, Course);
	}

	VectorPath generateVectorPath() {
		ExpandedNodes = VisitedCoordinates.size();

		float PathLength = CurrentNode.PathLengthFromStart;
		ArrayList<Direction> Course = new ArrayList();

		while (CurrentNode != null) {
			Course.add(CurrentNode.ParentDirection);
			CurrentNode = CurrentNode.Parent;
		}
		Course.remove(Course.size() - 1);

		Collections.reverse(Course);
		return new VectorPath(PathLength, Course, StartCoordinates, GoalCoordinates);
	}

	PathingNode provide() {
		return new PathingNode();
	}

	public MapPath call() {
		return findPath(0);
	}
}