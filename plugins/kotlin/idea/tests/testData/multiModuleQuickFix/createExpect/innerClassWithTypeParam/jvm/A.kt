// "Create expected class in common module testModule_Common" "true"
// DISABLE_ERRORS

class A<T> {
    actual inner class <caret>B<F : T>
}