// "Create expected class in common module testModule_Common" "true"
// SHOULD_FAIL_WITH: Some types are not accessible from testModule_Common:,Some
// DISABLE_ERRORS


interface Some

actual class <caret>A<T : Some>