/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package tac
package fpcf
package analyses
package pointsto

import scala.collection.mutable.ArrayBuffer

import org.opalj.collection.immutable.IntTrieSet
import org.opalj.fpcf.Entity
import org.opalj.fpcf.EOptionP
import org.opalj.fpcf.EPK
import org.opalj.fpcf.InterimEUBP
import org.opalj.fpcf.InterimPartialResult
import org.opalj.fpcf.PartialResult
import org.opalj.fpcf.ProperPropertyComputationResult
import org.opalj.fpcf.Property
import org.opalj.fpcf.Result
import org.opalj.fpcf.Results
import org.opalj.fpcf.SomeEOptionP
import org.opalj.fpcf.SomeEPK
import org.opalj.fpcf.SomeEPS
import org.opalj.fpcf.UBP
import org.opalj.br.ArrayType
import org.opalj.br.fpcf.properties.pointsto.allocationSiteLongToTypeId
import org.opalj.br.Field
import org.opalj.br.ReferenceType
import org.opalj.br.fpcf.properties.pointsto.isEmptyArrayAllocationSite
import org.opalj.br.fpcf.properties.pointsto.PointsToSetLike
import org.opalj.br.DeclaredMethod
import org.opalj.tac.common.DefinitionSite

trait PointsToAnalysisBase extends AbstractPointsToBasedAnalysis {

    override protected[this]type State = PointsToAnalysisState[ElementType, PointsToSet]
    override protected[this]type DependerType = Entity

    protected[this] def handleCallReceiver(
        receiverDefSites: IntTrieSet,
        target:           DeclaredMethod,
        isNonVirtualCall: Boolean
    )(implicit state: State): Unit = {
        val fps = formalParameters(target)
        val declClassType = target.declaringClassType
        val tgtMethod = target.definedMethod
        val filter = if (isNonVirtualCall) {
            t: ReferenceType ⇒ classHierarchy.isSubtypeOf(t, declClassType)
        } else {
            val overrides =
                if (project.overridingMethods.contains(tgtMethod))
                    project.overridingMethods(tgtMethod).map(_.classFile.thisType) -
                        declClassType
                else
                    Set.empty
            // TODO this might not be 100% correct in some corner cases
            t: ReferenceType ⇒
                classHierarchy.isSubtypeOf(t, declClassType) &&
                    !overrides.exists(st ⇒ classHierarchy.isSubtypeOf(t, st))
        }
        val fp = fps(0)
        val ptss = currentPointsToOfDefSites(fp, receiverDefSites, filter)
        state.includeSharedPointsToSets(
            fp,
            ptss,
            filter
        )
    }

    protected[this] def handleCallParameter(
        paramDefSites: IntTrieSet,
        paramIndex:    Int,
        target:        DeclaredMethod
    )(implicit state: State): Unit = {
        val fps = formalParameters(target)
        val paramType = target.descriptor.parameterType(paramIndex)
        if (paramType.isReferenceType) {
            val fp = fps(paramIndex + 1)
            val filter = { t: ReferenceType ⇒
                classHierarchy.isSubtypeOf(t, paramType.asReferenceType)
            }
            state.includeSharedPointsToSets(
                fp,
                currentPointsToOfDefSites(fp, paramDefSites, filter),
                filter
            )
        }
    }

    protected[this] def handleGetField(
        field: AField, pc: Int, objRefDefSites: IntTrieSet
    )(implicit state: State): Unit = {
        val tac = state.tac
        val index = tac.pcToIndex(pc)
        val nextStmt = tac.stmts(index + 1)
        val filter = nextStmt match {
            case Checkcast(_, value, cmpTpe) if value.asVar.definedBy.contains(index) ⇒
                t: ReferenceType ⇒ classHierarchy.isSubtypeOf(t, cmpTpe)
            case _ ⇒
                PointsToSetLike.noFilter
        }
        val defSiteObject = definitionSites(state.method.definedMethod, pc)
        val fakeEntity = (defSiteObject, field, filter)
        state.addGetFieldEntity(fakeEntity)
        state.includeSharedPointsToSet(defSiteObject, emptyPointsToSet, PointsToSetLike.noFilter)
        currentPointsToOfDefSites(fakeEntity, objRefDefSites).foreach { pts ⇒
            pts.forNewestNElements(pts.numElements) { as ⇒
                // TODO: Refactor
                val fieldClassType = field.classType
                val asTypeId = allocationSiteLongToTypeId(as.asInstanceOf[Long])
                if (fieldClassType.id == asTypeId || classHierarchy.subtypeInformation(fieldClassType).get.containsId(asTypeId)) {
                    state.includeSharedPointsToSet(
                        defSiteObject,
                        // IMPROVE: Use LongRefPair to avoid boxing
                        currentPointsTo(defSiteObject, (as, field), filter),
                        filter
                    )
                }
            }
        }
    }

