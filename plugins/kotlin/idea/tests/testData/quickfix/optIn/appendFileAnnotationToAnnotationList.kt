// "Opt in for 'A' in containing file 'appendFileAnnotationToAnnotationList.kt'" "true"
// IGNORE_FIR
// COMPILER_ARGUMENTS: -opt-in=kotlin.RequiresOptIn
// WITH_STDLIB
@file:[
    JvmName("Foo")
    OptIn(B::class)
    Suppress("UNSUPPORTED_FEATURE")
]

package p

@RequiresOptIn
annotation class A

@RequiresOptIn
annotation class B

@A
fun f() {}

fun g() {
    <caret>f()
}
