// "Change parameter 'x' type of function 'foo' to 'Any?'" "true"
// ERROR: Not enough information to infer type variable T
// LANGUAGE_VERSION: 1.8
package a

fun <T> foo(x: T & Any) {}

fun <T> bar(x: T) {
    foo(x<caret>)
}

// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.ChangeParameterTypeFix
/* IGNORE_K2 */

// Same example as in testData/quickfix/typeMismatch/definitelyNonNullableTypes/changeFunctionParameterType1.kt
// but shows another quick fix