    protected[this] def handleGetStatic(field: Field, pc: Int)(implicit state: State): Unit = {
        val defSiteObject = definitionSites(state.method.definedMethod, pc)
        val tac = state.tac
        val index = tac.pcToIndex(pc)
        val nextStmt = tac.stmts(index + 1)
        val filter = nextStmt match {
            case Checkcast(_, value, cmpTpe) if value.asVar.definedBy.contains(index) ⇒
                t: ReferenceType ⇒ classHierarchy.isSubtypeOf(t, cmpTpe)
            case _ ⇒
                PointsToSetLike.noFilter
        }
        state.includeSharedPointsToSet(
            defSiteObject,
            currentPointsTo(defSiteObject, field, filter),
            filter
        )
    }

    protected[this] def handleArrayLoad(
        arrayType: ArrayType, pc: Int, arrayDefSites: IntTrieSet
    )(implicit state: State): Unit = {
        val defSiteObject = definitionSites(state.method.definedMethod, pc)
        val tac = state.tac
        val index = tac.pcToIndex(pc)
        val nextStmt = tac.stmts(index + 1)
        val filter = nextStmt match {
            case Checkcast(_, value, cmpTpe) if value.asVar.definedBy.contains(index) ⇒
                t: ReferenceType ⇒ {
                    classHierarchy.isSubtypeOf(t, cmpTpe)
                }
            case _ ⇒
                PointsToSetLike.noFilter
        }
        val fakeEntity = (defSiteObject, arrayType, filter)
        state.addArrayLoadEntity(fakeEntity)
        state.includeSharedPointsToSet(defSiteObject, emptyPointsToSet, PointsToSetLike.noFilter)
        currentPointsToOfDefSites(fakeEntity, arrayDefSites).foreach { pts ⇒
            pts.forNewestNElements(pts.numElements) { as ⇒
                val typeId = allocationSiteLongToTypeId(as.asInstanceOf[Long])
                if (typeId < 0 &&
                    classHierarchy.isSubtypeOf(ArrayType.lookup(typeId), arrayType)) {
                    state.includeSharedPointsToSet(
                        defSiteObject,
                        currentPointsTo(defSiteObject, ArrayEntity(as), filter),
                        filter
                    )
                }
            }
        }
    }

    protected[this] def handlePutField(
        field: AField, objRefDefSites: IntTrieSet, rhsDefSites: IntTrieSet
    )(implicit state: State): Unit = {
        val fakeEntity = (rhsDefSites, field)
        state.addPutFieldEntity(fakeEntity)
        val filter = { t: ReferenceType ⇒
            classHierarchy.isSubtypeOf(t, field.fieldType.asReferenceType)
        }
        currentPointsToOfDefSites(fakeEntity, objRefDefSites).foreach { pts ⇒
            pts.forNewestNElements(pts.numElements) { as ⇒
                // TODO: Refactor
                val fieldClassType = field.classType
                val asTypeId = allocationSiteLongToTypeId(as.asInstanceOf[Long])
                if (fieldClassType.id == asTypeId || classHierarchy.subtypeInformation(fieldClassType).get.containsId(asTypeId)) {
                    val fieldEntity = (as, field)
                    state.includeSharedPointsToSets(
                        fieldEntity,
                        currentPointsToOfDefSites(fieldEntity, rhsDefSites, filter),
                        filter
                    )
                }
            }
        }
    }

    protected[this] def handlePutStatic(field: Field, rhsDefSites: IntTrieSet)(implicit state: State): Unit = {
        val filter = { t: ReferenceType ⇒
            classHierarchy.isSubtypeOf(t, field.fieldType.asReferenceType)
        }
        state.includeSharedPointsToSets(
            field,
            currentPointsToOfDefSites(field, rhsDefSites, filter),
            filter
        )
    }

