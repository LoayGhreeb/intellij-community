// "Make 'method' public explicitly" "true"
// PRIORITY: HIGH
// COMPILER_ARGUMENTS: -Xexplicit-api=strict

public class Foo2() {
    fun <caret>method() {}
}

// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.ChangeVisibilityFix$ChangeToPublicExplicitlyFix
// FUS_K2_QUICKFIX_NAME: org.jetbrains.kotlin.idea.k2.codeinsight.fixes.ChangeVisibilityFixFactories$ChangeToPublicModCommandAction