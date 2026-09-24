if exists("b:current_syntax")
  finish
endif

syntax case match
syntax keyword cplusAnnotation pub priv mut borrowed owned stat scratch hot warm cold
syntax keyword cplusSelf self
syntax keyword cplusKeyword typedef struct enum union static const volatile restrict return if else for while do switch case default break continue variable function
syntax keyword cplusType void char short int long float double signed unsigned size_t
syntax keyword cplusComptimeKeyword comptime import if else for type var fn variable function code test defer
syntax match cplusPreprocessor /^\s*#.*$/
syntax match cplusStructType /\<[A-Za-z_][A-Za-z0-9_]*_t\>/
syntax match cplusComptime /@[A-Za-z_][A-Za-z0-9_]*/
syntax match cplusComptimeCall /@[A-Za-z_][A-Za-z0-9_]*\ze\s*(/
syntax match cplusComptimeProperty /\<[A-Za-z_][A-Za-z0-9_]*\.\%(name\|size\|align\|fields\)\>/
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
