/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package tac
package fpcf
package analyses
package cg
package pointsto

import org.opalj.collection.ForeachRefIterator
import org.opalj.collection.immutable.IntTrieSet
import org.opalj.fpcf.Entity
import org.opalj.fpcf.EPS
import org.opalj.fpcf.EUBPS
import org.opalj.fpcf.ProperPropertyComputationResult
import org.opalj.fpcf.PropertyBounds
import org.opalj.fpcf.PropertyKey
import org.opalj.fpcf.SomeEPS
import org.opalj.br.analyses.SomeProject
import org.opalj.br.DefinedMethod
import org.opalj.br.Method
import org.opalj.br.ObjectType
import org.opalj.br.ReferenceType
import org.opalj.br.fpcf.properties.pointsto.AllocationSitePointsToSet
import org.opalj.br.fpcf.properties.pointsto.NoAllocationSites
import org.opalj.br.fpcf.properties.pointsto.NoTypes
import org.opalj.br.fpcf.properties.pointsto.PointsToSetLike
import org.opalj.br.fpcf.properties.pointsto.TypeBasedPointsToSet
import org.opalj.tac.fpcf.analyses.pointsto.AbstractPointsToBasedAnalysis
import org.opalj.tac.fpcf.properties.TACAI

/**
 * Uses the [[PointsToSetLike]] of
 * [[org.opalj.tac.common.DefinitionSite]] and[[org.opalj.br.analyses.VirtualFormalParameter]]s
 * in order to determine the targets of virtual method calls.
 *
 * @author Florian Kuebler
 */
trait AbstractPointsToBasedCallGraphAnalysis[PointsToSet <: PointsToSetLike[_, _, PointsToSet]]
    extends AbstractCallGraphAnalysis
    with AbstractPointsToBasedAnalysis[CallSiteT, PointsToSet] {

    override type State = PointsToBasedCGState[PointsToSet]

    /**
     * Computes the calls of the given `method` including the known effect of the `call` and
     * the call sites associated ith this call (in order to process updates of instantiated types).
     * There can be multiple "call sites", in case the three-address code has computed multiple
     * type bounds for the receiver.
     */
    override def handleImpreciseCall(
        caller:                        DefinedMethod,
        call:                          Call[V] with VirtualCall[V],
        pc:                            Int,
        specializedDeclaringClassType: ReferenceType,
        potentialTargets:              ForeachRefIterator[ObjectType],
        calleesAndCallers:             DirectCalls
    )(implicit state: State): Unit = {
        val callerType = caller.definedMethod.classFile.thisType
        val callSite = (pc, call.name, call.descriptor, call.declaringClass)

        // get the upper bound of the pointsToSet and creates a dependency if needed
        val pointsToSet = currentPointsTo(callSite, call.receiver.asVar.definedBy)

        var types = IntTrieSet.empty

        for (newType ← potentialTargets) {
            if (pointsToSet.contains(newType)) {
                val tgtR = project.instanceCall(
                    callerType,
                    newType,
                    call.name,
                    call.descriptor
                )
                handleCall(
                    caller,
                    call.name,
                    call.descriptor,
                    call.declaringClass,
                    pc,
                    tgtR,
                    calleesAndCallers
                )
            } else {
                types += newType.id
            }
        }
        state.setPotentialTypesOfCallSite(callSite, types)

    }

    override def c(
        state: State
    )(eps: SomeEPS): ProperPropertyComputationResult = eps match {
        case EUBPS(e, ub: PointsToSetLike[_, _, _], isFinal) ⇒
            val relevantCallSites = state.dependersOf(e)

            // ensures, that we only add new calls
            val calls = new DirectCalls()

            val oldEOptP = state.getPointsToProperty(eps.e)
            val seenElements = if (oldEOptP.hasUBP) oldEOptP.ub.numTypes else 0

            // perform the update for the new types
            for (callSite ← relevantCallSites) {
                val typesLeft = state.typesForCallSite(callSite)
                for (newType ← ub.dropOldestTypes(seenElements)) {
                    if (typesLeft.contains(newType.id)) {
                        state.removeTypeForCallSite(callSite, newType)
                        val (pc, name, descriptor, declaredType) = callSite
                        val tgtR = project.instanceCall(
                            state.method.declaringClassType.asObjectType,
                            newType,
                            name,
                            descriptor
                        )
                        handleCall(
                            state.method, name, descriptor, declaredType, pc, tgtR, calls
                        )
                    }
                }
            }

            // The method removeTypesForCallSite might have made the dependency obsolete, so only
            // update or remove it, if we still need updates for that type.
            if (state.hasPointsToDependee(eps.e)) {
                if (isFinal) {
                    state.removePointsToDependee(e)
                } else {
                    state.updatePointsToDependency(eps.asInstanceOf[EPS[Entity, PointsToSet]])
                }
            }

            returnResult(calls)(state)

        case _ ⇒ super.c(state)(eps)
    }

    override def createInitialState(
        definedMethod: DefinedMethod, tacEP: EPS[Method, TACAI]
    ): PointsToBasedCGState[PointsToSet] = {
        new PointsToBasedCGState[PointsToSet](definedMethod, tacEP)
    }
}

class TypeBasedPointsToBasedCallGraphAnalysis private[pointsto] (
        final val project: SomeProject
) extends AbstractPointsToBasedCallGraphAnalysis[TypeBasedPointsToSet] {
    override protected[this] val pointsToPropertyKey: PropertyKey[TypeBasedPointsToSet] = {
        TypeBasedPointsToSet.key
    }

    override protected def emptyPointsToSet: TypeBasedPointsToSet = NoTypes
}

object TypeBasedPointsToBasedCallGraphAnalysisScheduler extends CallGraphAnalysisScheduler {

    override def uses: Set[PropertyBounds] = super.uses + PropertyBounds.ub(TypeBasedPointsToSet)

    override def initializeAnalysis(p: SomeProject): TypeBasedPointsToBasedCallGraphAnalysis = {
        new TypeBasedPointsToBasedCallGraphAnalysis(p)
    }
}

class AllocationSiteBasedPointsToBasedCallGraphAnalysis private[pointsto] (
        final val project: SomeProject
) extends AbstractPointsToBasedCallGraphAnalysis[AllocationSitePointsToSet] {
    override protected[this] val pointsToPropertyKey: PropertyKey[AllocationSitePointsToSet] = {
        AllocationSitePointsToSet.key
    }

    override protected def emptyPointsToSet: AllocationSitePointsToSet = NoAllocationSites
}

object AllocationSiteBasedPointsToBasedCallGraphAnalysisScheduler extends CallGraphAnalysisScheduler {

    override def uses: Set[PropertyBounds] = {
        super.uses + PropertyBounds.ub(AllocationSitePointsToSet)
    }

    override def initializeAnalysis(
        p: SomeProject
    ): AllocationSiteBasedPointsToBasedCallGraphAnalysis = {
        new AllocationSiteBasedPointsToBasedCallGraphAnalysis(p)
    }
}