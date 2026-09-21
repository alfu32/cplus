function! cpluscomplete#Complete(findstart, base) abort
  if a:findstart
    let line = getline('.')
    let start = col('.') - 1
    while start > 0 && line[start - 1] =~# '\a\|\d\|_'
      let start -= 1
    endwhile
    return start
  endif

  let words = ['pub', 'priv', 'mut', 'borrowed', 'owned', 'stat']
  for line in getline(1, '$')
    let candidate = matchstr(line, '\<[A-Za-z_][A-Za-z0-9_]*\>\s*(')
    let candidate = substitute(candidate, '\s*($', '', '')
    if candidate !=# '' && index(['if', 'for', 'while', 'switch'], candidate) < 0
      call add(words, candidate)
    endif
  endfor

  let unique = uniq(sort(words))
  return filter(map(unique, '{"word": v:val, "menu": "[C-plus]"}'), 'stridx(v:val.word, a:base) == 0')
endfunction
