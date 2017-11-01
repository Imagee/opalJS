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
package org.opalj
package collection
package immutable

import org.junit.runner.RunWith

import org.scalatest.junit.JUnitRunner
import org.scalacheck.Properties
import org.scalacheck.Prop.forAll
import org.scalacheck.Prop.classify
import org.scalacheck.Prop.BooleanOperators
import org.scalacheck.Gen
import org.scalacheck.Arbitrary

/**
 * Tests `IntTrieSet` by creating a standard Scala Set and comparing
 * the results of the respective functions.
 *
 * @author Michael Eichberg
 */
@RunWith(classOf[JUnitRunner])
object IntTrieSetProperties extends Properties("IntTrieSet") {

    val r = new java.util.Random()

    val smallListsGen = for { m ← Gen.listOfN(8, Arbitrary.arbitrary[Int]) } yield (m)

    implicit val arbIntArraySet: Arbitrary[IntArraySet] = Arbitrary {
        Gen.sized { l ⇒
            (0 until l).foldLeft(IntArraySet.empty) { (c, n) ⇒ c + r.nextInt(100000) - 50000 }
        }
    }

    // NOT TO BE USED BY TESTS THAT TEST THE CORRECT CONSTRUCTION!
    implicit val arbIntTrieSet: Arbitrary[IntTrieSet] = Arbitrary {
        Gen.sized { l ⇒
            (0 until l).foldLeft(IntTrieSet.empty) { (c, n) ⇒ c + r.nextInt(100000) - 50000 }
        }
    }

