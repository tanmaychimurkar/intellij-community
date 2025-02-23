// "Move 'SomeOptInAnnotation' opt-in requirement from getter to property" "true"

@RequiresOptIn
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.PROPERTY_GETTER)
annotation class SomeOptInAnnotation

class Foo(val value: Int) {
    val bar: Boolean
        <caret>@SomeOptInAnnotation get() = value > 0
}
