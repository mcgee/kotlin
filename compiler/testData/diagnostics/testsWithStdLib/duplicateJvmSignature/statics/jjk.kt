// !DIAGNOSTICS: -UNUSED_PARAMETER

// FILE: A.java

public class A {
    static void foo() {}
    static void baz(String s) {}
}

// FILE: B.java

public class B extends A {
    static void bar(int i) {}
}

// FILE: K.kt

open class K : B() {
    companion object {
        <!ACCIDENTAL_OVERRIDE!>@JvmStatic
        fun foo()<!> {}
        @JvmStatic
        fun foo(a: Any) {}
        <!ACCIDENTAL_OVERRIDE!>@JvmStatic
        fun bar(i: Int)<!> {}
        @JvmStatic
        fun bar(i: String) {}
        @JvmStatic
        fun baz(i: Int) {}
    }
}
