// Based on KT-8643
public class MyClass
{
    fun main() {
        var str: String? = null

        if (str != null)
            callback {
                method1(<!DEBUG_INFO_SMARTCAST!>str<!>)
            }
    }

    inline fun callback(foo: () ->Unit) = foo()

    fun method1(str: String) = str
}