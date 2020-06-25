/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj.tac.fpcf.analyses.purity

import org.opalj.ai
import org.opalj.ai.ValueOrigin
import org.opalj.ai.isImmediateVMException
import org.opalj.br.ComputationalTypeReference
import org.opalj.br.DeclaredMethod
import org.opalj.br.DefinedMethod
import org.opalj.br.Field
import org.opalj.br.Method
import org.opalj.br.ObjectType
import org.opalj.br.analyses.DeclaredMethods
import org.opalj.br.analyses.DeclaredMethodsKey
import org.opalj.br.fpcf.FPCFAnalysis
import org.opalj.br.fpcf.analyses.ConfiguredPurity
import org.opalj.br.fpcf.analyses.ConfiguredPurityKey
import org.opalj.br.fpcf.properties.ClassImmutability_new
import org.opalj.br.fpcf.properties.CompileTimePure
import org.opalj.br.fpcf.properties.DeepImmutableClass
import org.opalj.br.fpcf.properties.DeepImmutableType
import org.opalj.br.fpcf.properties.ImmutableReference
import org.opalj.br.fpcf.properties.ImpureByAnalysis
import org.opalj.br.fpcf.properties.ImpureByLackOfInformation
import org.opalj.br.fpcf.properties.LazyInitializedReference
import org.opalj.br.fpcf.properties.MutableReference
import org.opalj.br.fpcf.properties.Pure
import org.opalj.br.fpcf.properties.Purity
import org.opalj.br.fpcf.properties.ReferenceImmutability
import org.opalj.br.fpcf.properties.SideEffectFree
import org.opalj.br.fpcf.properties.TypeImmutability_new
import org.opalj.br.fpcf.properties.cg.Callees
import org.opalj.collection.immutable.EmptyIntTrieSet
import org.opalj.collection.immutable.IntTrieSet
import org.opalj.fpcf.EOptionP
import org.opalj.fpcf.Entity
import org.opalj.fpcf.FinalEP
import org.opalj.fpcf.FinalP
import org.opalj.fpcf.InterimLUBP
import org.opalj.fpcf.InterimResult
import org.opalj.fpcf.InterimUBP
import org.opalj.fpcf.LBP
import org.opalj.fpcf.ProperPropertyComputationResult
import org.opalj.fpcf.Property
import org.opalj.fpcf.Result
import org.opalj.fpcf.SomeEOptionP
import org.opalj.fpcf.UBPS
import org.opalj.log.GlobalLogContext
import org.opalj.log.OPALLogger
import org.opalj.tac.ArrayLength
import org.opalj.tac.ArrayLoad
import org.opalj.tac.ArrayStore
import org.opalj.tac.Assignment
import org.opalj.tac.BinaryExpr
import org.opalj.tac.Call
import org.opalj.tac.CaughtException
import org.opalj.tac.Checkcast
import org.opalj.tac.ClassConst
import org.opalj.tac.Compare
import org.opalj.tac.DUVar
import org.opalj.tac.DoubleConst
import org.opalj.tac.Expr
import org.opalj.tac.ExprStmt
import org.opalj.tac.FieldRead
import org.opalj.tac.FloatConst
import org.opalj.tac.GetField
import org.opalj.tac.GetStatic
import org.opalj.tac.Goto
import org.opalj.tac.If
import org.opalj.tac.InstanceOf
import org.opalj.tac.IntConst
import org.opalj.tac.InvokedynamicFunctionCall
import org.opalj.tac.InvokedynamicMethodCall
import org.opalj.tac.JSR
import org.opalj.tac.LongConst
import org.opalj.tac.MethodHandleConst
import org.opalj.tac.MethodTypeConst
import org.opalj.tac.MonitorEnter
import org.opalj.tac.MonitorExit
import org.opalj.tac.New
import org.opalj.tac.NewArray
import org.opalj.tac.NonVirtualFunctionCall
import org.opalj.tac.NonVirtualMethodCall
import org.opalj.tac.Nop
import org.opalj.tac.NullExpr
import org.opalj.tac.Param
import org.opalj.tac.PrefixExpr
import org.opalj.tac.PrimitiveTypecastExpr
import org.opalj.tac.PutField
import org.opalj.tac.PutStatic
import org.opalj.tac.Ret
import org.opalj.tac.Return
import org.opalj.tac.ReturnValue
import org.opalj.tac.StaticFunctionCall
import org.opalj.tac.StaticMethodCall
import org.opalj.tac.Stmt
import org.opalj.tac.StringConst
import org.opalj.tac.Switch
import org.opalj.tac.TACMethodParameter
import org.opalj.tac.TACode
import org.opalj.tac.Throw
import org.opalj.tac.Var
import org.opalj.tac.VirtualFunctionCall
import org.opalj.tac.VirtualMethodCall
import org.opalj.tac.fpcf.analyses.cg.uVarForDefSites
import org.opalj.tac.fpcf.properties.TACAI
import org.opalj.value.ValueInformation

