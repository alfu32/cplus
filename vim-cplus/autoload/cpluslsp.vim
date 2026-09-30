let s:job = 0
let s:channel = 0
let s:buffer = ''
let s:next_id = 1
let s:pending = {}
let s:versions = {}

function! cpluslsp#Start() abort
  if cpluslsp#Running()
    call cpluslsp#SyncCurrent()
    return
  endif
  if !exists('*job_start')
    echoerr 'C-plus LSP requires Vim with job_start() and channel support'
    return
  endif
  let command = cpluslsp#DiscoverCommand()
  let command_parts = type(command) == v:t_list ? copy(command) : split(command)
  let command_parts += get(g:, 'cplus_language_server_arguments', [])
  call add(command_parts, 'lsp')
  let options = {
        \ 'in_mode': 'raw',
        \ 'out_mode': 'raw',
        \ 'err_mode': 'raw',
        \ 'out_cb': function('cpluslsp#Receive'),
        \ 'err_cb': function('cpluslsp#Error'),
        \ 'close_cb': function('cpluslsp#Closed')
        \ }
  let s:job = job_start(command_parts, options)
  if type(s:job) == v:t_number && s:job <= 0
    let s:job = 0
    echoerr cpluslsp#DiscoveryFailure(command_parts)
    return
  endif
  let s:channel = job_getchannel(s:job)
  call cpluslsp#Request('initialize', {
        \ 'processId': getpid(),
        \ 'rootUri': cpluslsp#Uri(getcwd()),
        \ 'capabilities': {},
        \ 'workspaceFolders': [{'uri': cpluslsp#Uri(getcwd()), 'name': fnamemodify(getcwd(), ':t')}]
        \ })
  call cpluslsp#Notify('initialized', {})
  augroup cplus_lsp_sync
    autocmd! *
    autocmd BufEnter *.cp,*.c+ call cpluslsp#OpenCurrent()
    autocmd BufWritePost *.cp,*.c+ call cpluslsp#ChangeCurrent()
    autocmd BufUnload *.cp,*.c+ call cpluslsp#CloseBuffer(expand('<abuf>'))
  augroup END
  call cpluslsp#OpenCurrent()
  echo 'C-plus language server started'
endfunction

" Resolve in the same order as the other editor clients: explicit command,
" project-local launchers, CPLUS_HOME/user-local launchers, then PATH.
function! cpluslsp#DiscoverCommand() abort
  let configured = get(g:, 'cplus_language_server_command', '')
  if empty(configured) && exists('g:cplus_command')
    let configured = get(g:, 'cplus_command', '')
  endif
  if type(configured) == v:t_list
    return configured
  endif
  if !empty(trim(configured))
    return configured
  endif
  if has('win32')
    let names = ['cpc.cmd', 'cpc.exe', 'cpc']
  else
    let names = ['cpc.sh', 'cpc']
  endif
  for directory in ['.cplus', 'c-plus-bin', '']
    for name in names
      let found = findfile(empty(directory) ? name : directory . '/' . name, '.;')
      if !empty(found)
        return fnamemodify(found, ':p')
      endif
    endfor
  endfor
  let cplus_home = exists('$CPLUS_HOME') ? $CPLUS_HOME : ''
  for root in [cplus_home, expand('$HOME') . '/.local/bin', expand('$HOME') . '/.local/bin/c-plus', expand('$HOME') . '/.local/share/c-plus']
    if empty(root) || root ==# '/.local/bin' || root ==# '/.local/bin/c-plus' || root ==# '/.local/share/c-plus'
      continue
    endif
    for name in names
      let found = root . '/' . name
      if filereadable(found) && executable(found)
        return fnamemodify(found, ':p')
      endif
    endfor
  endfor
  return 'cpc'
endfunction

function! cpluslsp#DiscoveryFailure(command) abort
  return 'C-plus language server could not be started (' . join(a:command, ' ') . '). ' .
        \ 'Set g:cplus_language_server_command, add .cplus/cpc(.sh|.cmd) or c-plus-bin/cpc, or install cpc in PATH.'
endfunction

function! cpluslsp#Stop() abort
  if cpluslsp#HasChannel()
    call cpluslsp#Request('shutdown', v:null)
    call ch_close_in(s:channel)
  endif
  if cpluslsp#Running()
    call job_stop(s:job)
  endif
  let s:job = v:null
  let s:channel = 0
  let s:buffer = ''
  let s:pending = {}
  let s:versions = {}
endfunction

" Restart the external server while retaining the current buffer snapshot.
function! cpluslsp#Restart() abort
  call cpluslsp#Stop()
  call cpluslsp#Start()
endfunction

function! cpluslsp#OpenCurrent() abort
  if !cpluslsp#HasChannel() || &buftype !=# '' || &filetype !=# 'cplus'
    return
  endif
  let uri = cpluslsp#Uri(expand('%:p'))
  let document_version = get(s:versions, bufnr('%'), 0) + 1
  let s:versions[bufnr('%')] = document_version
  call cpluslsp#Notify('textDocument/didOpen', {
        \ 'textDocument': {'uri': uri, 'languageId': 'cplus', 'version': document_version, 'text': cpluslsp#Text()}
        \ })
endfunction

