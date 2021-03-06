/* Soot - a Java Optimization Framework
 * Copyright (C) 2012 Michael Markert, Frank Hartmann
 *
 * (c) 2012 University of Luxembourg - Interdisciplinary Centre for
 * Security Reliability and Trust (SnT) - All rights reserved
 * Alexandre Bartel
 *
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the
 * Free Software Foundation, Inc., 59 Temple Place - Suite 330,
 * Boston, MA 02111-1307, USA.
 */

package soot.dexpler;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import soot.Body;
import soot.Local;
import soot.Trap;
import soot.Unit;
import soot.UnitBox;
import soot.jimple.AssignStmt;
import soot.jimple.Constant;
import soot.jimple.FieldRef;
import soot.jimple.GotoStmt;
import soot.jimple.IfStmt;
import soot.jimple.ReturnStmt;
import soot.jimple.ReturnVoidStmt;
import soot.jimple.Stmt;
import soot.jimple.toolkits.scalar.LocalCreation;
import soot.toolkits.graph.ExceptionalUnitGraph;
import soot.toolkits.scalar.LocalDefs;
import soot.toolkits.scalar.LocalUses;
import soot.toolkits.scalar.SimpleLiveLocals;
import soot.toolkits.scalar.SimpleLocalUses;
import soot.toolkits.scalar.SmartLocalDefs;

/**
 * BodyTransformer to inline jumps to return statements. Take the following code:
 * 		a = b
 * 		goto label1
 * 
 * 		label1:
 * 			return a
 * We inline this to produce
 * 		a = b
 * 		return b
 *
 * @author Steven Arzt
 */
public class DexReturnInliner extends DexTransformer {

    public static DexReturnInliner v() {
        return new DexReturnInliner();
    }

	private boolean isInstanceofReturn(Unit u) {
		if (u instanceof ReturnStmt || u instanceof ReturnVoidStmt)
			return true;
		return false;
	}

	private boolean isInstanceofFlowChange(Unit u) {
		if (u instanceof GotoStmt || isInstanceofReturn(u))
			return true;
		return false;
	}

	@Override
	protected void internalTransform(final Body body, String phaseName, @SuppressWarnings("rawtypes") Map options) {
    	Set<Unit> duplicateIfTargets = getFallThroughReturns(body);
    	
		Iterator<Unit> it = body.getUnits().snapshotIterator();
		boolean mayBeMore = false;
		do {
			mayBeMore = false;
			while (it.hasNext()) {
				Unit u = it.next();
				if (u instanceof GotoStmt) {
					GotoStmt gtStmt = (GotoStmt) u;
					if (isInstanceofReturn(gtStmt.getTarget())) {
						Stmt stmt = (Stmt) gtStmt.getTarget().clone();
						
						for (Trap t : body.getTraps())
							for (UnitBox ubox : t.getUnitBoxes())
								if (ubox.getUnit() == u)
									ubox.setUnit(stmt);
						
						while (!u.getBoxesPointingToThis().isEmpty())
							u.getBoxesPointingToThis().get(0).setUnit(stmt);
						body.getUnits().swapWith(u, stmt);
						
						mayBeMore = true;
					}
				} else if (u instanceof IfStmt) {
					IfStmt ifstmt = (IfStmt) u;
					Unit t = ifstmt.getTarget();
					
					if (isInstanceofReturn(t)) {
						// We only copy this return if it is used more than
						// once, otherwise we will end up with unused copies
						if (duplicateIfTargets == null)
							duplicateIfTargets = new HashSet<Unit>();
						if (!duplicateIfTargets.add(t)) {
							Unit newTarget = (Unit) t.clone();
							body.getUnits().addLast(newTarget);
							ifstmt.setTarget(newTarget);
						}
					}
				}
			}
		} while (mayBeMore);

        ExceptionalUnitGraph graph = new ExceptionalUnitGraph(body, DalvikThrowAnalysis.v(), true);
        LocalDefs localDefs = new SmartLocalDefs(graph, new SimpleLiveLocals(graph));
        LocalUses localUses = null;
        LocalCreation localCreation = null;
        
		// If a return statement's operand has only one definition and this is
		// a copy statement, we take the original operand
		for (Unit u : body.getUnits())
			if (u instanceof ReturnStmt) {
				ReturnStmt retStmt = (ReturnStmt) u;
				if (retStmt.getOp() instanceof Local) {
					List<Unit> defs = localDefs.getDefsOfAt((Local) retStmt.getOp(), retStmt);
					if (defs.size() == 1 && defs.get(0) instanceof AssignStmt) {
						AssignStmt assign = (AssignStmt) defs.get(0);
						if (assign.getRightOp() instanceof Local
								|| assign.getRightOp() instanceof Constant)
							retStmt.setOp(assign.getRightOp());
						
						// If this is a field access which has no other uses,
						// we rename the local to help splitting
						if (assign.getRightOp() instanceof FieldRef) {
							if (localUses == null)
								localUses = new SimpleLocalUses(body, localDefs);
							if (localUses.getUsesOf(assign).size() == 1) {
								if (localCreation == null)
									localCreation = new LocalCreation(body.getLocals(), "ret");
								Local newLocal = localCreation.newLocal(assign.getLeftOp().getType());
								assign.setLeftOp(newLocal);
								retStmt.setOp(newLocal);
							}
						}
					}
				}
			}
    }
    
    /**
     * Gets the set of return statements that can be reached via fall-throughs,
     * i.e. normal sequential code execution. Dually, these are the statements
     * that can be reached without jumping there.
     * @param body The method body
     * @return The set of fall-through return statements
     */
	private Set<Unit> getFallThroughReturns(Body body) {
		Set<Unit> fallThroughReturns = null;
		Unit lastUnit = null;
		for (Unit u : body.getUnits()) {
			if (lastUnit != null
					&& isInstanceofReturn(u)
					&& !isInstanceofFlowChange(lastUnit)) {
				if (fallThroughReturns == null)
					fallThroughReturns = new HashSet<Unit>();
				fallThroughReturns.add(u);
			}
			lastUnit = u;
		}
		return fallThroughReturns;
	}

}

