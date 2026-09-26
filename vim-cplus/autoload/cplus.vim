function! cplus#Run(subcommand, args) abort
  if &buftype !=# ''
    echoerr 'C-plus commands require a file buffer'
    return
  endif
  let command = get(g:, 'cplus_command', 'cplus')
  let file = shellescape(expand('%:p'))
  let extra = empty(a:args) ? '' : ' ' . a:args
  if a:subcommand ==# 'run'
    execute '!' . command . ' run ' . file . extra
    return
  endif
  let output = systemlist(command . ' ' . a:subcommand . ' ' . file . extra . ' 2>&1')
  let &l:errorformat = '%f:%l:%c:\ %m'
  call setqflist([], 'r', {'title': 'C-plus ' . a:subcommand, 'lines': output})
  if v:shell_error
    copen
    echohl ErrorMsg
    echomsg 'C-plus ' . a:subcommand . ' failed'
    echohl None
  else
    echo 'C-plus ' . a:subcommand . ' succeeded'
  endif
endfunction

function! cplus#Check() abort
  call cplus#Run('compile', '')
endfunction

function! s:byte_column(line, utf16_column) abort
  let units = 0
  let bytes = 0
  let index = 0
  while index < strchars(a:line) && units < a:utf16_column - 1
    let character = strcharpart(a:line, index, 1)
    let units += char2nr(character) > 0xffff ? 2 : 1
    let bytes += strlen(character)
    let index += 1
  endwhile
  return bytes + 1
endfunction

function! cplus#Parse() abort
  if &buftype !=# ''
    echoerr 'C-plus parser diagnostics require a file buffer'
    return
  endif
  let command = get(g:, 'cplus_command', 'cplus')
  let source = join(getline(1, '$'), "\n") . (&l:endofline ? "\n" : '')
  let output = system(command . ' parse --stdin --source ' . shellescape(expand('%:p')), source)
  let status = v:shell_error
  try
    let result = json_decode(output)
  catch
    echoerr 'C-plus parse returned invalid JSON; requires a CLI with cplus.parse.v1 support'
    return
  endtry
  let items = []
  for diagnostic in get(result, 'diagnostics', [])
    let span = diagnostic.span
    let line_number = max([1, get(span, 'startLine', 1)])
    let line_text = getline(line_number)
    call add(items, {
          \ 'filename': expand('%:p'),
          \ 'lnum': line_number,
          \ 'col': s:byte_column(line_text, get(span, 'startColumn', 1)),
          \ 'text': get(diagnostic, 'message', 'C-plus parser diagnostic'),
          \ 'type': get(diagnostic, 'severity', 'error') ==# 'warning' ? 'W' : 'E'
          \ })
  endfor
  call setqflist([], 'r', {'title': 'C-plus parser diagnostics', 'items': items})
  if status != 0 || !empty(items)
    copen
  else
    echo 'C-plus parse succeeded'
  endif
endfunction

function! s:find_declarator_identifier(node) abort
  if get(a:node, 'kind', '') ==# 'identifier' && get(a:node, 'field', '') ==# 'declarator'
    return a:node
  endif
  for child in get(a:node, 'children', [])
    let found = s:find_declarator_identifier(child)
    if !empty(found)
      return found
    endif
  endfor
  return {}
endfunction

function! s:find_named_child(node, field) abort
  if get(a:node, 'field', '') ==# a:field && get(a:node, 'kind', '') ==# 'identifier'
    return a:node
  endif
  for child in get(a:node, 'children', [])
    let found = s:find_named_child(child, a:field)
    if !empty(found)
      return found
    endif
  endfor
  return {}
endfunction

function! s:symbol_name(node) abort
  let span = get(a:node, 'span', {})
  let line_number = get(span, 'startLine', 1)
  let line_text = getline(line_number)
  let byte_column = s:byte_column(line_text, get(span, 'startColumn', 1))
  return matchstr(strpart(line_text, byte_column - 1), '^[A-Za-z_][A-Za-z0-9_]*')
endfunction

function! s:add_symbol(items, node, label) abort
  let span = get(a:node, 'span', {})
  let line_number = max([1, get(span, 'startLine', 1)])
  let line_text = getline(line_number)
  call add(a:items, {
        \ 'filename': expand('%:p'),
        \ 'lnum': line_number,
        \ 'col': s:byte_column(line_text, get(span, 'startColumn', 1)),
        \ 'text': a:label,
        \ 'type': 'I'
        \ })
endfunction

