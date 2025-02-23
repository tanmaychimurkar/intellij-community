// "Propagate 'SubclassOptInRequired(UnstableApi::class)' opt-in requirement to 'SomeImplementation'" "true"
// ACTION: Add '-opt-in=PropagateSubclassOptInRequired5.UnstableApi' to module light_idea_test_case compiler arguments
// ACTION: Add full qualifier
// ACTION: Implement abstract class
// ACTION: Introduce import alias
// ACTION: Opt in for 'UnstableApi' in containing file 'propagateSubclassOptInRequired5.kts'
// ACTION: Opt in for 'UnstableApi' on 'SomeImplementation'
// ACTION: Propagate 'SubclassOptInRequired(UnstableApi::class)' opt-in requirement to 'SomeImplementation'
// RUNTIME_WITH_SCRIPT_RUNTIME

@RequiresOptIn
annotation class UnstableApi

interface MockApi

@SubclassOptInRequired(UnstableApi::class)
interface CoreLibraryApi

abstract class SomeImplementation : MockApi, CoreLibrary<caret>Api