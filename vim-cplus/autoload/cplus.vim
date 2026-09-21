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
