/* BSD 2-Clause License:
 * Copyright (c) 2009 - 2014
 * Software Technology Group
 * Department of Computer Science
 * Technische Universität Darmstadt
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  - Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *  - Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package org.opalj
package bugpicker
package core
package analysis

import scala.language.existentials
import java.net.URL
import scala.xml.Node
import scala.xml.UnprefixedAttribute
import scala.xml.Unparsed
import scala.Console.BLUE
import scala.Console.RED
import scala.Console.BOLD
import scala.Console.GREEN
import scala.Console.RESET
import scala.collection.SortedMap
import org.opalj.util.PerformanceEvaluation.{ time, ns2sec }
import org.opalj.br.analyses.{ Analysis, AnalysisExecutor, BasicReport, Project, SomeProject }
import org.opalj.br.analyses.ProgressManagement
import org.opalj.br.{ ClassFile, Method }
import org.opalj.br.MethodWithBody
import org.opalj.ai.common.XHTML
import org.opalj.ai.BaseAI
import org.opalj.ai.Domain
import org.opalj.br.Code
import org.opalj.ai.collectPCWithOperands
import org.opalj.ai.BoundedInterruptableAI
import org.opalj.ai.domain
import org.opalj.ai.domain.l0.ZeroDomain
import org.opalj.br.AnalysisFailedException
import org.opalj.br.ObjectType
import org.opalj.br.ComputationalTypeInt
import org.opalj.br.ComputationalTypeLong
import org.opalj.br.instructions.ATHROW
import org.opalj.br.instructions.Instruction
import org.opalj.br.instructions.ConditionalBranchInstruction
import org.opalj.br.instructions.SimpleConditionalBranchInstruction
import org.opalj.br.instructions.CompoundConditionalBranchInstruction
import org.opalj.br.instructions.ArithmeticInstruction
import org.opalj.br.instructions.BinaryArithmeticInstruction
import org.opalj.br.instructions.UnaryArithmeticInstruction
import org.opalj.br.instructions.LNEG
import org.opalj.br.instructions.INEG
import org.opalj.br.instructions.IINC
import org.opalj.br.instructions.RET
import org.opalj.br.instructions.ShiftInstruction
import org.opalj.br.instructions.INSTANCEOF
import org.opalj.br.instructions.ISTORE
import org.opalj.br.instructions.IStoreInstruction
import org.opalj.br.instructions.MethodCompletionInstruction
import org.opalj.ai.AIResult
import org.opalj.ai.InterpretationFailedException
import org.opalj.ai.domain.ConcreteIntegerValues
import org.opalj.ai.domain.ConcreteLongValues
import org.opalj.ai.domain.RecordCFG
import org.opalj.ai.domain.l1.RecordAllThrownExceptions
import org.opalj.ai.domain.l1.ReferenceValues
import org.opalj.br.instructions.FieldReadAccess
import org.opalj.br.instructions.MethodInvocationInstruction
import org.opalj.br.instructions.GOTO
import org.opalj.br.instructions.ATHROW
import org.opalj.br.instructions.GOTO_W
import org.opalj.br.instructions.ReturnInstruction
import org.opalj.br.PC
import org.opalj.ai.analyses.MethodReturnValuesKey
import org.opalj.ai.analyses.cg.Callees
import org.opalj.br.instructions.GotoInstruction
import org.opalj.br.instructions.ReturnInstructions
import org.opalj.ai.IsAReferenceValue
import org.opalj.ai.domain.ThrowNoPotentialExceptionsConfiguration
import org.opalj.br.instructions.NEW
import org.opalj.br.cfg.ControlFlowGraph

/**
 * Implementation of an analysis to find code that is "dead".
 *
 * @author Michael Eichberg
 */
object DeadPathAnalysis {

    final val AssertionError = ObjectType("java/lang/AssertionError")