function! cpluslsp#ChangeCurrent() abort
  if !cpluslsp#HasChannel() || &buftype !=# '' || &filetype !=# 'cplus'
    return
  endif
  let uri = cpluslsp#Uri(expand('%:p'))
  let document_version = get(s:versions, bufnr('%'), 0) + 1
  let s:versions[bufnr('%')] = document_version
  call cpluslsp#Notify('textDocument/didChange', {
        \ 'textDocument': {'uri': uri, 'version': document_version},
        \ 'contentChanges': [{'text': cpluslsp#Text()}]
        \ })
endfunction

function! cpluslsp#SyncCurrent() abort
  call cpluslsp#OpenCurrent()
endfunction

function! cpluslsp#CloseBuffer(number) abort
  if !cpluslsp#HasChannel() || !bufexists(str2nr(a:number))
    return
  endif
  let path = resolve(fnamemodify(bufname(str2nr(a:number)), ':p'))
  call cpluslsp#Notify('textDocument/didClose', {'textDocument': {'uri': cpluslsp#Uri(path)}})
  call remove(s:versions, str2nr(a:number))
endfunction

function! cpluslsp#Request(method, params) abort
  let id = s:next_id
  let s:next_id += 1
  let s:pending[id] = a:method
  call cpluslsp#Write({'jsonrpc': '2.0', 'id': id, 'method': a:method, 'params': a:params})
  return id
endfunction

function! cpluslsp#Running() abort
  if type(s:job) == v:t_number
    return s:job > 0 && job_status(s:job) ==# 'run'
  endif
  return type(s:job) == v:t_job && job_status(s:job) ==# 'run'
endfunction

function! cpluslsp#HasChannel() abort
  if type(s:channel) == v:t_number
    return s:channel != 0
  endif
  return type(s:channel) == v:t_channel
endfunction

function! cpluslsp#Notify(method, params) abort
  call cpluslsp#Write({'jsonrpc': '2.0', 'method': a:method, 'params': a:params})
endfunction

function! cpluslsp#Write(message) abort
  if !cpluslsp#HasChannel()
    return
  endif
  let body = json_encode(a:message)
  call ch_sendraw(s:channel, 'Content-Length: ' . strlen(body) . "\r\n\r\n" . body)
endfunction

function! cpluslsp#Receive(channel, message) abort
  let s:buffer .= type(a:message) == v:t_list ? join(a:message, '') : a:message
  while 1
    let separator = match(s:buffer, "\r\n\r\n")
    if separator < 0
      return
    endif
    let header = strpart(s:buffer, 0, separator)
    let length = matchstr(header, '\cContent-Length:\s*\zs\d\+')
    if empty(length)
      let s:buffer = strpart(s:buffer, separator + 4)
      continue
    endif
    let body_start = separator + 4
    if strlen(s:buffer) < body_start + str2nr(length)
      return
    endif
    let body = strpart(s:buffer, body_start, str2nr(length))
    let s:buffer = strpart(s:buffer, body_start + str2nr(length))
    call cpluslsp#Dispatch(json_decode(body))
  endwhile
endfunction

function! cpluslsp#Dispatch(message) abort
  if get(a:message, 'method', '') ==# 'textDocument/publishDiagnostics'
    call cpluslsp#Diagnostics(get(a:message, 'params', {}))
    return
  endif
  let id = get(a:message, 'id', -1)
  if has_key(s:pending, id)
    call remove(s:pending, id)
  endif
endfunction

function! cpluslsp#Diagnostics(params) abort
  let uri = get(a:params, 'uri', '')
  let path = substitute(uri, '^file://', '', '')
  let path = substitute(path, '%20', ' ', 'g')
  let items = []
  for diagnostic in get(a:params, 'diagnostics', [])
    let range = get(diagnostic, 'range', {})
    let start = get(range, 'start', {})
    call add(items, {
          \ 'filename': path,
          \ 'lnum': get(start, 'line', 0) + 1,
          \ 'col': get(start, 'character', 0) + 1,
          \ 'text': get(diagnostic, 'message', 'C-plus language-server diagnostic'),
          \ 'type': get(diagnostic, 'severity', 1) == 2 ? 'W' : 'E'
          \ })
  endfor
  call setqflist([], 'r', {'title': 'C-plus language-server diagnostics', 'items': items})
  if !empty(items) && bufnr('%') == bufnr(path)
    copen
  endif
endfunction

function! cpluslsp#Error(channel, message) abort
  if !empty(a:message)
    echomsg 'C-plus LSP: ' . (type(a:message) == v:t_list ? join(a:message, '') : a:message)
  endif
endfunction

function! cpluslsp#Closed(job, ...) abort
  " A stopped server may report its close callback after a replacement job
  " has already started. Never let the old callback clear the new handle.
  if a:job !=# s:job
    return
  endif
  let s:job = v:null
  let s:channel = 0
endfunction

function! cpluslsp#Text() abort
  return join(getline(1, '$'), "\n") . (&l:endofline ? "\n" : '')
endfunction

function! cpluslsp#Uri(path) abort
  let path = substitute(resolve(a:path), '\\', '/', 'g')
  return 'file://' . (path[0] ==# '/' ? '' : '/') . substitute(path, ' ', '%20', 'g')
endfunction
