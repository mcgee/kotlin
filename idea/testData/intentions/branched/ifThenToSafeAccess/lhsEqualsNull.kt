fun maybeFoo(): String? {
    return "foo"
}

fun main(args: Array<String>) {
    val foo = maybeFoo()
    if (foo == null<caret>)
        null
    else
        foo.size
}