import scala.annotation.switch

/**
 * Base trait for analyses that analyze the purity of methods.
 *
 * Provides types and methods needed for purity analyses.
 */
trait AbstractPurityAnalysis_new extends FPCFAnalysis {

    /** The type of the TAC domain. */
    type V = DUVar[ValueInformation]

    /**
     * The state of the analysis.
     * Analyses are expected to extend this trait with the information they need.
     *
     * lbPurity - The current minimum possible purity level for the method
     * ubPurity - The current maximum purity level for the method
     * method - The currently analyzed method
     * declClass - The declaring class of the currently analyzed method
     * code - The code of the currently analyzed method
     */
    trait AnalysisState {
        var lbPurity: Purity
        var ubPurity: Purity
        val method: Method
        val definedMethod: DeclaredMethod
        val declClass: ObjectType
        var pcToIndex: Array[Int]
        var code: Array[Stmt[V]]
    }

    type StateType <: AnalysisState

    protected[this] def raterFqn: String

    val rater: DomainSpecificRater

    implicit protected[this] val declaredMethods: DeclaredMethods = project.get(DeclaredMethodsKey)

    val configuredPurity: ConfiguredPurity = project.get(ConfiguredPurityKey)

    /**
     * Reduces the maxPurity of the current method to at most the given purity level.
     */
    def reducePurityLB(newLevel: Purity)(implicit state: StateType): Unit = {
        state.lbPurity = state.lbPurity meet newLevel
    }

    /**
     * Reduces the minPurity and maxPurity of the current method to at most the given purity level.
     */
    def atMost(newLevel: Purity)(implicit state: StateType): Unit = {
        state.lbPurity = state.lbPurity meet newLevel
        state.ubPurity = state.ubPurity meet newLevel
    }

    /**
     * Examines whether the given expression denotes an object/array that is local to the current
     * method, i.e. the method has control over the object/array and actions on it might not
     * influence purity.
     *
     * @param otherwise The maxPurity will be reduced to at most this level if the expression is not
     *                  local.
     */
    def isLocal(
        expr:             Expr[V],
        otherwise:        Purity,
        excludedDefSites: IntTrieSet = EmptyIntTrieSet
    )(implicit state: StateType): Boolean

    /**
     * Checks whether the statement, which is the origin of an exception, directly created the
     * exception or if the VM instantiated the exception. Here, we are only concerned about the
     * exceptions thrown by the instructions not about exceptions that are transitively thrown;
     * e.g. if a method is called.
     * TODO We need this method because currently, for exceptions that terminate the method, no
     * definitions are recorded. Once this is done, use that information instead to determine
     * whether it may be an immediate exception or not.
     */
    def isSourceOfImmediateException(origin: ValueOrigin)(implicit state: StateType): Boolean = {

        def evaluationMayCauseVMLevelException(expr: Expr[V]): Boolean = {
            (expr.astID: @switch) match {

                case NonVirtualFunctionCall.ASTID | VirtualFunctionCall.ASTID ⇒
                    val rcvr = expr.asInstanceFunctionCall.receiver
                    !rcvr.isVar || rcvr.asVar.value.asReferenceValue.isNull.isYesOrUnknown

                case StaticFunctionCall.ASTID ⇒ false

                case _                        ⇒ true
            }
        }

        val stmt = state.code(origin)
        (stmt.astID: @switch) match {
            case StaticMethodCall.ASTID ⇒ false // We are looking for implicit exceptions only

            case Throw.ASTID ⇒
                stmt.asThrow.exception.asVar.value.asReferenceValue.isNull.isNotNo

            case NonVirtualMethodCall.ASTID | VirtualMethodCall.ASTID ⇒
                val rcvr = stmt.asInstanceMethodCall.receiver
                !rcvr.isVar || rcvr.asVar.value.asReferenceValue.isNull.isNotNo

            case Assignment.ASTID ⇒ evaluationMayCauseVMLevelException(stmt.asAssignment.expr)

            case ExprStmt.ASTID   ⇒ evaluationMayCauseVMLevelException(stmt.asExprStmt.expr)

            case _                ⇒ true
        }
    }

