fun <R> foo(x: R) {
}

fun bar() {
    foo<Long>(2 * 3)
    foo<Long>(-1)
}

// 2 I2L
// 2 java/lang/Long.valueOf