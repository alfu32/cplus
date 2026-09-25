function! cpluscomplete#Complete(findstart, base) abort
  if a:findstart
    let line = getline('.')
    let start = col('.') - 1
    while start > 0 && line[start - 1] =~# '\a\|\d\|_'
      let start -= 1
    endwhile
    if start > 0 && line[start - 1] ==# '@'
      return start - 1
    endif
    return start
  endif

  let line = strpart(getline('.'), 0, col('.') - 1)
  let candidates = []
  let seen = {}
  let AddCandidate = {word, menu, kind -> s:Add(candidates, seen, word, menu, kind)}

  let reflection = matchlist(line, '\<\([A-Za-z_][A-Za-z0-9_]*\)\.\w*$')
  if !empty(reflection) && s:IsComptimeTypeName(reflection[1])
    for property in ['name', 'size', 'align', 'fields', 'type']
      call AddCandidate(property, '[C-plus comptime reflection property]', 'v')
    endfor
    return s:Filter(candidates, a:base)
  endif

  if line =~# '\%(\.\|->\)\s*\w*$'
    for field in s:Fields()
      call AddCandidate(field, '[C-plus field]', 'f')
    endfor
    for method in s:Methods()
      call AddCandidate(method, '[C-plus method]', 'm')
    endfor
    return s:Filter(candidates, a:base)
  endif

  if line =~# '\<comptime\s\+[A-Za-z_]*$'
    for kind in ['type', 'variable', 'function', 'code', 'import', 'flags', 'string', 'int', 'float', 'void']
      call AddCandidate(kind, '[C-plus comptime result/form]', 'k')
    endfor
    return s:Filter(candidates, a:base)
  endif

  if line =~# '@\a\w*$'
    for keyword in ['@import', '@if', '@else', '@for', '@type', '@var', '@fn', '@code', '@test', '@assert', '@assertEquals', '@throws', '@try', '@catch']
      call AddCandidate(keyword, '[C-plus comptime]', 'k')
    endfor
    for symbol in s:ComptimeSymbols()
      call AddCandidate(symbol, '[C-plus comptime]', 'v')
    endfor
  else
    for keyword in ['comptime', 'defer', 'import', 'type', 'variable', 'function', 'code', 'test', 'var', 'fn', 'flags']
      call AddCandidate(keyword, '[C-plus keyword]', 'k')
    endfor
    for keyword in ['auto', 'break', 'case', 'char', 'const', 'continue', 'default', 'do', 'double', 'else', 'enum', 'extern', 'float', 'for', 'goto', 'if', 'inline', 'int', 'long', 'register', 'restrict', 'return', 'short', 'signed', 'sizeof', 'static', 'struct', 'switch', 'typedef', 'union', 'unsigned', 'void', 'volatile', 'while', '_Alignas', '_Alignof', '_Atomic', '_Bool', '_Complex', '_Generic', '_Imaginary', '_Noreturn', '_Static_assert', '_Thread_local']
      call AddCandidate(keyword, '[C keyword]', 'k')
    endfor
    for annotation in ['pub', 'priv', 'mut', 'borrowed', 'owned', 'stat', 'scratch', 'hot', 'warm', 'cold']
      call AddCandidate(annotation, '[C-plus annotation]', 'k')
    endfor
    for type_name in ['bool', 'size_t', 'ptrdiff_t', 'wchar_t', 'char16_t', 'char32_t']
      call AddCandidate(type_name, '[C/C-plus built-in type]', 't')
    endfor
    for macro in ['CPLUS_TEST_ASSERT', 'CPLUS_TEST_ASSERT_EQUALS', 'CPLUS_TEST_FAIL']
      call AddCandidate(macro, '[C-plus test helper macro]', 'f')
    endfor
    if line =~# '\%(@if\|@else\s\+if\)\s*(.*$'
      call AddCandidate('os', '[C-plus comptime target value]', 'v')
    endif
    for type_name in s:Types()
      call AddCandidate(type_name, '[C-plus type]', 't')
    endfor
    for method in s:Methods()
      call AddCandidate(method, '[C-plus method]', 'm')
    endfor
    for function in s:Functions()
      call AddCandidate(function, '[C-plus function]', 'f')
    endfor
  endif
  return s:Filter(candidates, a:base)
endfunction

function! s:IsComptimeTypeName(name) abort
  let pattern = '\%(@\)\?type\s\+' . escape(a:name, '\') . '\>'
  for source_line in getline(1, '$')
    if source_line =~# pattern
      return 1
    endif
  endfor
  return 0
endfunction

function! s:Add(result, seen, word, menu, kind) abort
  if empty(a:word) || has_key(a:seen, a:word)
    return
  endif
  let a:seen[a:word] = 1
  call add(a:result, {'word': a:word, 'menu': a:menu, 'kind': a:kind})
endfunction

function! s:Filter(candidates, base) abort
  return filter(a:candidates, 'stridx(v:val.word, a:base) == 0')
endfunction

function! s:Types() abort
  let result = []
  for line in getline(1, '$')
    let value = matchstr(line, '\<[A-Za-z_][A-Za-z0-9_]*_t\>')
    if !empty(value)
      call add(result, value)
    endif
  endfor
  return uniq(sort(result))
endfunction

function! s:Functions() abort
  let result = []
  for line in getline(1, '$')
    let value = matchstr(line, '\<[A-Za-z_][A-Za-z0-9_]*\>\s*(')
    let value = substitute(value, '\s*($', '', '')
    if !empty(value) && index(['if', 'for', 'while', 'switch'], value) < 0
      call add(result, value)
    endif
    let alias = matchlist(line, '^\s*#\s*define\s\+[A-Za-z_][A-Za-z0-9_]*\s\+\([A-Za-z_][A-Za-z0-9_]*\)\s*$')
    if !empty(alias)
      call add(result, alias[1])
    endif
  endfor
  return uniq(sort(result))
endfunction

function! s:Methods() abort
  let result = []
  for line in getline(1, '$')
    let value = matchstr(line, '\<\%(pub\|priv\|static\)\?\s*[A-Za-z_][A-Za-z0-9_]*\s\+\zs[A-Za-z_][A-Za-z0-9_]*\ze\s*(')
    if !empty(value) && index(['if', 'for', 'while', 'switch'], value) < 0
      call add(result, value)
    endif
  endfor
  return uniq(sort(result))
endfunction

function! s:Fields() abort
  let result = []
  for line in getline(1, '$')
    let value = matchstr(line, '^\s*\%(pub\|priv\|mut\|borrowed\|owned\|static\)\?\s*[A-Za-z_][A-Za-z0-9_ *]*\s\+\zs[A-Za-z_][A-Za-z0-9_]*\ze\s*;')
    if !empty(value)
      call add(result, value)
    endif
  endfor
  return uniq(sort(result))
endfunction

function! s:ComptimeSymbols() abort
  let result = []
  for line in getline(1, '$')
    let start = 0
    while 1
      let match = matchstrpos(line, '@[A-Za-z_][A-Za-z0-9_]*', start)
      if empty(match[0])
        break
      endif
      if index(['@import', '@if', '@else', '@for', '@type', '@var', '@fn', '@code', '@test', '@assert', '@assertEquals', '@throws', '@try', '@catch'], match[0]) < 0
        call add(result, match[0])
      endif
      let start = match[2]
    endwhile
  endfor
  return uniq(sort(result))
endfunction
