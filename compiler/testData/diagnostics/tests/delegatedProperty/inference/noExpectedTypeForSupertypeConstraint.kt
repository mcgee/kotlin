class A {
    var a by <!TYPE_INFERENCE_NO_INFORMATION_FOR_PARAMETER!>MyProperty<!>()
}

class MyProperty<T, R> {

    public fun getValue(thisRef: R, desc: PropertyMetadata): T {
        throw Exception("$thisRef $desc")
    }

    public fun setValue(thisRef: R, desc: PropertyMetadata, t: T) {
        throw Exception("$thisRef $desc $t")
    }
}