    protected[this] def handleArrayStore(
        arrayType: ArrayType, arrayDefSites: IntTrieSet, rhsDefSites: IntTrieSet
    )(implicit state: State): Unit = {
        val fakeEntity = (rhsDefSites, arrayType)
        state.addArrayStoreEntity(fakeEntity)
        currentPointsToOfDefSites(fakeEntity, arrayDefSites).foreach { pts ⇒
            pts.forNewestNElements(pts.numElements) { as ⇒
                val typeId = allocationSiteLongToTypeId(as.asInstanceOf[Long])
                if (typeId < 0 &&
                    classHierarchy.isSubtypeOf(ArrayType.lookup(typeId), arrayType) &&
                    !isEmptyArrayAllocationSite(as.asInstanceOf[Long])) {
                    val arrayEntity = ArrayEntity(as)
                    val componentType = ArrayType.lookup(typeId).componentType.asReferenceType
                    val filter = { t: ReferenceType ⇒
                        classHierarchy.isSubtypeOf(t, componentType)
                    }
                    state.includeSharedPointsToSets(
                        arrayEntity,
                        currentPointsToOfDefSites(arrayEntity, rhsDefSites, filter),
                        filter
                    )
                }
            }
        }
    }

    @inline protected[this] def currentPointsTo(
        depender: Entity, dependee: Entity, typeFilter: ReferenceType ⇒ Boolean
    )(implicit state: State): PointsToSet = {
        val epk = EPK(dependee, pointsToPropertyKey)
        val p2s = if (state.hasDependency(depender, epk)) {
            // IMPROVE: add a method to the state
            state.dependeesOf(depender)(epk)._1
        } else {
            val p2s = propertyStore(dependee, pointsToPropertyKey)
            if (p2s.isRefinable) {
                state.addDependee(depender, p2s, typeFilter)
            }
            p2s
        }
        pointsToUB(p2s.asInstanceOf[EOptionP[Entity, PointsToSet]])
    }

    @inline protected[this] def updatedDependees(
        eps: SomeEPS, oldDependees: Map[SomeEPK, (SomeEOptionP, ReferenceType ⇒ Boolean)]
    ): Map[SomeEPK, (SomeEOptionP, ReferenceType ⇒ Boolean)] = {
        val epk = eps.toEPK
        val typeFilter = oldDependees(epk)._2
        if (eps.isRefinable) {
            oldDependees + (epk → ((eps, typeFilter)))
        } else {
            oldDependees - epk
        }
    }

    @inline protected[this] def updatedPointsToSet(
        oldPointsToSet:         PointsToSet,
        newDependeePointsToSet: PointsToSet,
        dependee:               SomeEPS,
        oldDependees:           Map[SomeEPK, (SomeEOptionP, ReferenceType ⇒ Boolean)]
    ): PointsToSet = {
        val (oldDependee, typeFilter) = oldDependees(dependee.toEPK)
        val oldDependeePointsTo = oldDependee match {
            case UBP(ub: PointsToSet @unchecked)   ⇒ ub
            case _: EPK[_, PointsToSet @unchecked] ⇒ emptyPointsToSet
            case d ⇒
                throw new IllegalArgumentException(s"unexpected dependee $d")
        }

        oldPointsToSet.included(
            newDependeePointsToSet,
            oldDependeePointsTo.numElements,
            typeFilter
        )
    }

    @inline private[this] def getNumElements(eopt: SomeEOptionP): Int = {
        if (eopt.isEPK) 0
        else eopt.ub.asInstanceOf[PointsToSet].numElements
    }

