/** Language vocabulary shared by C-plus completion and the editor smoke tests. */
export const cplusAnnotations = [
    "pub", "priv", "mut", "borrowed", "owned", "stat",
    "scratch", "hot", "warm", "cold"
];

export const cplusKeywords = [
    "comptime", "defer", "import", "type", "variable", "function", "code", "test", "var", "fn", "flags"
];

export const comptimeResultKinds = ["type", "variable", "function", "code", "string", "int", "float", "void"];

export const comptimeForms = ["import", "flags"];

export const comptimeAtForms = [
    "@import", "@if", "@else", "@for", "@type", "@var", "@fn", "@code", "@test",
    "@assert", "@assertEquals"
];

export const comptimeValues = ["os"];
export const comptimeProperties = ["name", "size", "align", "fields", "type"];

export const cKeywords = [
    "auto", "break", "case", "char", "const", "continue", "default", "do", "double",
    "else", "enum", "extern", "float", "for", "goto", "if", "inline", "int", "long",
    "register", "restrict", "return", "short", "signed", "sizeof", "static", "struct",
    "switch", "typedef", "union", "unsigned", "void", "volatile", "while",
    "_Alignas", "_Alignof", "_Atomic", "_Bool", "_Complex", "_Generic", "_Imaginary",
    "_Noreturn", "_Static_assert", "_Thread_local"
];

export const cTypes = ["bool", "size_t", "ptrdiff_t", "wchar_t", "char16_t", "char32_t"];

export const builtinTestMacros = ["CPLUS_TEST_ASSERT", "CPLUS_TEST_ASSERT_EQUALS", "CPLUS_TEST_FAIL"];