    // NOT TO BE USED BY TESTS THAT TEST THE CORRECT CONSTRUCTION!
    implicit val arbListOfIntArraySet: Arbitrary[List[IntArraySet]] = Arbitrary {
        Gen.sized { s ⇒ Gen.listOfN(s, Arbitrary.arbitrary[IntArraySet]) }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                             P R O P E R T I E S

    property("create singleton IntTrieSet") = forAll { v: Int ⇒
        val factoryITS = IntTrieSet1(v)
        val directITS = new IntTrieSet1(v)
        val viaEmptyITS = EmptyIntTrieSet + v
        factoryITS.size == 1 &&
            factoryITS.isSingletonSet &&
            !factoryITS.isEmpty &&
            !factoryITS.hasMultipleElements &&
            factoryITS.head == v &&
            factoryITS == directITS &&
            directITS == viaEmptyITS &&
            directITS.hashCode == viaEmptyITS.hashCode
    }

    property("create IntTrieSet from Set (i.e., no duplicates)") = forAll { s: IntArraySet ⇒
        val its = s.foldLeft(EmptyIntTrieSet: IntTrieSet)(_ + _)
        (its.size == s.size) :| "matching size" &&
            (its.isEmpty == s.isEmpty) &&
            (its.nonEmpty == s.nonEmpty) &&
            (its.hasMultipleElements == (s.size > 1)) &&
            (its.isSingletonSet == (s.size == 1)) &&
            (its.iterator.toList.sorted == s.iterator.toList.sorted) :| "same content"
    }

    property("create IntTrieSet from List (i.e., with duplicates)") = forAll { l: List[Int] ⇒
        val its = l.foldLeft(EmptyIntTrieSet: IntTrieSet)(_ + _)
        val lWithoutDuplicates = l.toSet.toList
        (its.size == lWithoutDuplicates.size) :| "matching size" &&
            its.iterator.toList.sorted == lWithoutDuplicates.sorted
    }

    property("head") = forAll { s: IntArraySet ⇒
        s.nonEmpty ==> {
            val its = s.foldLeft(EmptyIntTrieSet: IntTrieSet)(_ + _)
            s.contains(its.head)
        }
    }

    property("mkString") = forAll { (s: Set[Int], pre: String, in: String, post: String) ⇒
        val its = s.foldLeft(EmptyIntTrieSet: IntTrieSet)(_ + _)
        val itsString = its.mkString(pre, in, post)
        val sString = its.iterator.mkString(pre, in, post)
        val lString = s.mkString(pre, in, post)
        ((itsString.length == sString.length) :| "length of generated string (its vs s)") &&
            ((itsString.length == lString.length) :| "length of generated string (its vs l)") &&
            itsString.startsWith(pre) && itsString.endsWith(post)
    }

    property("contains") = forAll { (s1: IntArraySet, s2: IntArraySet) ⇒
        val its = s1.foldLeft(EmptyIntTrieSet: IntTrieSet)(_ + _)
        s1.forall(its.contains) :| "contains expected value" &&
            s2.forall(v ⇒ s1.contains(v) == its.contains(v))
    }

    property("foreach") = forAll { s: IntArraySet ⇒
        val its = EmptyIntTrieSet ++ s.iterator
        var newS = IntArraySet.empty
        its.foreach { newS += _ } // use foreach to compute a new set
        s == newS
    }

    property("map") = forAll { s: IntArraySet ⇒
        val its = EmptyIntTrieSet ++ s.iterator
        val mappedIts = its.map(_ * 2)
        val mappedS = s.map(_ * 2)
        classify(mappedIts.size > 3, "using trie") {
            mappedS.size == mappedIts.size &&
                (EmptyIntTrieSet ++ mappedS.iterator) == mappedIts
        }
    }

    property("map (identity)") = forAll { s: IntArraySet ⇒
        val its = EmptyIntTrieSet ++ s.iterator
        val mappedIts = its.map(_ * 1)
        val mappedS = s.map(_ * 1)
        classify(mappedIts.size > 3, "using trie") {
            (mappedIts eq its) &&
                mappedS.size == mappedIts.size &&
                (EmptyIntTrieSet ++ mappedS.iterator) == mappedIts
        }
    }

    property("exists") = forAll { s: IntArraySet ⇒
        val its = EmptyIntTrieSet ++ s.iterator
        s.forall(v ⇒ its.exists(_ == v)) &&
            s.forall(v ⇒ its.exists(_ != v) == s.exists(_ != v))
    }

    property("forall") = forAll { s: IntArraySet ⇒
        val its = EmptyIntTrieSet ++ s.iterator
        its.forall(s.contains) &&
            its.forall(v ⇒ s.contains(-v)) == s.forall(v ⇒ s.contains(-v))
    }

    property("foldLeft") = forAll { s: IntArraySet ⇒
        val its = EmptyIntTrieSet ++ s.iterator
        its.foldLeft(0)(_ + _) == s.foldLeft(0)(_ + _)
    }

    property("toChain") = forAll { s: IntArraySet ⇒
        val its = EmptyIntTrieSet ++ s.iterator
        its.toChain.toIterator.toList.sorted == s.iterator.toList.sorted
    }

    property("getAndRemove") = forAll { s: IntArraySet ⇒
        var its = EmptyIntTrieSet ++ s.iterator
        var removed = Chain.empty[Int]
        while (its.nonEmpty) {
            val (v, newIts) = its.getAndRemove
            removed :&:= v
            its = newIts
        }
        (removed.toIterator.toSet.size == s.size) :| "no value is returned more than once" &&
            (removed.size == s.size) :| "all values are returned"
    }

    property("flatMap") = forAll { listOfSets: List[IntArraySet] ⇒
        listOfSets.nonEmpty ==> {
            val arrayOfSets = listOfSets.toArray
            val arrayOfITSets = arrayOfSets.map(s ⇒ IntTrieSet.empty ++ s.iterator)
            val l = arrayOfSets.length

            val flatMappedITSet = arrayOfITSets(0).flatMap(v ⇒ arrayOfITSets(v % l))
            val flatMappedSSet = arrayOfSets(0).flatMap(v ⇒ arrayOfSets(v % l))

            classify(flatMappedSSet.size > 50, "set with more than 50 elements") {
                classify(flatMappedSSet.size < listOfSets.map(_.size).sum, "flat map is not the join of all sets") {
                    flatMappedSSet.forall(flatMappedITSet.contains) &&
                        flatMappedSSet.size == flatMappedITSet.size
                }
            }
        }
    }

    property("not equals") = forAll { s: IntArraySet ⇒
        val its = EmptyIntTrieSet ++ s.iterator
        its != (new Object)
    }

    property("toString") = forAll { s: IntArraySet ⇒
        val its = s.foldLeft(IntTrieSet.empty)(_ + _)
        val itsToString = its.toString
        itsToString.startsWith("IntTrieSet(") && itsToString.endsWith(")")
        // IMPROVE add content based test
    }

    property("subsetOf") = forAll { (s1: IntArraySet, s2: IntArraySet) ⇒
        val its1 = s1.foldLeft(IntTrieSet.empty)(_ + _)
        val its2 = s2.foldLeft(IntTrieSet.empty)(_ + _)
        val mergedIts = its1 ++ its2
        its1.subsetOf(mergedIts) && its2.subsetOf(mergedIts)
    }

    /*
    property("withFilter -> iterator (does not force evaluation)") = forAll { s: Set[Int] ⇒
        val fl1 = IntArraySetBuilder(s).result
        var newS = Set.empty[Int]
        s.withFilter(_ >= 0).withFilter(_ <= 1000).foreach(newS += _)
        fl1.withFilter(_ >= 0).withFilter(_ <= 1000).iterator.toList == newS.toList.sorted
    }

    property("withFilter -> foreach (does not force evaluation)") = forAll { s: Set[Int] ⇒
        val fl1 = IntArraySetBuilder(s).result
        var newS = Set.empty[Int]
        var newFLS = Set.empty[Int]
        s.withFilter(_ >= 0).withFilter(_ <= 1000).foreach(newS += _)
        fl1.withFilter(_ >= 0).withFilter(_ <= 1000).foreach(newFLS += _)
        newS == newFLS
    }

    property("withFilter -> size|empty|hasMultipleElements (does not force evaluation)") = forAll { s: Set[Int] ⇒
        val fl1 = IntArraySetBuilder(s).result
        var newS = Set.empty[Int]
        s.withFilter(_ >= 0).withFilter(_ <= 1000).foreach(newS += _)
        val newFLS = fl1.withFilter(_ >= 0).withFilter(_ <= 1000)
        newS.size == newFLS.size &&
            newS.isEmpty == newFLS.isEmpty &&
            (newS.size >= 2) == newFLS.hasMultipleElements &&
            (newFLS.isEmpty || newS.min == newFLS.min && newS.max == newFLS.max)
    }

    */
}