    /**
     * Examines whether a call constitutes a domain-specific action using the domain-specific rater.
     * If it is, the maxPurity will be reduced to at most the domain-specific purity returned by the
     * domain-specific rater.
     */
    def isDomainSpecificCall(
        call:     Call[V],
        receiver: Option[Expr[V]]
    )(implicit state: StateType): Boolean = {
        implicit val code: Array[Stmt[V]] = state.code
        val ratedResult = rater.handleCall(call, receiver)
        if (ratedResult.isDefined)
            atMost(ratedResult.get)
        ratedResult.isDefined
    }

    /**
     * Examines a statement for its influence on the method's purity.
     * This method will return false for impure statements, so evaluation can be terminated early.
     */
    def checkPurityOfStmt(stmt: Stmt[V])(implicit state: StateType): Boolean = {
        val isStmtNotImpure = (stmt.astID: @switch) match {
            // For method calls, purity will be checked later
            case StaticMethodCall.ASTID | NonVirtualMethodCall.ASTID | VirtualMethodCall.ASTID ⇒
                true

            // We don't handle unresolved Invokedynamics
            // - either OPAL removes it or we forget about it
            case InvokedynamicMethodCall.ASTID ⇒
                atMost(ImpureByAnalysis)
                false

            // Returning objects/arrays is pure, if the returned object/array is locally initialized
            // and non-escaping or the object is immutable
            case ReturnValue.ASTID ⇒
                checkPurityOfReturn(stmt.asReturnValue.expr)
                true
            case Throw.ASTID ⇒
                checkPurityOfReturn(stmt.asThrow.exception)
                true

            // Synchronization on non-escaping locally initialized objects/arrays is pure (and
            // useless...)
            case MonitorEnter.ASTID | MonitorExit.ASTID ⇒
                isLocal(stmt.asSynchronizationStmt.objRef, ImpureByAnalysis)

            // Storing into non-escaping locally initialized objects/arrays is pure
            case ArrayStore.ASTID ⇒ isLocal(stmt.asArrayStore.arrayRef, ImpureByAnalysis)
            case PutField.ASTID   ⇒ isLocal(stmt.asPutField.objRef, ImpureByAnalysis)

            case PutStatic.ASTID ⇒
                // Note that a putstatic is not necessarily pure/sideeffect free, even if it
                // is executed within a static initializer to initialize a field of
                // `the` class; it is possible that the initialization triggers the
                // initialization of another class which reads the value of this static field.
                // See
                // https://stackoverflow.com/questions/6416408/static-circular-dependency-in-java
                // for an in-depth discussion.
                // (Howevever, if we would check for cycles, we could determine that it is pure,
                // but this is not considered to be too useful...)
                atMost(ImpureByAnalysis)
                false

            // Creating implicit exceptions is side-effect free (because of fillInStackTrace)
            // but it may be ignored as domain-specific
            case CaughtException.ASTID ⇒
                for {
                    origin ← stmt.asCaughtException.origins
                    if isImmediateVMException(origin)
                } {
                    val baseOrigin = state.code(ai.underlyingPC(origin))
                    val ratedResult = rater.handleException(baseOrigin)
                    if (ratedResult.isDefined) atMost(ratedResult.get)
                    else atMost(SideEffectFree)
                }
                true

            // Reference comparisons may have different results for structurally equal values
            case If.ASTID ⇒
                val If(_, left, _, right, _) = stmt
                if (left.cTpe eq ComputationalTypeReference)
                    if (!(isLocal(left, CompileTimePure) || isLocal(right, CompileTimePure)))
                        atMost(SideEffectFree)
                true

            // The following statements do not further influence purity
            case Goto.ASTID | JSR.ASTID | Ret.ASTID | Switch.ASTID | Assignment.ASTID | Return.ASTID |
                Nop.ASTID | ExprStmt.ASTID | Checkcast.ASTID ⇒
                true
        }

        isStmtNotImpure && stmt.forallSubExpressions(checkPurityOfExpr)
    }

