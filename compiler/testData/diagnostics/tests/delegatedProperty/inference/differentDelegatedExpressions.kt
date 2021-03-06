package baz

class A(outer: Outer) {
    var i: String by  + getMyConcreteProperty()
    var d: String by  getMyConcreteProperty() - 1
    var c: String by  O.getMyProperty()
    var g: String by  outer.getContainer().getMyProperty()


    var b: String by  foo(<!TYPE_INFERENCE_NO_INFORMATION_FOR_PARAMETER!>getMyProperty<!>())
    var r: String by  foo(outer.getContainer().<!TYPE_INFERENCE_NO_INFORMATION_FOR_PARAMETER!>getMyProperty<!>())
    var e: String by  <!DEBUG_INFO_ELEMENT_WITH_ERROR_TYPE!>+<!> <!TYPE_INFERENCE_NO_INFORMATION_FOR_PARAMETER!>foo<!>(<!TYPE_INFERENCE_NO_INFORMATION_FOR_PARAMETER!>getMyProperty<!>())
    var f: String by  <!TYPE_INFERENCE_NO_INFORMATION_FOR_PARAMETER!>foo<!>(<!TYPE_INFERENCE_NO_INFORMATION_FOR_PARAMETER!>getMyProperty<!>()) <!DEBUG_INFO_ELEMENT_WITH_ERROR_TYPE!>-<!> 1
}

fun <A, B> foo(<!UNUSED_PARAMETER!>a<!>: Any?) = MyProperty<A, B>()

fun <A, B> getMyProperty() = MyProperty<A, B>()

fun getMyConcreteProperty() = MyProperty<Any?, String>()

class MyProperty<R, T> {

    public fun getValue(thisRef: R, desc: PropertyMetadata): T {
        println("get $thisRef ${desc.name}")
        return null as T
    }

    public fun setValue(thisRef: R, desc: PropertyMetadata, value: T) {
        println("set $thisRef ${desc.name} $value")
    }
}

operator fun <R, T> MyProperty<R, T>.plus() = MyProperty<R, T>()
operator fun <R, T> MyProperty<R, T>.minus(<!UNUSED_PARAMETER!>i<!>: Int) = MyProperty<R, T>()

object O {
    fun <A, B> getMyProperty() = MyProperty<A, B>()
}

interface MyPropertyContainer {
    fun <R, T> getMyProperty(): MyProperty<R, T>
}

interface Outer {
    fun getContainer(): MyPropertyContainer
}

// -----------------
fun println(a: Any?) = a