    protected[this] def continuationForNewAllocationSitesAtPutField(
        knownPointsTo:  PointsToSet,
        rhsDefSitesEPS: Map[SomeEPK, SomeEOptionP],
        field:          AField,
        dependees:      Map[SomeEPK, (SomeEOptionP, ReferenceType ⇒ Boolean)]
    )(eps: SomeEPS): ProperPropertyComputationResult = {
        eps match {
            case UBP(newDependeePointsTo: PointsToSet @unchecked) ⇒
                val newDependees = updatedDependees(eps, dependees)
                var results: List[ProperPropertyComputationResult] = List.empty

                newDependeePointsTo.forNewestNElements(newDependeePointsTo.numElements - getNumElements(dependees(eps.toEPK)._1)) { as ⇒
                    // TODO: Refactor
                    val fieldClassType = field.classType
                    val asTypeId = allocationSiteLongToTypeId(as.asInstanceOf[Long])
                    if (fieldClassType.id == asTypeId || classHierarchy.subtypeInformation(fieldClassType).get.containsId(asTypeId)) {
                        val typeFilter = { t: ReferenceType ⇒
                            classHierarchy.isSubtypeOf(t, field.fieldType.asReferenceType)
                        }
                        results ++= createPartialResults(
                            (as, field),
                            rhsDefSitesEPS.mapValues((_, typeFilter)),
                            knownPointsTo,
                            { _.included(knownPointsTo, typeFilter) }
                        )
                    }
                }
                if (newDependees.nonEmpty) {
                    results ::= InterimPartialResult(
                        newDependees.values.map(_._1),
                        continuationForNewAllocationSitesAtPutField(
                            knownPointsTo, rhsDefSitesEPS, field, newDependees
                        )
                    )
                }
                Results(
                    results
                )
        }
    }

    protected[this] def continuationForNewAllocationSitesAtArrayStore(
        knownPointsTo:  PointsToSet,
        rhsDefSitesEPS: Map[SomeEPK, SomeEOptionP],
        arrayType:      ArrayType,
        dependees:      Map[SomeEPK, (SomeEOptionP, ReferenceType ⇒ Boolean)]
    )(eps: SomeEPS): ProperPropertyComputationResult = {
        eps match {
            case UBP(newDependeePointsTo: PointsToSet @unchecked) ⇒
                val newDependees = updatedDependees(eps, dependees)
                var results: List[ProperPropertyComputationResult] = List.empty
                newDependeePointsTo.forNewestNElements(newDependeePointsTo.numElements - getNumElements(dependees(eps.toEPK)._1)) { as ⇒
                    val typeId = allocationSiteLongToTypeId(as.asInstanceOf[Long])
                    if (typeId < 0 &&
                        classHierarchy.isSubtypeOf(ArrayType.lookup(typeId), arrayType) &&
                        !isEmptyArrayAllocationSite(as.asInstanceOf[Long])) {
                        val componentType = ArrayType.lookup(typeId).componentType.asReferenceType
                        val typeFilter = { t: ReferenceType ⇒
                            classHierarchy.isSubtypeOf(t, componentType)
                        }
                        results ++= createPartialResults(
                            ArrayEntity(as),
                            rhsDefSitesEPS.mapValues((_, typeFilter)),
                            knownPointsTo,
                            { _.included(knownPointsTo, typeFilter) }
                        )
                    }
                }
                if (newDependees.nonEmpty) {
                    results ::= InterimPartialResult(
                        newDependees.values.map(_._1),
                        continuationForNewAllocationSitesAtArrayStore(
                            knownPointsTo, rhsDefSitesEPS, arrayType, newDependees
                        )
                    )
                }
                Results(
                    results
                )
        }
    }

    // todo name
    protected[this] def continuationForNewAllocationSitesAtGetField(
        defSiteObject: DefinitionSite,
        field:         AField,
        filter:        ReferenceType ⇒ Boolean,
        dependees:     Map[SomeEPK, (SomeEOptionP, ReferenceType ⇒ Boolean)]
    )(eps: SomeEPS): ProperPropertyComputationResult = {
        eps match {
            case UBP(newDependeePointsTo: PointsToSet @unchecked) ⇒
                val newDependees = updatedDependees(eps, dependees)
                var nextDependees: List[SomeEOptionP] = Nil
                var newPointsTo = emptyPointsToSet
                newDependeePointsTo.forNewestNElements(newDependeePointsTo.numElements - getNumElements(dependees(eps.toEPK)._1)) { as ⇒
                    // TODO: Refactor
                    val fieldClassType = field.classType
                    val asTypeId = allocationSiteLongToTypeId(as.asInstanceOf[Long])
                    if (fieldClassType.id == asTypeId || classHierarchy.subtypeInformation(fieldClassType).get.containsId(asTypeId)) {
                        val fieldEntries = ps((as, field), pointsToPropertyKey)
                        newPointsTo = newPointsTo.included(pointsToUB(fieldEntries), filter)
                        if (fieldEntries.isRefinable)
                            nextDependees ::= fieldEntries
                    }
                }

                var results: Seq[ProperPropertyComputationResult] = createPartialResults(
                    defSiteObject,
                    nextDependees.iterator.map(d ⇒ d.toEPK → ((d, filter))).toMap,
                    newPointsTo,
                    { _.included(newPointsTo, filter) }
                )

                if (newDependees.nonEmpty) {
                    results +:= InterimPartialResult(
                        newDependees.values.map(_._1),
                        continuationForNewAllocationSitesAtGetField(
                            defSiteObject, field, filter, newDependees
                        )
                    )
                }

                Results(results)
            case _ ⇒ throw new IllegalArgumentException(s"unexpected update: $eps")
        }
    }

