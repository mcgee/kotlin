// IS_APPLICABLE: false
fun main(args: Array<String>) {
    val foo: String? = "foo"
    val bar: String? = null
    if (foo == bar<caret>) {
        foo?.size
    }
    else null
}
