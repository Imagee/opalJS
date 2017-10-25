/* BSD 2-Clause License:
 * Copyright (c) 2009 - 2017
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
package org.opalj.tac

import org.opalj.br.analyses.Project
import java.net.URL

import org.opalj.br.analyses.DefaultOneStepAnalysis
import org.opalj.br.analyses.BasicReport
import org.opalj.br.analyses.AllocationSitesKey
import org.opalj.collection.immutable.IntArraySet
import org.opalj.util.PerformanceEvaluation.time
import org.opalj.log.OPALLogger.error
import org.opalj.log.OPALLogger.info

object FieldUsageAnalysis extends DefaultOneStepAnalysis {

    override def title: String = ""

    override def description: String = {
        ""
    }

    override def doAnalyze(
        project:       Project[URL],
        parameters:    Seq[String],
        isInterrupted: () ⇒ Boolean
    ): BasicReport = {
        implicit val logContext = project.logContext

        var putFields = 0
        var putFieldsOfAllocation = 0
        var getFields = 0
        var arrayStores = 0
        var arrayStoresOfAllocation = 0
        var arrayLoads = 0
        var allocations = 0
        var nonDeadAllocations = 0
        val tacai = time {
            val tacai = project.get(DefaultTACAIKey)
            // parallelization is more efficient using parForeachMethodWithBody
            val errors = project.parForeachMethodWithBody() { mi ⇒ tacai(mi.method) }
            errors.foreach { e ⇒ error("progress", "generating 3-address code failed", e) }
            tacai
        } { t ⇒ info("progress", s"generating 3-address code took ${t.toSeconds}") }
        val ass = time {
            project.get(AllocationSitesKey)
        } { t ⇒ info("progress", s"allocationSites took ${t.toSeconds}") }
        for {
            m ← project.allMethodsWithBody
            (pc, as) ← ass(m)
            code = tacai(m).stmts
            lineNumbers = tacai(m).lineNumberTable
            index = code indexWhere { stmt ⇒ stmt.pc == pc }
            if index != -1
        } {
            allocations += 1
            code(index) match {
                case Assignment(`pc`, DVar(_, uses), New(`pc`, _) | NewArray(`pc`, _, _)) ⇒
                    nonDeadAllocations += 1
                    for (use ← uses) {
                        code(use) match {
                            case PutField(_, _, name, _, objRef, value) ⇒
                                if (value.isVar && value.asVar.definedBy.contains(index)) {
                                    putFields += 1
                                    if (objRef.isVar && objRef.asVar.definedBy != IntArraySet(-1)) {
                                        val defSitesOfObjRef = objRef.asVar.definedBy
                                        if (defSitesOfObjRef.exists { defSite ⇒
                                            if (defSite > 0) {
                                                code(defSite) match {
                                                    case Assignment(_, _, New(_, _)) ⇒ true
                                                    case _                           ⇒ false
                                                }
                                            } else false
                                        }) {
                                            putFieldsOfAllocation += 1
                                            for (stmt ← code) {
                                                stmt match {
                                                    case Assignment(_, DVar(_, _), GetField(_, _, `name`, _, objRef2)) if objRef2.isVar ⇒
                                                        if (objRef2.asVar.definedBy.exists(defSitesOfObjRef.contains)) {
                                                            getFields += 1
                                                            //println(s"${m.toJava} ${if (lineNumbers.nonEmpty) lineNumbers.get.lookupLineNumber(as.pc)} ${as.allocatedType}")
                                                        }

                                                    case _ ⇒
                                                }
                                            }
                                        }
                                    }
                                }
                            case ArrayStore(_, arrayRef, _, value) ⇒
                                if (value.isVar && value.asVar.definedBy.contains(index)) {
                                    arrayStores += 1

                                    if (arrayRef.isVar) {
                                        val defSitesOfArray = arrayRef.asVar.definedBy
                                        if (defSitesOfArray.exists { defSite ⇒
                                            if (defSite > 0) {
                                                code(defSite) match {
                                                    case Assignment(_, _, NewArray(_, _, _)) ⇒ true
                                                    case _                                   ⇒ false
                                                }
                                            } else false

                                        }) {
                                            arrayStoresOfAllocation += 1
                                            for (stmt ← code) {
                                                stmt match {
                                                    case Assignment(_, DVar(_, _), ArrayLoad(_, _, arrayRef2)) if arrayRef2.isVar ⇒
                                                        if (arrayRef2.asVar.definedBy.exists(defSitesOfArray.contains)) {
                                                            arrayLoads += 1
                                                            //println(s"${m.toJava} ${if (lineNumbers.nonEmpty) lineNumbers.get.lookupLineNumber(as.pc)} ${as.allocatedType}")
                                                        }
                                                    case _ ⇒
                                                }
                                            }
                                        }
                                    }
                                }
                            case _ ⇒
                        }
                    }
                case _ ⇒
            }
        }

        val message =
            s"""
               |# of allocations $allocations
               |# of non dead allocations: $nonDeadAllocations
               |# of putfields: $putFields
               |# of putfields on new fields $putFieldsOfAllocation
               |# of getfields after puts: $getFields
               |# of arraystores: $arrayStores
               |# of arraystores on new fields $arrayStoresOfAllocation
               |# of arrayloads after store: $arrayLoads"""

        BasicReport(message.stripMargin('|'))
    }

}