    /**
     * Examines an expression for its influence on the method's purity.
     * This method will return false for impure expressions, so evaluation can be terminated early.
     */
    def checkPurityOfExpr(expr: Expr[V])(implicit state: StateType): Boolean = {
        val isExprNotImpure = (expr.astID: @switch) match {
            // For function calls, purity will be checked later
            case StaticFunctionCall.ASTID | NonVirtualFunctionCall.ASTID | VirtualFunctionCall.ASTID ⇒
                true

            // Field/array loads are pure if the field is (effectively) final or the object/array is
            // local and non-escaping
            case GetStatic.ASTID ⇒
                implicit val code: Array[Stmt[V]] = state.code
                val ratedResult = rater.handleGetStatic(expr.asGetStatic)
                if (ratedResult.isDefined) atMost(ratedResult.get)
                else checkPurityOfFieldRef(expr.asGetStatic)
                true
            case GetField.ASTID ⇒
                checkPurityOfFieldRef(expr.asGetField)
                true
            case ArrayLoad.ASTID ⇒
                if (state.ubPurity.isDeterministic)
                    isLocal(expr.asArrayLoad.arrayRef, SideEffectFree)
                true

            // We don't handle unresolved Invokedynamic
            // - either OPAL removes it or we forget about it
            case InvokedynamicFunctionCall.ASTID ⇒
                atMost(ImpureByAnalysis)
                false

            // The following expressions do not further influence purity, potential exceptions are
            // handled explicitly
            case New.ASTID | NewArray.ASTID | InstanceOf.ASTID | Compare.ASTID | Param.ASTID |
                MethodTypeConst.ASTID | MethodHandleConst.ASTID | IntConst.ASTID | LongConst.ASTID |
                FloatConst.ASTID | DoubleConst.ASTID | StringConst.ASTID | ClassConst.ASTID |
                NullExpr.ASTID | BinaryExpr.ASTID | PrefixExpr.ASTID | PrimitiveTypecastExpr.ASTID |
                ArrayLength.ASTID | Var.ASTID ⇒
                true

        }

        isExprNotImpure && expr.forallSubExpressions(checkPurityOfExpr)
    }

    def checkPurityOfMethod(
        callee: DeclaredMethod,
        params: Seq[Expr[V]]
    )(implicit state: StateType): Boolean = {
        if (callee.hasSingleDefinedMethod && (callee.definedMethod eq state.method)) {
            true
        } else {
            val calleePurity = propertyStore(callee, Purity.key)
            checkMethodPurity(calleePurity, params)
        }
    }

    def getCall(stmt: Stmt[V])(implicit state: StateType): Call[V] = stmt.astID match {
        case StaticMethodCall.ASTID     ⇒ stmt.asStaticMethodCall
        case NonVirtualMethodCall.ASTID ⇒ stmt.asNonVirtualMethodCall
        case VirtualMethodCall.ASTID    ⇒ stmt.asVirtualMethodCall
        case Assignment.ASTID           ⇒ stmt.asAssignment.expr.asFunctionCall
        case ExprStmt.ASTID             ⇒ stmt.asExprStmt.expr.asFunctionCall
        case CaughtException.ASTID ⇒
            /*
       * There is no caught exception instruction in bytecode, so it might be the case, that
       * in the three-address code, there is a CaughtException stmt right before the call
       * with the same pc. Therefore, we have to get the call stmt after the current stmt.
       *
       * Example:
       * void foo() {
       *     try {
       *         ...
       *     } catch (Exception e) {
       *         e.printStackTrace();
       *     }
       * }
       *
       * In TAC:
       * 12: pc=52 caught java.lang.Exception ...
       * 13: pc=52 java.lang.Exception.printStackTrace()
       */
            getCall(state.code(state.pcToIndex(stmt.pc) + 1))
        case _ ⇒
            throw new IllegalStateException(s"unexpected stmt $stmt")
    }

