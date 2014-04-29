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
package de.tud.cs.st
package bat
package resolved
package dependency

import analyses.SomeProject
import analyses.ProjectInformationKey

/**
 * ==Thread Safety==
 * This class is thread-safe. However, it does not make sense to call the method
 * toStore unless the dependency extractor that uses this processor has completed.
 */
class DependencyCollectingDependencyProcessor extends DependencyProcessor {

    import scala.collection.concurrent.{ TrieMap ⇒ CMap }
    import scala.collection.mutable.{ Set }

    private[this] val deps =
        CMap.empty[VirtualSourceElement, CMap[VirtualSourceElement, Set[DependencyType]]]

    private[this] val depsOnArrayTypes =
        CMap.empty[VirtualSourceElement, CMap[ArrayType, Set[DependencyType]]]

    private[this] val depsOnBaseTypes =
        CMap.empty[VirtualSourceElement, CMap[BaseType, Set[DependencyType]]]

    def toStore: DependencyStore = {

        import scala.collection.mutable.{ Map ⇒ MutableMap }
        import scala.collection.immutable.{ Set ⇒ ImmutableSet }

        val dependencyTypes: MutableMap[Set[DependencyType], ImmutableSet[DependencyType]] = MutableMap.empty

        val theDeps =
            (deps map { dep ⇒
                val (source, targets) = dep
                val newTargets = (targets map { targetKind ⇒
                    val (target, dTypes) = targetKind
                    (target, dependencyTypes.getOrElseUpdate(dTypes, dTypes.toSet))
                }).toMap
                (source, newTargets)
            }).toMap

        val theDepsOnArrayTypes =
            (depsOnArrayTypes map { dep ⇒
                val (source, targets) = dep
                val newTargets = (targets map { targetKind ⇒
                    val (target, dTypes) = targetKind
                    (target, dependencyTypes.getOrElseUpdate(dTypes, dTypes.toSet))
                }).toMap
                (source, newTargets)
            }).toMap

        val theDepsOnBaseTypes =
            (depsOnBaseTypes map { dep ⇒
                val (source, targets) = dep
                val newTargets = (targets map { targetKind ⇒
                    val (target, dTypes) = targetKind
                    (target, dependencyTypes.getOrElseUpdate(dTypes, dTypes.toSet))
                }).toMap
                (source, newTargets)
            }).toMap

        new DependencyStore(theDeps, theDepsOnArrayTypes, theDepsOnBaseTypes)
    }

    def processDependency(
        source: VirtualSourceElement,
        target: VirtualSourceElement,
        dType: DependencyType): Unit = {

        val targetElements = deps.getOrElseUpdate(source, CMap.empty[VirtualSourceElement, Set[DependencyType]])
        val dependencyTypes = targetElements.getOrElseUpdate(target, Set.empty[DependencyType])
        dependencyTypes.synchronized {
            dependencyTypes += dType
        }
    }

    def processDependency(
        source: VirtualSourceElement,
        arrayType: ArrayType,
        dType: DependencyType): Unit = {

        val arrayTypes = depsOnArrayTypes.getOrElseUpdate(source, CMap.empty[ArrayType, Set[DependencyType]])
        val dependencyTypes = arrayTypes.getOrElseUpdate(arrayType, Set.empty[DependencyType])
        dependencyTypes.synchronized {
            dependencyTypes += dType
        }
    }

    def processDependency(
        source: VirtualSourceElement,
        baseType: BaseType,
        dType: DependencyType): Unit = {

        val baseTypes = depsOnBaseTypes.getOrElseUpdate(source, CMap.empty[BaseType, Set[DependencyType]])
        val dependencyTypes = baseTypes.getOrElseUpdate(baseType, Set.empty[DependencyType])
        dependencyTypes.synchronized {
            dependencyTypes += dType
        }
    }
}



