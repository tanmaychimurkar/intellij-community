// "Opt in for 'A' on 'root'" "true"
// RUNTIME_WITH_SCRIPT_RUNTIME
@RequiresOptIn
annotation class A

@A
fun f1() {}

@OptIn()
fun root() {
    <caret>f1()
}