    def analyze(
        theProject: SomeProject, classFile: ClassFile, method: Method,
        result: AIResult { val domain: Domain with Callees with RecordCFG }): Seq[StandardIssue] = {

        import result.domain
        import result.operandsArray
        import result.localsArray
        val evaluatedInstructions = result.evaluatedInstructions
        implicit val body = result.code
        val instructions = body.instructions
        import result.joinInstructions
        import result.domain.regularSuccessorsOf
        import result.domain.exceptionHandlerSuccessorsOf
        import result.domain.hasMultipleRegularPredecessors
        import result.domain.isRegularPredecessorOf

        /*
         * Helper function to identify methods that will always throw an exception if
         * executed.
         */
        def isAlwaysExceptionThrowingMethodCall(pc: PC): Boolean = {
            body.instructions(pc) match {
                case MethodInvocationInstruction(receiver: ObjectType, name, descriptor) ⇒
                    val callees = domain.callees(receiver, name, descriptor)

                    if (callees.size == 1) {
                        // we are only handling the special idiom where a
                        // (static) private method is used to create and throw
                        // a special exception object
                        // ...
                        // catch(Exception e) {
                        //     /*return|athrow*/ handleException(e);
                        // }
                        val callee = callees.head
                        callee.body match {
                            case Some(code) ⇒
                                !code.exists((pc, i) ⇒ ReturnInstruction.isReturnInstruction(i))
                            case _ ⇒
                                false
                        }
                    } else
                        false

                case _ ⇒ false
            }
        }

        /*
         *  A return/goto/athrow instruction is considered useless if the preceding
         * instruction is a method call with a single target that _context-independently_
         * always just throws (an) exception(s). This is common pattern found in the JDK.
         */
        // We want to suppress false warning as found in JDK 1.8.0_25
        //     javax.xml.bind.util.JAXBResult
        // when the method
        //     assertFailed
        // is called and afterwards a jump is done to a non-dead instruction.
        def requiredButIrrelevantSuccessor(currentPC: PC, nextPC: PC): Boolean = {

            val nextInstruction = body.instructions(nextPC)
            (
                nextInstruction.isInstanceOf[ReturnInstruction] ||
                nextInstruction == ATHROW ||
                (
                    nextInstruction.isInstanceOf[GotoInstruction] &&
                    evaluatedInstructions.contains(body.nextNonGotoInstruction(nextPC))
                )
            ) &&
                    isAlwaysExceptionThrowingMethodCall(currentPC)
        }

        val deadCodeIssues: Seq[StandardIssue] = {

            var issues = List.empty[StandardIssue]
            for {
                pc ← body.programCounters

                // test if the instruction was evaluated
                if evaluatedInstructions.contains(pc)
                instruction = instructions(pc)

                if {
                    val opcode = instruction.opcode
                    // we don't know the next one, but the next one is not dead... (ever)
                    // if we reach this one
                    opcode != RET.opcode &&
                        // We don't have an analysis in place that enables us to determine
                        // the real successors
                        opcode != ATHROW.opcode
                }

                // test if one or all of the successors are dead
                (nextPC: PC) ← instruction.nextInstructions(pc, body, regularSuccessorsOnly = true)
                if !regularSuccessorsOf(pc).contains(nextPC)

                allOperands = operandsArray(pc)
                // Possibility: Store the state of the subroutines to facilitate the
                // identification of dead paths in subroutines...
                if allOperands ne null // null if we are in a subroutine (java < 1.5)
            } {
                // identify those dead edges that are pure technical artifacts
                val isLikelyFalsePositive = requiredButIrrelevantSuccessor(pc, nextPC)

                def isRelatedToCompilationOfFinally: Boolean =
                    classFile.majorVersion >= 50 /* older compilers are using jsr/ret */ &&
                        body.exceptionHandlers.exists(_.catchType.isEmpty) /* there is no finally */ && {
                            val pcIsOnExceptionalControlFlowPaths =
                                body.exceptionHandlers.filter { eh ⇒
                                    eh.catchType.isEmpty &&
                                        isRegularPredecessorOf(eh.handlerPC, pc)
                                }

                            // find matching instruction
                            val cPCs =
                                body.programCounters.filter { cPC ⇒
                                    cPC != pc && instructions(pc).isIsomorphic(pc, cPC)
                                }.filterNot { cPC ⇒
                                    def cPCIsOnExceptionalControlFlowPaths =
                                        body.exceptionHandlers.filter { eh ⇒
                                            eh.catchType.isEmpty && isRegularPredecessorOf(eh.handlerPC, cPC)
                                        }

                                    (
                                        // check the the instructions are not in a pre-/
                                        // successor relation
                                        isRegularPredecessorOf(pc, cPC) ||
                                        isRegularPredecessorOf(cPC, pc)
                                    ) || (
                                            // they are not successors of the same exception handlers
                                            (pcIsOnExceptionalControlFlowPaths.nonEmpty ||
                                                cPCIsOnExceptionalControlFlowPaths.nonEmpty) &&
                                                (pcIsOnExceptionalControlFlowPaths intersect cPCIsOnExceptionalControlFlowPaths).nonEmpty
                                        )
                                }.toList
                            // check if at least one instruction is dominated by a "finally"
                            // exception handler
                            // ???

                            println(method.toJava(classFile) + s" - correspondence - $pc(ehs=$pcIsOnExceptionalControlFlowPaths) => $cPCs; pc>cPCs===${cPCs.exists(cPC ⇒ isRegularPredecessorOf(pc, cPC))}; cPC>pc===${cPCs.exists(cPC ⇒ isRegularPredecessorOf(cPC, pc))}")

                            // check if - taken together – all successor are visited
                            cPCs.exists { cPC ⇒
                                regularSuccessorsOf(cPC).contains(cPC + (nextPC - pc))
                            }
                        }

                lazy val isProvenAssertion = {
                    instructions(nextPC) match {
                        case NEW(AssertionError) ⇒
                            // TODO Test if a "getstatic thisType $assertionsDisabled" instruction
                            // dominates this instruction
                            true
                        case _ ⇒
                            false
                    }
                }

                // identify those dead edges that are the result of common programming
                // idioms; e.g.,
                // switch(v){
                // ...
                // default:
                //   1: throw new XYZError(...);
                //   2: throw new IllegalStateException(...);
                //   3: assert(false); // TODO !!!
                //   4: stateError();
                //         AN ALWAYS (PRIVATE AND/OR STATIC) EXCEPTION
                //         THROWING METHOD
                //         HERE, THE DEFAULT CASE MAY EVEN FALL THROUGH!
                // }
                //
                lazy val isDefaultBranchOfSwitch =
                    instruction.isInstanceOf[CompoundConditionalBranchInstruction] &&
                        nextPC == pc + instruction.asInstanceOf[CompoundConditionalBranchInstruction].defaultOffset

                lazy val isNonExistingDefaultBranchOfSwitch = isDefaultBranchOfSwitch &&
                    hasMultipleRegularPredecessors(pc + instruction.asInstanceOf[CompoundConditionalBranchInstruction].defaultOffset)

                lazy val isLikelyIntendedDeadDefaultBranch = isDefaultBranchOfSwitch &&
                    // this is the default branch of a switch instruction that is dead
                    body.alwaysResultsInException(
                        nextPC,
                        joinInstructions,
                        (invocationPC) ⇒ {
                            isAlwaysExceptionThrowingMethodCall(invocationPC)
                        },
                        (athrowPC) ⇒ {
                            // Let's do a basic analysis to determine the type of
                            // the thrown exception.
                            // What we do next is basic a local data-flow analysis that
                            // starts with the first instruction of the default branch
                            // of the switch instruction and which uses
                            // the most basic domain available.
                            val codeLength = body.instructions.length
                            class ZDomain extends { // we need the "early initializer
                                val project: SomeProject = theProject
                                val code: Code = body
                            } with ZeroDomain with ThrowNoPotentialExceptionsConfiguration
                            val zDomain = new ZDomain
                            val zOperandsArray = new zDomain.OperandsArray(codeLength)
                            val zInitialOperands =
                                operandsArray(pc).tail.map(_.adapt(zDomain, Int.MinValue))
                            zOperandsArray(nextPC) = zInitialOperands
                            val zLocalsArray = new zDomain.LocalsArray(codeLength)
                            zLocalsArray(nextPC) =
                                localsArray(pc) map { l ⇒
                                    if (l ne null)
                                        l.adapt(zDomain, Int.MinValue)
                                    else
                                        null
                                }
                            BaseAI.continueInterpretation(
                                result.strictfp,
                                result.code,
                                result.joinInstructions,
                                zDomain)(
                                    /*initialWorkList =*/ List(nextPC),
                                    /*alreadyEvaluated =*/ List(),
                                    zOperandsArray,
                                    zLocalsArray,
                                    List.empty
                                )
                            val exceptionValue = zOperandsArray(athrowPC).head
                            val throwsError =
                                (
                                    zDomain.asReferenceValue(exceptionValue).
                                    isValueSubtypeOf(ObjectType.Error).
                                    isYesOrUnknown
                                ) ||
                                    zDomain.asReferenceValue(exceptionValue).
                                    isValueSubtypeOf(ObjectType("java/lang/IllegalStateException")).
                                    isYesOrUnknown

                            throwsError
                        }
                    )

                val poppedOperandsCount =
                    instruction.numberOfPoppedOperands { index ⇒
                        allOperands(index).computationalType.computationalTypeCategory
                    }
                val operands = allOperands.take(poppedOperandsCount)

                val line = body.lineNumber(nextPC).map(l ⇒ s" (line=$l)").getOrElse("")

                val hints =
                    body.collectWithIndex {
                        case (pc, instr @ FieldReadAccess(_ /*declaringClassType*/ , _ /* name*/ , fieldType)) if {
                            val nextPC = instr.indexOfNextInstruction(pc)
                            val operands = operandsArray(nextPC)
                            operands != null &&
                                operands.head.isMorePreciseThan(result.domain.TypedValue(pc, fieldType))
                        } ⇒
                            (pc, s"the value of $instr is ${operandsArray(instr.indexOfNextInstruction(pc)).head}")

                        case (pc, instr @ MethodInvocationInstruction(_ /*declaringClassType*/ , _ /* name*/ , descriptor)) if !descriptor.returnType.isVoidType && {
                            val nextPC = instr.indexOfNextInstruction(pc, body)
                            val operands = operandsArray(nextPC)
                            operands != null &&
                                operands.head.isMorePreciseThan(result.domain.TypedValue(pc, descriptor.returnType))
                        } ⇒
                            (pc, s"the return value of $instr is ${operandsArray(instr.indexOfNextInstruction(pc, body)).head}")
                    }

                val isJustDeadPath = evaluatedInstructions.contains(nextPC)
                val isTechnicalArtifact =
                    isLikelyFalsePositive ||
                        isNonExistingDefaultBranchOfSwitch ||
                        isRelatedToCompilationOfFinally
                issues ::= StandardIssue(
                    theProject, classFile, Some(method), Some(pc),
                    Some(operands), Some(result.localsArray(pc)),
                    if (isJustDeadPath)
                        s"the path to instruction pc=$nextPC$line is never taken"
                    else
                        s"the successor instruction pc=$nextPC$line is dead",
                    Some(
                        "The evaluation of the instruction never directly leads to the evaluation of the given subsequent instruction."+(
                            if (isTechnicalArtifact)
                                "\n(This seems to be a technical artifact that cannot be avoided; i.e., there is probably nothing to fix!)"
                            else if (isLikelyIntendedDeadDefaultBranch)
                                "\n(This seems to be a deliberately dead default branch of a switch instruction; i.e., there is probably nothing to fix!)"
                            else
                                ""
                        )),
                    Set(IssueCategory.Flawed, IssueCategory.Comprehensibility),
                    Set(IssueKind.DeadPath),
                    hints,
                    if (isTechnicalArtifact)
                        Relevance.TechnicalArtifact
                    else if (isProvenAssertion)
                        Relevance.ProvenAssertion
                    else if (isLikelyIntendedDeadDefaultBranch)
                        Relevance.CommonIdiom
                    else if (isJustDeadPath)
                        Relevance.UselessDefensiveProgramming
                    else
                        Relevance.OfUtmostRelevance
                )
            }
            issues
        }

        deadCodeIssues.sortBy { _.line }
    }

}
