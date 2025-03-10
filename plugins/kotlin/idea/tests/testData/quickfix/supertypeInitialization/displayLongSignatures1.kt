// "Add constructor parameters from Base(Int)" "true"
// ACTION: Add constructor parameters from Base(Char, Char, String, Int, Any?,...)
// ACTION: Add constructor parameters from Base(Char, Char, String?, Int, Any?)
// ACTION: Add constructor parameters from Base(Int)
// ACTION: Change to constructor invocation
// ACTION: Introduce import alias

open class Base {
    constructor(p: Int){}
    constructor(p1: Char, p2: Char, p3: String, p4: Int, p5: Any?, p6: String){}
    constructor(p1: Char, p2: Char, p3: String?, p4: Int, p5: Any?){}
}

class C : Base<caret>

// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.SuperClassNotInitialized$AddParametersFix
// FUS_K2_QUICKFIX_NAME: org.jetbrains.kotlin.idea.k2.codeinsight.fixes.SuperClassNotInitializedFactories$AddParametersFix