    // todo name
    protected[this] def continuationForNewAllocationSitesAtArrayLoad(
        defSiteObject: DefinitionSite,
        arrayType:     ArrayType,
        filter:        ReferenceType ⇒ Boolean,
        dependees:     Map[SomeEPK, (SomeEOptionP, ReferenceType ⇒ Boolean)]
    )(eps: SomeEPS): ProperPropertyComputationResult = {
        eps match {
            case UBP(newDependeePointsTo: PointsToSet @unchecked) ⇒
                val newDependees = updatedDependees(eps, dependees)
                var nextDependees: List[SomeEOptionP] = Nil
                var newPointsTo = emptyPointsToSet
                newDependeePointsTo.forNewestNElements(newDependeePointsTo.numElements - getNumElements(dependees(eps.toEPK)._1)) { as ⇒
                    val typeId = allocationSiteLongToTypeId(as.asInstanceOf[Long])
                    if (typeId < 0 && classHierarchy.isSubtypeOf(ArrayType.lookup(typeId), arrayType)) {
                        val arrayEntries = ps(ArrayEntity(as), pointsToPropertyKey)
                        newPointsTo = newPointsTo.included(pointsToUB(arrayEntries), filter)
                        if (arrayEntries.isRefinable)
                            nextDependees ::= arrayEntries
                    }
                }

                var results: Seq[ProperPropertyComputationResult] =
                    createPartialResults(
                        defSiteObject,
                        nextDependees.iterator.map(d ⇒ d.toEPK → ((d, filter))).toMap,
                        newPointsTo,
                        { _.included(newPointsTo, filter) }
                    )

                if (newDependees.nonEmpty) {
                    results +:= InterimPartialResult(
                        newDependees.values.map(_._1),
                        continuationForNewAllocationSitesAtArrayLoad(
                            defSiteObject, arrayType, filter, newDependees
                        )
                    )
                }

                Results(results)
            case _ ⇒ throw new IllegalArgumentException(s"unexpected update: $eps")
        }
    }

    protected[this] def continuationForShared(
        e: Entity, dependees: Map[SomeEPK, (SomeEOptionP, ReferenceType ⇒ Boolean)]
    )(eps: SomeEPS): ProperPropertyComputationResult = {
        eps match {
            case UBP(newDependeePointsTo: PointsToSet @unchecked) ⇒
                val newDependees = updatedDependees(eps, dependees)

                val results = createPartialResults(
                    e,
                    newDependees,
                    newDependeePointsTo,
                    { old ⇒
                        updatedPointsToSet(
                            old,
                            newDependeePointsTo,
                            eps,
                            dependees
                        )
                    },
                    true
                )

                Results(results)

            case _ ⇒ throw new IllegalArgumentException(s"unexpected update: $eps")
        }
    }

    @inline protected[this] def createPartialResults(
        e:                   Entity,
        newDependees:        Map[SomeEPK, (SomeEOptionP, ReferenceType ⇒ Boolean)],
        newDependeePointsTo: PointsToSet,
        updatePointsTo:      PointsToSet ⇒ PointsToSet,
        isUpdate:            Boolean                                               = false
    ): Seq[ProperPropertyComputationResult] = {
        var results: Seq[ProperPropertyComputationResult] = Seq.empty

        if (newDependees.nonEmpty) {
            results +:= InterimPartialResult(
                newDependees.values.map(_._1),
                continuationForShared(e, newDependees)
            )
        }

        if (!isUpdate || (newDependeePointsTo ne emptyPointsToSet)) {
            results +:= PartialResult[Entity, PointsToSetLike[_, _, PointsToSet]](
                e,
                pointsToPropertyKey,
                {
                    case eps @ UBP(ub: PointsToSet @unchecked) ⇒
                        val newPointsToSet = updatePointsTo(ub)

                        if (newPointsToSet ne ub) {
                            Some(InterimEUBP(e, newPointsToSet))
                        } else {
                            None
                        }

                    case _: EPK[Entity, _] ⇒
                        val newPointsToSet = updatePointsTo(emptyPointsToSet)
                        if (isUpdate && (newPointsToSet eq emptyPointsToSet))
                            None
                        else
                            Some(InterimEUBP(e, newPointsToSet))

                    case eOptP ⇒
                        throw new IllegalArgumentException(s"unexpected eOptP: $eOptP")
                }
            )
        }

        results
    }