    /**
     * Examines the influence of the purity property of a method on the examined method's purity.
     *
     * @note Adds dependendies when necessary.
     */
    def checkMethodPurity(
        ep:     EOptionP[DeclaredMethod, Purity],
        params: Seq[Expr[V]]                     = Seq.empty
    )(implicit state: StateType): Boolean

    /**
     * Examines whether a field read influences a method's purity.
     * Reading values from fields that are not (effectively) final may cause nondeterministic
     * behavior, so the method can only be side-effect free.
     */
    def checkPurityOfFieldRef(
        fieldRef: FieldRead[V]
    )(implicit state: StateType): Unit = {
        // Don't do dependee checks if already non-deterministic
        if (state.ubPurity.isDeterministic) {
            fieldRef.asFieldRead.resolveField match {
                case Some(field) if field.isStatic ⇒
                    checkFieldMutability(propertyStore(field, ReferenceImmutability.key), None) //FieldMutability.key), None)
                case Some(field) ⇒
                    checkFieldMutability(
                        propertyStore(field, ReferenceImmutability.key),
                        Some(fieldRef.asGetField.objRef) //FieldMutability.key), Some(fieldRef.asGetField.objRef)
                    )
                case _ ⇒ // Unknown field
                    if (fieldRef.isGetField) isLocal(fieldRef.asGetField.objRef, SideEffectFree)
                    else atMost(SideEffectFree)
            }
        }
    }

    /**
     * Examines the influence that a given field mutability has on the method's purity.
     */
    def checkFieldMutability(
        ep:     EOptionP[Field, ReferenceImmutability], // FieldMutability],
        objRef: Option[Expr[V]]
    )(implicit state: StateType): Unit = ep match {

        //case LBP(ImmutableReference(_))    ⇒
        //case LBP(LazyInitializedReference) ⇒
        case LBP(ImmutableReference(_) | LazyInitializedReference) ⇒
            println("====>>>> EP: "+ep) //_: FinalField) ⇒ // Final fields don't impede purity
        case FinalP(MutableReference) ⇒
            println("====>>>> EP: "+ep) //_: FinalEP[Field, ReferenceImmutability] ⇒ //FieldMutability] ⇒ // Mutable field
            if (objRef.isDefined) {
                if (state.ubPurity.isDeterministic)
                    isLocal(objRef.get, SideEffectFree)
            } else atMost(SideEffectFree)
        case _ ⇒
            reducePurityLB(SideEffectFree)
            if (state.ubPurity.isDeterministic)
                handleUnknownFieldMutability(ep, objRef)

    }

    /**
     * Handles what to do when the mutability of a field is not yet known.
     * Analyses must implement this method with the behavior they need, e.g. registering dependees.
     */
    def handleUnknownFieldMutability(
        ep:     EOptionP[Field, ReferenceImmutability], //FieldMutability],
        objRef: Option[Expr[V]]
    )(implicit state: StateType): Unit

