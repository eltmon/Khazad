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

package Renderer;

import Game.Actor;
import Game.Game;
import Game.Pawn;

import Map.BlockShape;
import Map.Coordinates.Direction;
import Map.GameMap;
import Map.Coordinates.BlockCoordinate;
import Map.Coordinates.MapCoordinate;

import Interface.GameCameraState;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppStateManager;
import com.jme3.asset.AssetManager;

import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;

import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.control.LodControl;

import com.jme3.animation.AnimChannel;
import com.jme3.animation.AnimControl;
import com.jme3.animation.AnimEventListener;
import com.jme3.animation.LoopMode;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Sphere;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rendering class for Actors, used Nodes from the Map rendered to attach
 * Actors to the correct locations for rendering and lighting.
 *
 * @author Impaler
 */
public class ActorRenderer extends AbstractAppState {

	SimpleApplication app = null;
	AppStateManager state = null;
	AssetManager assetmanager = null;
	LodControl ActorLodControler;
	ConcurrentHashMap<Integer, Node> ActorNodeMap;
	boolean DisplayToggle = true;
	MapCoordinate TestingCoords;
	BlockShape TestingBlockShape;
	Vector3f OffsetVector, DirectionVector;

	public ActorRenderer() {
		ActorNodeMap = new ConcurrentHashMap<Integer, Node>();
		TestingCoords = new MapCoordinate();
		TestingBlockShape = new BlockShape();
	}

	@Override
	public void initialize(AppStateManager stateManager, Application app) {
		super.initialize(stateManager, app);
		this.app = (SimpleApplication) app;
		this.state = stateManager;
		this.assetmanager = app.getAssetManager();

		OffsetVector = new Vector3f();
		DirectionVector = new Vector3f();
	}

	private Node getModel(Actor actor) {
		Spatial actorModel = assetmanager.loadModel("Models/Dwarf/Dwarf.j3o");
		actorModel.scale(0.25f, 0.25f, 0.25f);
		actorModel.rotate(1.5f, 0.0f, 0.0f);

		Node actorNode = new Node("ActorNode-" + actor.getID());
		actorNode.attachChild(actorModel);
		ActorNodeMap.put(new Integer(actor.getID()), actorNode);

		return actorNode;
	}

	public void populateActors() {
		Game game = state.getState(Game.class);
		GameMap map = game.getMap();

		long CurrentTick = game.getCurrentTimeTick();

		HashMap<Integer, Actor> actors = game.getActors();
		if (actors != null) {
			for (Actor target : actors.values()) {
				if (target != null) {
					if (target.isDirty()) {
						Node actorNode = ActorNodeMap.get(target.getID());
						if (actorNode == null) {
							actorNode = getModel(target);
						}

						actorNode.setCullHint(Spatial.CullHint.Dynamic);
						MapCoordinate coords = target.getLocation();
						MapRenderer Renderer = state.getState(MapRenderer.class);

						Node zNode;
						if (map.isBlockSunLit(coords)) {
							zNode = Renderer.getChunkNodeLight(coords.Chunk);
						} else {
							zNode = Renderer.getChunkNodeDark(coords.Chunk);
						}

						zNode.attachChild(actorNode);

						if (target instanceof Pawn) {
							MovePawn((Pawn) target, CurrentTick);
						} else {
							actorNode.setLocalTranslation(coords.Block.getX(), coords.Block.getY(), coords.Block.getZ());
						}
					}
				}
			}
		}
	}

	public void MovePawn(Pawn target, long CurrentTick) {
		Game game = state.getState(Game.class);
		GameMap map = game.getMap();

		float MoveFraction;
		Direction MovingDirection;
		boolean FirstHalf;

		synchronized(target) {
			TestingCoords.copy(target.getLocation());
			MoveFraction = target.getActionFraction(CurrentTick);
			MovingDirection = target.getMovementDirection();
			FirstHalf = target.FirstHalfMovement;
		}

		Node actorNode = ActorNodeMap.get(target.getID());
		float Height;

		map.getBlockShape(TestingCoords, TestingBlockShape);
		float CenterHeight = TestingBlockShape.getCenterHeight();

		if (MovingDirection == Direction.DIRECTION_DESTINATION) {
			Height = CenterHeight;
		} else {
			if (FirstHalf) {
				if (MoveFraction < 0.0f)
					MoveFraction = 0.0f;

				float EdgeHeight = TestingBlockShape.getDirectionEdgeHeight(MovingDirection);
				float EdgeFraction = MoveFraction * 2.0f;
				float CenterFraction = 1.0f - EdgeFraction;
				Height = (CenterHeight * CenterFraction) + (EdgeHeight * EdgeFraction);
				MovingDirection.setVector(DirectionVector);
				DirectionVector.mult(MoveFraction, OffsetVector);
			} else {
				if (MoveFraction > 1.0f)
					MoveFraction = 1.0f;

				float EdgeFraction = 2.0f - (MoveFraction * 2.0f);
				float EdgeHeight = TestingBlockShape.getDirectionEdgeHeight(MovingDirection.invert());
				float CenterFraction = 1.0f - EdgeFraction;
				Height = (CenterHeight * CenterFraction) + (EdgeHeight * EdgeFraction);
				MovingDirection.invert().setVector(DirectionVector);
				DirectionVector.mult(1.0f - MoveFraction, OffsetVector);
			}
		}

		Quaternion rotation = actorNode.getLocalRotation();
		rotation.fromAngleAxis(MovingDirection.toDegree() * FastMath.DEG_TO_RAD, Vector3f.UNIT_Z);
		actorNode.setLocalRotation(rotation);

		float x = TestingCoords.Block.getX() + OffsetVector.x;
		float y = TestingCoords.Block.getY() + OffsetVector.y;
		float z = TestingCoords.Block.getZ() + Height;

		actorNode.setLocalTranslation(x, y, z);
	}

	public void hideActors() {
		for (Node target : ActorNodeMap.values()) {
			target.setCullHint(Spatial.CullHint.Always);
		}
	}

	@Override
	public void update(float tpf) {
		Game game = state.getState(Game.class);
		if (game != null) {
			GameCameraState cam = state.getState(GameCameraState.class);
			if (cam != null) {
				if ((game.getTickRate() <= 256 || game.isPaused()) && cam.getZoom() < 200) {
					populateActors();
				} else {
					hideActors();
				}
			}
		}
	}
}