    protected[this] def createResults(
        state: State
    ): ArrayBuffer[ProperPropertyComputationResult] = {
        val results = ArrayBuffer.empty[ProperPropertyComputationResult]

        for ((e, pointsToSet) ← state.allocationSitePointsToSetsIterator) {
            results += Result(e, pointsToSet)
        }

        for ((e, pointsToSet) ← state.sharedPointsToSetsIterator) {
            results ++= createPartialResults(
                e,
                if (state.hasDependees(e)) state.dependeesOf(e) else Map.empty,
                pointsToSet,
                { _.included(pointsToSet) }
            )
        }

        for (fakeEntity ← state.getFieldsIterator) {
            if (state.hasDependees(fakeEntity)) {
                val (defSite, field, filter) = fakeEntity
                val dependees = state.dependeesOf(fakeEntity)
                results += InterimPartialResult(
                    dependees.values.map(_._1),
                    continuationForNewAllocationSitesAtGetField(
                        defSite, field, filter, dependees
                    )
                )
            }
        }

        for (fakeEntity ← state.putFieldsIterator) {
            if (state.hasDependees(fakeEntity)) {
                val (defSites, field) = fakeEntity
                val defSitesWithoutExceptions = defSites.iterator.filterNot(ai.isImplicitOrExternalException)
                var knownPointsTo = emptyPointsToSet
                val defSitesEPSs = defSitesWithoutExceptions.map[(EPK[Entity, Property], EOptionP[Entity, Property])] { ds ⇒
                    val rhsPTS =
                        ps(toEntity(ds, state.method, state.tac.stmts), pointsToPropertyKey)
                    knownPointsTo = knownPointsTo.included(pointsToUB(rhsPTS))
                    rhsPTS.toEPK → rhsPTS
                }.filter(_._2.isRefinable).toMap

                val dependees = state.dependeesOf(fakeEntity)
                if (defSitesEPSs.nonEmpty || (knownPointsTo ne emptyPointsToSet))
                    results += InterimPartialResult(
                        dependees.values.map(_._1),
                        continuationForNewAllocationSitesAtPutField(
                            knownPointsTo, defSitesEPSs, field, dependees
                        )
                    )
            }
        }

        for (fakeEntity ← state.arrayLoadsIterator) {
            if (state.hasDependees(fakeEntity)) {
                val (defSite, arrayType, filter) = fakeEntity
                val dependees = state.dependeesOf(fakeEntity)
                results += InterimPartialResult(
                    dependees.values.map(_._1),
                    continuationForNewAllocationSitesAtArrayLoad(
                        defSite, arrayType, filter, dependees
                    )
                )
            }
        }

        for (fakeEntity ← state.arrayStoresIterator) {
            if (state.hasDependees(fakeEntity)) {
                val (defSites, arrayType) = fakeEntity
                val defSitesWithoutExceptions = defSites.iterator.filterNot(ai.isImplicitOrExternalException)
                var knownPointsTo = emptyPointsToSet
                val defSitesEPSs = defSitesWithoutExceptions.map[(EPK[Entity, Property], EOptionP[Entity, Property])] { ds ⇒
                    val rhsPTS =
                        ps(toEntity(ds, state.method, state.tac.stmts), pointsToPropertyKey)
                    knownPointsTo = knownPointsTo.included(pointsToUB(rhsPTS))
                    rhsPTS.toEPK → rhsPTS
                }.filter(_._2.isRefinable).toMap

                val dependees = state.dependeesOf(fakeEntity)
                if (defSitesEPSs.nonEmpty || (knownPointsTo ne emptyPointsToSet))
                    results += InterimPartialResult(
                        dependees.values.map(_._1),
                        continuationForNewAllocationSitesAtArrayStore(
                            knownPointsTo, defSitesEPSs, arrayType, dependees
                        )
                    )
            }
        }

        results
    }
}
