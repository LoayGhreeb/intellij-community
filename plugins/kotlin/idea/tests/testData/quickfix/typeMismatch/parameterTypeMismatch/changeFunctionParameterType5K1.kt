// "Change parameter 'x' type of function 'foo' to 'Any?'" "true"
// ERROR: Not enough information to infer type variable T
// LANGUAGE_VERSION: 1.8
package a

fun <T> foo(x: T & Any) {}

fun <T> bar(x: T) {
    foo(x<caret>)
}

// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.ChangeParameterTypeFix
// IGNORE_K2
// For K2-specific behaviour, see changeFunctionParameterType5K2.kt