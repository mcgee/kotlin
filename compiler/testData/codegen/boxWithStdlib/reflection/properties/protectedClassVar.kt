import kotlin.reflect.*
import kotlin.reflect.jvm.isAccessible

class A(param: String) {
    protected var v: String = param

    fun ref() = A::class.memberProperties.single() as KMutableProperty1<A, String>
}

fun box(): String {
    val a = A(":(")
    val f = a.ref()

    try {
        f.get(a)
        return "Fail: protected property getter is accessible by default"
    } catch (e: IllegalCallableAccessException) { }

    try {
        f.set(a, ":D")
        return "Fail: protected property setter is accessible by default"
    } catch (e: IllegalCallableAccessException) { }

    f.isAccessible = true

    f.set(a, ":)")

    return if (f[a] != ":)") "Fail: ${f[a]}" else "OK"
}
