if exists("b:current_syntax")
  finish
endif

syntax case match
syntax keyword cplusAnnotation pub priv mut borrowed owned stat scratch hot warm cold
syntax keyword cplusSelf self
syntax keyword cplusKeyword auto break case char const continue default do double else enum extern float for goto if inline int long register restrict return short signed sizeof static struct switch typedef union unsigned void volatile while _Alignas _Alignof _Atomic _Bool _Complex _Generic _Imaginary _Noreturn _Static_assert _Thread_local
syntax keyword cplusType void char short int long float double signed unsigned size_t ptrdiff_t wchar_t char16_t char32_t bool
syntax keyword cplusComptimeKeyword comptime import if else for type var fn variable function code test defer string
syntax match cplusPreprocessor /^\s*#.*$/
syntax match cplusStructType /\<[A-Za-z_][A-Za-z0-9_]*_t\>/
syntax match cplusComptime /@[A-Za-z_][A-Za-z0-9_]*/
syntax match cplusComptimeCall /@[A-Za-z_][A-Za-z0-9_]*\ze\s*(/
syntax match cplusComptimeBuiltin /@\%(import\|if\|else\|for\|type\|var\|fn\|code\|test\|assertEquals\|assert\|throws\|try\|catch\)\>/
syntax match cplusBuiltinMacro /\<CPLUS_TEST_\%(ASSERT\|ASSERT_EQUALS\|FAIL\)\>/
syntax match cplusComptimeFlags /\<flags\>/
syntax match cplusComptimeValue /\<os\>/
syntax match cplusComptimeProperty /\.\%(name\|size\|align\|fields\|type\)\>/
syntax match cplusFunction /\<[A-Za-z_][A-Za-z0-9_]*\>\ze\s*(/
syntax match cplusNumber /\<\%(0[xX][0-9A-Fa-f]\+\|[0-9]\+\%([.][0-9]*\)\?\)\>/

syntax region cplusString start=/"/ skip=/\\./ end=/"/
syntax region cplusChar start=/'/ skip=/\\./ end=/'/
syntax match cplusLineComment /\/\/.*$/ contains=@Spell
syntax region cplusBlockComment start=/\/\*/ end=/\*\// contains=@Spell

highlight default link cplusAnnotation Special
highlight default link cplusSelf Special
highlight default link cplusKeyword Keyword
highlight default link cplusType Type
highlight default link cplusStructType Type
highlight default link cplusComptime PreProc
highlight default link cplusComptimeCall PreProc
highlight default link cplusComptimeBuiltin Keyword
highlight default link cplusBuiltinMacro PreProc
highlight default link cplusComptimeFlags PreProc
highlight default link cplusComptimeValue Constant
highlight default link cplusComptimeKeyword PreProc
highlight default link cplusPreprocessor PreProc
highlight default link cplusComptimeProperty Identifier
highlight default link cplusFunction Function
highlight default link cplusNumber Number
highlight default link cplusString String
highlight default link cplusChar Character
highlight default link cplusLineComment Comment
highlight default link cplusBlockComment Comment

let b:current_syntax = "cplus"
