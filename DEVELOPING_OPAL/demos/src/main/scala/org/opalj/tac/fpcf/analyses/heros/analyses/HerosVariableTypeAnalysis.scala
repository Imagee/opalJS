/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj.tac.fpcf.analyses.heros.analyses

import heros.{FlowFunction, FlowFunctions, TwoElementSet}
import heros.flowfunc.{Identity, KillAll}
import org.opalj.br.{ArrayType, DeclaredMethod, FieldType, Method}
import org.opalj.br.analyses.{DeclaredMethods, DeclaredMethodsKey, SomeProject}
import org.opalj.br.fpcf.PropertyStoreKey
import org.opalj.collection.immutable.EmptyIntTrieSet
import org.opalj.fpcf.{FinalEP, PropertyStore}
import org.opalj.tac._
import org.opalj.tac.fpcf.analyses.heros.cfg.OpalForwardICFG
import org.opalj.tac.fpcf.analyses.ifds.{JavaIFDSProblem, JavaStatement}
import org.opalj.tac.fpcf.analyses.ifds.old.{CalleeType, VTAFact, VTANullFact, VariableType}
import org.opalj.tac.fpcf.properties.cg.Callees
import org.opalj.util.Milliseconds
import org.opalj.value.ValueInformation

import java.io.File
import java.util
import java.util.Collections
import scala.annotation.tailrec
import scala.jdk.CollectionConverters._

/**
 * An implementation of the IFDSBasedVariableTypeAnalysis in the Heros framework.
 * For a documentation of the data flow functions, see IFDSBasedVariableTypeAnalysis.
 *
 * @author Mario Trageser
 */
class HerosVariableTypeAnalysis(
        p:              SomeProject,
        icfg:           OpalForwardICFG,
        initialMethods: Map[Method, util.Set[VTAFact]]
) extends HerosAnalysis[VTAFact](p, icfg) {

    override val initialSeeds: util.Map[JavaStatement, util.Set[VTAFact]] = {
        var result: Map[JavaStatement, util.Set[VTAFact]] = Map.empty
        for ((m, facts) <- initialMethods) {
            result += icfg.getStartPointsOf(m).iterator().next() -> facts
        }
        result.asJava
    }

    override def createZeroValue(): VTAFact = VTANullFact

    override protected def createFlowFunctionsFactory(): FlowFunctions[JavaStatement, VTAFact, Method] = {

        new FlowFunctions[JavaStatement, VTAFact, Method]() {

            override def getNormalFlowFunction(
                statement: JavaStatement,
                succ:      JavaStatement
            ): FlowFunction[VTAFact] = {
                if (!insideAnalysisContext(statement.method)) return KillAll.v()
                val stmt = statement.stmt
                stmt.astID match {
                    case Assignment.ASTID =>
                        (source: VTAFact) => {
                            val fact =
                                newFact(statement.method, statement.stmt.asAssignment.expr, statement.index, source)
                            if (fact.isDefined) TwoElementSet.twoElementSet(source, fact.get)
                            else Collections.singleton(source)
                        }
                    case ArrayStore.ASTID =>
                        (source: VTAFact) => {
                            val flow = scala.collection.mutable.Set.empty[VTAFact]
                            flow += source
                            newFact(statement.method, stmt.asArrayStore.value, statement.index, source).foreach {
                                case VariableType(_, t, upperBound) if !(t.isArrayType && t.asArrayType.dimensions <= 254) =>
                                    stmt.asArrayStore.arrayRef.asVar.definedBy
                                        .foreach(flow += VariableType(_, ArrayType(t), upperBound))
                                case _ => // Nothing to do
                            }
                            flow.asJava
                        }
                    case _ => Identity.v()
                }
            }

            override def getCallFlowFunction(stmt: JavaStatement, callee: Method): FlowFunction[VTAFact] =
                if (!insideAnalysisContext(callee)) KillAll.v()
                else {
                    val callObject = asCall(stmt.stmt)
                    val allParams = callObject.allParams
                    source: VTAFact => {
                        val flow = scala.collection.mutable.Set.empty[VTAFact]
                        source match {
                            case VariableType(definedBy, t, upperBound) =>
                                allParams.iterator.zipWithIndex.foreach {
                                    case (parameter, parameterIndex) if parameter.asVar.definedBy.contains(definedBy) =>
                                        flow += VariableType(
                                            JavaIFDSProblem
                                                .switchParamAndVariableIndex(parameterIndex, callee.isStatic),
                                            t,
                                            upperBound
                                        )
                                    case _ =>
                                }
                            case _ =>
                        }
                        flow.asJava
                    }
                }

            override def getReturnFlowFunction(
                stmt:   JavaStatement,
                callee: Method,
                exit:   JavaStatement,
                succ:   JavaStatement
            ): FlowFunction[VTAFact] =
                if (exit.stmt.astID == ReturnValue.ASTID && stmt.stmt.astID == Assignment.ASTID) {
                    val returnValue = exit.stmt.asReturnValue.expr.asVar
                    source: VTAFact => {
                        source match {
                            // If we know the type of the return value, we create a fact for the assigned variable.
                            case VariableType(definedBy, t, upperBound) if returnValue.definedBy.contains(definedBy) =>
                                Collections.singleton(VariableType(stmt.index, t, upperBound))
                            case _ => Collections.emptySet()
                        }
                    }
                } else KillAll.v()

            override def getCallToReturnFlowFunction(
                statement: JavaStatement,
                succ:      JavaStatement
            ): FlowFunction[VTAFact] = {
                if (!insideAnalysisContext(statement.method)) return KillAll.v()
                val stmt = statement.stmt
                val calleeDefinitionSites = asCall(stmt).receiverOption
                    .map(callee => callee.asVar.definedBy)
                    .getOrElse(EmptyIntTrieSet)
                val callOutsideOfAnalysisContext =
                    getCallees(statement).exists(
                        method =>
                            !((method.hasSingleDefinedMethod || method.hasMultipleDefinedMethods) && insideAnalysisContext(
                                method.definedMethod
                            ))
                    )
                source: VTAFact => {
                    val result = scala.collection.mutable.Set[VTAFact](source)
                    source match {
                        case VariableType(index, t, upperBound) if calleeDefinitionSites.contains(index) =>
                            result += CalleeType(statement.index, t, upperBound)
                        case _ =>
                    }
                    if (callOutsideOfAnalysisContext) {
                        val returnType = asCall(stmt).descriptor.returnType
                        if (stmt.astID == Assignment.ASTID && returnType.isReferenceType) {
                            result += VariableType(statement.index, returnType.asReferenceType, upperBound = true)
                        }
                    }
                    result.asJava
                }
            }
        }
    }

    private def newFact(
        method:         Method,
        expression:     Expr[DUVar[ValueInformation]],
        statementIndex: Int,
        source:         VTAFact
    ): Option[VariableType] = expression.astID match {
        case New.ASTID =>
            source match {
                case VTANullFact =>
                    Some(VariableType(statementIndex, expression.asNew.tpe, upperBound = false))
                case _ => None
            }
        case Var.ASTID =>
            source match {
                case VariableType(index, t, upperBound) if expression.asVar.definedBy.contains(index) =>
                    Some(VariableType(statementIndex, t, upperBound))
                case _ => None
            }
        case ArrayLoad.ASTID =>
            source match {
                case VariableType(index, t, upperBound) if isArrayOfObjectType(t) &&
                    expression.asArrayLoad.arrayRef.asVar.definedBy.contains(index) =>
                    Some(VariableType(statementIndex, t.asArrayType.elementType.asReferenceType, upperBound))
                case _ => None
            }
        case GetField.ASTID | GetStatic.ASTID =>
            val t = expression.asFieldRead.declaredFieldType
            if (t.isReferenceType)
                Some(VariableType(statementIndex, t.asReferenceType, upperBound = true))
            else None
        case _ => None
    }

    @tailrec private def isArrayOfObjectType(
        t:                 FieldType,
        includeObjectType: Boolean   = false
    ): Boolean = {
        if (t.isArrayType) isArrayOfObjectType(t.asArrayType.elementType, includeObjectType = true)
        else if (t.isObjectType && includeObjectType) true
        else false
    }

    private def insideAnalysisContext(callee: Method): Boolean =
        callee.body.isDefined && (callee.classFile.fqn.startsWith("java/lang") ||
            callee.classFile.fqn.startsWith("org/opalj/fpcf/fixtures/vta"))

    private def getCallees(statement: JavaStatement): Iterator[DeclaredMethod] = {
        val context = typeProvider.newContext(declaredMethods(statement.method))
        val FinalEP(_, callees) = propertyStore(declaredMethods(statement.method), Callees.key)
        callees.directCallees(context, statement.stmt.pc).map(_.method)
    }

}