    /**
     * Examines the effect of returning a value on the method's purity.
     * Returning a reference to a mutable object or array may cause nondeterministic behavior
     * as the object/array may be modified between invocations of the method, so the method can
     * only be side-effect free. E.g., a given parameter which references a mutable object is
     * returned (and not otherwise accessed).
     */
    def checkPurityOfReturn(returnValue: Expr[V])(implicit state: StateType): Unit = {
        if (returnValue.cTpe != ComputationalTypeReference)
            return ; // Only non-primitive return values influence purity.

        if (!state.ubPurity.isDeterministic)
            return ; // If the method can't be pure, the return value is not important.

        if (!returnValue.isVar) {
            // The expression could refer to further expressions in a non-flat representation. To
            // avoid special handling, we just fallback to SideEffectFreeWithoutAllocations here if
            // the return value is not local as the analysis is intended to be used on flat
            // representations anyway.
            isLocal(returnValue, SideEffectFree)
            return ;
        }

        val value = returnValue.asVar.value.asReferenceValue
        if (value.isNull.isYes)
            return ; // Null is immutable

        if (value.upperTypeBound.exists(_.isArrayType)) {
            // Arrays are always mutable
            isLocal(returnValue, SideEffectFree)
            return ;
        }

        if (value.isPrecise) { // Precise class known, use ClassImmutability
            val returnType = value.upperTypeBound.head

            val classImmutability =
                propertyStore(
                    returnType,
                    ClassImmutability_new.key // ClassImmutability.key
                ).asInstanceOf[EOptionP[ObjectType, ClassImmutability_new]] // ClassImmutability]]
            checkTypeMutability(classImmutability, returnValue)

        } else { // Precise class unknown, use TypeImmutability
            // IMPROVE Use ObjectType once we attach the respective information to ObjectTypes
            val returnTypes = value.upperTypeBound

            returnTypes.forall { returnType ⇒
                val typeImmutability =
                    propertyStore(
                        returnType,
                        TypeImmutability_new.key //.key
                    ).asInstanceOf[EOptionP[ObjectType, TypeImmutability_new]] //TypeImmutability]]
                checkTypeMutability(typeImmutability, returnValue)
            }
        }
    }

    /**
     * Examines the effect that the mutability of a returned value's type has on the method's
     * purity.
     */
    def checkTypeMutability(
        ep:          EOptionP[ObjectType, Property],
        returnValue: Expr[V]
    )(implicit state: StateType): Boolean = ep match {
        // Returning immutable object is pure
        case LBP(DeepImmutableType | DeepImmutableClass) ⇒
            true // ImmutableType | ImmutableObject) ⇒ true7
        case _: FinalEP[ObjectType, Property] ⇒
            atMost(Pure) // Can not be compile time pure if mutable object is returned
            if (state.ubPurity.isDeterministic)
                isLocal(returnValue, SideEffectFree)
            false // Return early if we are already side-effect free
        case _ ⇒
            reducePurityLB(SideEffectFree)
            if (state.ubPurity.isDeterministic)
                handleUnknownTypeMutability(ep, returnValue)
            true
    }

    /**
     * Handles what to do when the mutability of a type is not yet known.
     * Analyses must implement this method with the behavior they need, e.g. registering dependees.
     */
    def handleUnknownTypeMutability(
        ep:   EOptionP[ObjectType, Property],
        expr: Expr[V]
    )(implicit state: StateType): Unit

    /**
     * Examines the effect that the purity of all potential callees has on the purity of the method.
     */
    def checkPurityOfCallees(
        calleesEOptP: EOptionP[DeclaredMethod, Callees]
    )(
        implicit
        state: StateType
    ): Boolean = {
        handleCalleesUpdate(calleesEOptP)
        calleesEOptP match {
            case UBPS(p: Callees, isFinal) ⇒
                if (!isFinal) reducePurityLB(ImpureByAnalysis)

                val hasIncompleteCallSites =
                    p.incompleteCallSites.exists { pc ⇒
                        val index = state.pcToIndex(pc)
                        if (index < 0)
                            false // call will not be executed
                        else {
                            val call = getCall(state.code(state.pcToIndex(pc)))
                            !isDomainSpecificCall(call, call.receiverOption)
                        }
                    }

                if (hasIncompleteCallSites) {
                    atMost(ImpureByAnalysis)
                    return false;
                }

                val noDirectCalleeIsImpure = p.directCallSites().forall {
                    case (pc, callees) ⇒
                        val index = state.pcToIndex(pc)
                        if (index < 0)
                            true // call will not be executed
                        else {
                            val call = getCall(state.code(index))
                            isDomainSpecificCall(call, call.receiverOption) ||
                                callees.forall { callee ⇒
                                    checkPurityOfMethod(
                                        callee,
                                        call.receiverOption.orNull +: call.params
                                    )
                                }
                        }
                }

                if (!noDirectCalleeIsImpure)
                    return false;

                val noIndirectCalleeIsImpure = p.indirectCallSites().forall {
                    case (pc, callees) ⇒
                        val index = state.pcToIndex(pc)
                        if (index < 0)
                            true // call will not be executed
                        else {
                            val call = getCall(state.code(index))
                            isDomainSpecificCall(call, call.receiverOption) ||
                                callees.forall { callee ⇒
                                    checkPurityOfMethod(
                                        callee,
                                        p.indirectCallReceiver(pc, callee)
                                            .map(receiver ⇒ uVarForDefSites(receiver, state.pcToIndex))
                                            .orNull +:
                                            p.indirectCallParameters(pc, callee).map { paramO ⇒
                                                paramO.map(uVarForDefSites(_, state.pcToIndex)).orNull
                                            }
                                    )
                                }
                        }
                }

                noIndirectCalleeIsImpure

            case _ ⇒
                reducePurityLB(ImpureByAnalysis)
                true
        }
    }