function! cplus#Symbols() abort
  if &buftype !=# '' || empty(expand('%:p'))
    echoerr 'C-plus symbols require a file buffer'
    return
  endif
  let command = get(g:, 'cplus_command', 'cplus')
  let source = join(getline(1, '$'), "\n") . (&l:endofline ? "\n" : '')
  let output = system(command . ' parse --stdin --source ' . shellescape(expand('%:p')), source)
  try
    let result = json_decode(output)
  catch
    echoerr 'C-plus parse returned invalid JSON; requires a CLI with cplus.parse.v1 support'
    return
  endtry
  if get(result, 'schema', '') !=# 'cplus.parse.v1' || empty(get(result, 'ast', {}))
    echoerr 'C-plus parse did not return a cplus.parse.v1 syntax tree'
    return
  endif

  let items = []
  for declaration in get(result.ast, 'children', [])
    if get(declaration, 'kind', '') ==# 'type_alias'
      let structure = {}
      for child in get(declaration, 'children', [])
        if get(child, 'kind', '') ==# 'struct_declaration'
          let structure = child
          break
        endif
      endfor
      if empty(structure)
        continue
      endif
      let name_node = {}
      for child in get(declaration, 'children', [])
        if get(child, 'kind', '') ==# 'identifier' && get(child, 'field', '') ==# 'declarator'
          let name_node = child
          break
        endif
      endfor
      if empty(name_node)
        let name_node = s:find_named_child(structure, 'name')
      endif
      let type_name = empty(name_node) ? 'anonymous_struct' : s:symbol_name(name_node)
      call s:add_symbol(items, empty(name_node) ? declaration : name_node, 'struct ' . type_name)
      for body in get(structure, 'children', [])
        if get(body, 'field', '') !=# 'body'
          continue
        endif
        for member in get(body, 'children', [])
          let member_kind = get(member, 'kind', '')
          if member_kind ==# 'field_declaration'
            let member_name = s:find_declarator_identifier(member)
            if !empty(member_name)
              call s:add_symbol(items, member_name, type_name . '.' . s:symbol_name(member_name) . ' (field)')
            endif
          elseif member_kind ==# 'method_declaration'
            let member_name = s:find_declarator_identifier(member)
            if !empty(member_name)
              call s:add_symbol(items, member_name, type_name . '.' . s:symbol_name(member_name) . ' (method)')
            endif
          endif
        endfor
      endfor
    elseif get(declaration, 'kind', '') ==# 'function_declaration'
      let function_name = s:find_declarator_identifier(declaration)
      if !empty(function_name)
        call s:add_symbol(items, function_name, s:symbol_name(function_name) . ' (function)')
      endif
    endif
  endfor
  call setloclist(0, [], 'r', {'title': 'C-plus parser symbols', 'items': items})
  lopen
  echo 'Loaded ' . len(items) . ' C-plus symbols from normalized parser tree'
endfunction

function! cplus#ImportGraph() abort
  if &buftype !=# '' || empty(expand('%:p'))
    echoerr 'C-plus import graph requires a file buffer'
    return
  endif
  if &modified
    echoerr 'Save the C-plus file before resolving imports'
    return
  endif
  let command = get(g:, 'cplus_command', 'cplus')
  let output = systemlist(command . ' graph ' . shellescape(expand('%:p')) . ' 2>&1')
  let status = v:shell_error
  if status != 0
    echoerr join(output, "\n")
    return
  endif
  try
    let result = json_decode(join(output, "\n"))
  catch
    echoerr 'C-plus graph returned invalid JSON; requires a CLI with cplus.imports.v1 support'
    return
  endtry
  if get(result, 'schema', '') !=# 'cplus.imports.v1'
        \ || type(get(result, 'imports', v:null)) != v:t_list
    echoerr 'C-plus graph did not return a cplus.imports.v1 import graph'
    return
  endif

  let items = []
  for edge in result.imports
    let imported = get(edge, 'imported', '')
    let location = get(edge, 'location', {})
    if empty(imported)
      continue
    endif
    call add(items, {
          \ 'filename': imported,
          \ 'lnum': 1,
          \ 'col': 1,
          \ 'text': printf('imported from %s:%d:%d', get(edge, 'importer', expand('%:p')),
          \     get(location, 'startLine', 1), get(location, 'startColumn', 1)),
          \ 'type': 'I'
          \ })
  endfor
  call setloclist(0, [], 'r', {'title': 'C-plus resolved imports', 'items': items})
  if empty(items)
    echo 'No resolved C-plus imports'
    return
  endif
  lopen
  echo 'Loaded ' . len(items) . ' resolved C-plus imports'
endfunction
