//!DIAGNOSTICS: -UNUSED_PARAMETER

@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
fun <@kotlin.internal.OnlyInputTypes T> assertEquals1(t1: T, t2: T) {}

open class A
class B: A()
class C: A()
class D

fun test1(a: A, b: B, c: C) {
    assertEquals1(a, b)
    <!TYPE_INFERENCE_ONLY_INPUT_TYPES!>assertEquals1<!>(b, c)
}

@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
public fun <@kotlin.internal.OnlyInputTypes T> expect1(expected: T, block: () -> T) {}

fun test() {
    expect1(2) { byteArrayOf(1, 2, 3).indexOf(3) }
}