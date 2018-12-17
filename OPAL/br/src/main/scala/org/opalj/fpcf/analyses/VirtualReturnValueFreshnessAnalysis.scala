/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package fpcf
package analyses

import org.opalj.br.DeclaredMethod
import org.opalj.br.analyses.SomeProject
import org.opalj.br.analyses.DeclaredMethods
import org.opalj.br.analyses.DeclaredMethodsKey
import org.opalj.fpcf.properties.NoFreshReturnValue
import org.opalj.fpcf.properties.PrimitiveReturnValue
import org.opalj.fpcf.properties.ReturnValueFreshness
import org.opalj.fpcf.properties.VFreshReturnValue
import org.opalj.fpcf.properties.VNoFreshReturnValue
import org.opalj.fpcf.properties.VPrimitiveReturnValue
import org.opalj.fpcf.properties.VirtualMethodReturnValueFreshness

/**
 * An analysis that aggregates whether the return value for all possible methods represented by a
 * given [[org.opalj.br.DeclaredMethod]] are always freshly allocated.
 *
 * @author Florian Kuebler
 */
class VirtualReturnValueFreshnessAnalysis private[analyses] (
        final val project: SomeProject
) extends FPCFAnalysis {

    private[this] val declaredMethods: DeclaredMethods = project.get(DeclaredMethodsKey)

    def determineFreshness(m: DeclaredMethod): ProperPropertyComputationResult = {
        if (m.descriptor.returnType.isBaseType || m.descriptor.returnType.isVoidType) {
            return Result(m, VPrimitiveReturnValue)
        }

        var dependees: Set[EOptionP[Entity, Property]] = Set.empty

        if (m.declaringClassType.isArrayType) {
            throw new NotImplementedError()
        }

        val methods = project.virtualCall(
            m.declaringClassType.asObjectType.packageName,
            m.declaringClassType,
            m.name,
            m.descriptor
        )

        var temporary: VirtualMethodReturnValueFreshness = VFreshReturnValue

        for (method ← methods) {
            val rvf = propertyStore(declaredMethods(method), ReturnValueFreshness.key)
            handleReturnValueFreshness(rvf).foreach(return _)
        }

        def handleReturnValueFreshness(
            eOptionP: EOptionP[DeclaredMethod, ReturnValueFreshness]
        ): Option[ProperPropertyComputationResult] = eOptionP match {
            case FinalP(NoFreshReturnValue) ⇒
                Some(Result(m, VNoFreshReturnValue))

            case FinalP(PrimitiveReturnValue) ⇒
                throw new RuntimeException("unexpected property")

            case ep @ UBP(p) ⇒
                temporary = temporary meet p.asVirtualMethodReturnValueFreshness
                if (ep.isRefinable)
                    dependees += ep
                None

            case epk ⇒
                dependees += epk
                None
        }

        def returnResult(): ProperPropertyComputationResult = {
            if (dependees.isEmpty)
                Result(m, temporary)
            else
                InterimResult(m, VNoFreshReturnValue, temporary, dependees, c)
        }

        def c(someEPS: SomeEPS): ProperPropertyComputationResult = {

            dependees = dependees.filter(_.e ne someEPS.e)

            val r = handleReturnValueFreshness(
                someEPS.asInstanceOf[EOptionP[DeclaredMethod, ReturnValueFreshness]]
            )
            if (r.isDefined) return r.get;

            returnResult()
        }

        returnResult()
    }

}

sealed trait VirtualReturnValueFreshnessAnalysisScheduler extends ComputationSpecification {

    final def derivedProperty: PropertyBounds = {
        PropertyBounds.lub(VirtualMethodReturnValueFreshness)
    }

    final override def uses: Set[PropertyBounds] = Set(PropertyBounds.lub(ReturnValueFreshness))

}

object EagerVirtualReturnValueFreshnessAnalysis
    extends VirtualReturnValueFreshnessAnalysisScheduler
    with BasicFPCFEagerAnalysisScheduler {

    override def derivesEagerly: Set[PropertyBounds] = Set(derivedProperty)

    override def derivesCollaboratively: Set[PropertyBounds] = Set.empty

    override def start(p: SomeProject, ps: PropertyStore, unused: Null): FPCFAnalysis = {
        val declaredMethods = p.get(DeclaredMethodsKey).declaredMethods
        val analysis = new VirtualReturnValueFreshnessAnalysis(p)
        ps.scheduleEagerComputationsForEntities(declaredMethods)(
            analysis.determineFreshness
        )
        analysis
    }
}

object LazyVirtualReturnValueFreshnessAnalysis
    extends VirtualReturnValueFreshnessAnalysisScheduler
    with BasicFPCFLazyAnalysisScheduler {

    override def derivesLazily: Some[PropertyBounds] = Some(derivedProperty)

    override def register(p: SomeProject, ps: PropertyStore, unused: Null): FPCFAnalysis = {
        val analysis = new VirtualReturnValueFreshnessAnalysis(p)
        ps.registerLazyPropertyComputation(
            VirtualMethodReturnValueFreshness.key,
            analysis.determineFreshness
        )
        analysis
    }
}
