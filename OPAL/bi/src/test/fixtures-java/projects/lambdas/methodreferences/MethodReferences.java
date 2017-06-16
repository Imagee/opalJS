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
package lambdas.methodreferences;

import java.util.*;
import java.util.function.*;

import annotations.target.InvokedMethod;
import static annotations.target.TargetResolution.*;

/**
 * This class contains a few simple examples for method references introduced in Java 8.
 *
 * <!--
 *
 * INTENTIONALLY LEFT EMPTY (THIS AREA CAN BE EXTENDED/REDUCED TO MAKE SURE THAT THE
 * SPECIFIED LINE NUMBERS ARE STABLE.
 *
 * -->
 * @author Arne Lottmann
 */
public class MethodReferences {
    @InvokedMethod(resolution = DYNAMIC, receiverType = "lambdas/methodreferences/MethodReferences$Value", name = "isEmpty", line = 52)
	public void filterOutEmptyValues() {
		List<Value> values = Arrays.asList(new Value("foo"), new Value(""));
		values.stream().filter(Value::isEmpty);
	}

	@InvokedMethod(resolution = DYNAMIC, receiverType = "lambdas/methodreferences/MethodReferences$Value", name = "compare", line = 58, isStatic = true)
	public void compareValues() {
		Comparator<Value> comparator = Value::compare;
		System.out.println(comparator.compare(new Value("a"), new Value("b")));
	}

	public interface ValueCreator {
		Value newValue(String value);
	}

	@InvokedMethod(resolution = DYNAMIC, receiverType = "lambdas/methodreferences/MethodReferences$Value", name = "<init>", line = 68)
	public Value newValue(String value) {
		ValueCreator v = Value::new;
		return v.newValue(value);
	}

    // @InvokedMethod(...)
    public int instanceMethod() {
        Function<String, Integer> i = String::length;
        return i.apply("instanceMethod");

    }

    // @InvokedMethod(...)
    public long staticMethod() {
        LongSupplier t = System::currentTimeMillis;
        return t.getAsLong();
    }

    public int explicitTypeArgs() {
        Function<List<String>, Integer> t = List<String>::size;
        ArrayList<String> stringArray = new ArrayList<>();
        stringArray.add("1");
        stringArray.add("2");
        return t.apply(stringArray);
    }

    public void partialBound(List<Object> someList) {
        // add(int index, E element)
        BiConsumer<Integer, Object> s = someList::add;
        s.accept(0, new Object());
    }

    public int inferredTypeArgs() {
        @SuppressWarnings("rawtypes") Function<List, Integer> t = List::size;
        ArrayList<String> stringArray = new ArrayList<>();
        stringArray.add("1");
        stringArray.add("2");
        return t.apply(stringArray);
    }

    public int[] intArrayClone() {
        int[] intArray = { 0, 1, 2, 42 };
        Function<int[], int[]> t = int[]::clone;

        return t.apply(intArray);
    }

    public int[][] intArrayArrayClone() {
        int[][] intArray = { { 0, 1, 2, 42 } };
        Function<int[][], int[][]> t = int[][]::clone;

        return t.apply(intArray);
    }

    public boolean objectMethod() {
        Value v = new Value("foo");
        BooleanSupplier t = v::isEmpty;
        return t.getAsBoolean();
    }

    public void referencePrintln() {
        Consumer<String> c = System.out::println;
        c.accept("Hello World!");
    }

    public int referenceLength() {
        Supplier<Integer> s = "foo"::length;
        return s.get();
    }

    public int arrayMethod() {
        String[] stringArray = { "0", "1", "2", "42" };

        IntSupplier s = stringArray[0]::length;

        return s.getAsInt();
    }

    public Iterator<String> ternaryIterator(boolean t) {
        ArrayList<String> stringArray1 = new ArrayList<>();
        stringArray1.add("1");
        stringArray1.add("2");
        ArrayList<String> stringArray2 = new ArrayList<>();
        stringArray2.add("foo");
        stringArray2.add("bar");

        Supplier<Iterator<String>> f = (t ?
                stringArray1 :
                stringArray2)::iterator;

        return f.get();
    }

    public String superToString() {
        Supplier<String> s = new Child().getSuperToString();
        return s.get();
    }

    public String overloadResolution() {
        Function<Double, String> f = String::valueOf;
        return f.apply(3.14);
    }

    public int[] typeArgsFromContext() {
        Consumer<int[]> c = Arrays::sort;

        int[] someInts = { 3, 2, 9, 14, 7 };

        c.accept(someInts);
        return someInts;
    }

    public int[] typeArgsExplicit() {
        Consumer<int[]> c = Arrays::<int[]>asList;

        int[] someInts = { 3, 2, 9, 14, 7 };

        c.accept(someInts);
        return someInts;
    }

    public ArrayList<String> parameterizedConstructor() {
        Supplier<ArrayList<String>> s = ArrayList<String>::new;
        return s.get();
    }

    @SuppressWarnings("rawtypes") public ArrayList inferredConstructor() {
        Supplier<ArrayList> s = ArrayList::new;
        return s.get();
    }

    public GenericConstructor genericConstructor() {
        Function<String, GenericConstructor> f = GenericConstructor::<String>new;
        return f.apply("42");
    }

    public GenericClass<String> genericClass() {
        Function<String, GenericClass<String>> f = GenericClass<String>::<String>new;
        return f.apply("42");
    }

    public Outer.Inner nestedClass() {
        Supplier<Outer.Inner> s = Outer.Inner::new;
        return s.get();
    }

    public int[] arrayNew() {
        Function<Integer, int[]> f = int[]::new;
        return f.apply(42);
    }

	public static class Outer {
        public static class Inner {

        }
    }

    public static class GenericConstructor {

        Object p;

        <T> GenericConstructor(T param) {
            p = param;
        }
    }

    public static class GenericClass<T> {

        Object p;
        T q;

        <U> GenericClass(U param) {
            p = param;
        }
    }

    public static class Parent {

        @Override public String toString() {
            return "Parent";
        }
    }

    public static class Child extends Parent {
		public static int compare(Value a, Value b) {
			return a.value.compareTo(b.value);
		}

        public Supplier<String> getSuperToString() {
            return super::toString;
        }

        @Override public String toString() {
            return "Child";
        }
    }

    public static class Value {

        private String value;

        public Value(String value) {
            this.value = value;
        }

        public boolean isEmpty() {
            return value.isEmpty();
        }

        public static int compare(Value a, Value b) {
            return a.value.compareTo(b.value);
        }
    }
}