class A {
    fun foo() = this
    inline fun bar() = this
}

fun foo() {
    val a = A()
    a
     .foo()

    a
     .bar()
}

// 2 3 7 8 9 8 9 11 12 11 12 17 13
