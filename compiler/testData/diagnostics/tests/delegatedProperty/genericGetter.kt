// !DIAGNOSTICS: -UNUSED_PARAMETER

val a: Int by A(1)

class A<T: Any>(i: T) {
  fun getValue(t: Any?, p: PropertyMetadata): T {
    throw Exception()
  }
}