class HerosVariableTypeAnalysisRunner
    extends HerosAnalysisRunner[VTAFact, HerosVariableTypeAnalysis] {

    override protected def createAnalysis(p: SomeProject): HerosVariableTypeAnalysis = {
        implicit val declaredMethods: DeclaredMethods = p.get(DeclaredMethodsKey)
        implicit val propertyStore: PropertyStore = p.get(PropertyStoreKey)
        val initialMethods =
            p.allProjectClassFiles
                .filter(_.fqn.startsWith("java/lang"))
                .flatMap(classFile => classFile.methods)
                .filter(isEntryPoint)
                .map(method => method -> entryPointsForMethod(method).asJava)
                .toMap
        new HerosVariableTypeAnalysis(p, new OpalForwardICFG(p), initialMethods)
    }

    override protected def printResultsToConsole(
        analysis:     HerosVariableTypeAnalysis,
        analysisTime: Milliseconds
    ): Unit = {}

    private def isEntryPoint(
        method: Method
    )(implicit declaredMethods: DeclaredMethods, propertyStore: PropertyStore): Boolean = {
        method.body.isDefined && HerosAnalysis.canBeCalledFromOutside(method)
    }

    private def entryPointsForMethod(method: Method): Set[VTAFact] = {
        (method.descriptor.parameterTypes.zipWithIndex.collect {
            case (t, index) if t.isReferenceType =>
                VariableType(
                    JavaIFDSProblem.switchParamAndVariableIndex(index, method.isStatic),
                    t.asReferenceType,
                    upperBound = true
                )
        } :+ VTANullFact).toSet
    }

}

object HerosVariableTypeAnalysisRunner {
    def main(args: Array[String]): Unit = {
        val fileIndex = args.indexOf("-f")
        new HerosVariableTypeAnalysisRunner().run(
            if (fileIndex >= 0) Some(new File(args(fileIndex + 1))) else None
        )
    }
}