    /**
     * Handles what to do when the set of potential callees changes.
     * Analyses must implement this method with the behavior they need, e.g. registering dependees.
     */
    def handleCalleesUpdate(
        callees: EOptionP[DeclaredMethod, Callees]
    )(implicit state: StateType): Unit

    /**
     * Handles what to do if the TACAI is not yet final.
     */
    def handleTACAI(ep: EOptionP[Method, TACAI])(implicit state: StateType): Unit

    /**
     * Retrieves and commits the methods purity as calculated for its declaring class type for the
     * current DefinedMethod that represents the non-overwritten method in a subtype.
     */
    def baseMethodPurity(dm: DefinedMethod): ProperPropertyComputationResult = {

        def c(eps: SomeEOptionP): ProperPropertyComputationResult = eps match {
            case FinalP(p) ⇒ Result(dm, p)
            case ep @ InterimLUBP(lb, ub) ⇒
                InterimResult.create(dm, lb, ub, Seq(ep), c)
            case epk ⇒
                InterimResult(dm, ImpureByAnalysis, CompileTimePure, Seq(epk), c)
        }

        c(propertyStore(declaredMethods(dm.definedMethod), Purity.key))
    }

    /**
     * Determines the purity of the given method.
     *
     * @param definedMethod A defined method with a body.
     */
    def determinePurity(definedMethod: DefinedMethod): ProperPropertyComputationResult

    /** Called when the analysis is scheduled lazily. */
    def doDeterminePurity(e: Entity): ProperPropertyComputationResult = {
        e match {
            case dm: DefinedMethod if dm.definedMethod.body.isDefined ⇒
                determinePurity(dm)
            case dm: DeclaredMethod ⇒ Result(dm, ImpureByLackOfInformation)
            case _ ⇒
                throw new IllegalArgumentException(s"$e is not a declared method")
        }
    }

    /**
     * Returns the TACode for a method if available, registering dependencies as necessary.
     */
    def getTACAI(
        method: Method
    )(implicit state: StateType): Option[TACode[TACMethodParameter, V]] = {
        propertyStore(method, TACAI.key) match {
            case finalEP: FinalEP[Method, TACAI] ⇒
                handleTACAI(finalEP)
                finalEP.ub.tac
            case eps @ InterimUBP(ub: TACAI) ⇒
                reducePurityLB(ImpureByAnalysis)
                handleTACAI(eps)
                ub.tac
            case epk ⇒
                reducePurityLB(ImpureByAnalysis)
                handleTACAI(epk)
                None
        }
    }

    def resolveDomainSpecificRater(fqn: String): DomainSpecificRater = {
        import scala.reflect.runtime.universe.runtimeMirror
        val mirror = runtimeMirror(getClass.getClassLoader)
        try {
            val module = mirror.staticModule(fqn)
            mirror.reflectModule(module).instance.asInstanceOf[DomainSpecificRater]
        } catch {
            case ex @ (_: ScalaReflectionException | _: ClassCastException) ⇒
                OPALLogger.error(
                    "analysis configuration",
                    "resolve of domain specific rater failed, change "+
                        s"org.opalj.fpcf.${this.getClass.getName}.domainSpecificRater in "+
                        "ai/reference.conf to an existing DomainSpecificRater implementation",
                    ex
                )(GlobalLogContext)
                new BaseDomainSpecificRater // Provide a safe default if resolution failed
        }
    }

}