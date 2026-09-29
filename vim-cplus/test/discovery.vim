set nocompatible
let s:repository = getcwd()
let &runtimepath = s:repository . '/vim-cplus,' . &runtimepath
let s:fixture = s:repository . '/documentation/fixtures/lsp-discovery'
execute 'cd ' . fnameescape(s:fixture)
let g:cplus_language_server_command = ''
let g:cplus_command = ''
call assert_equal(fnamemodify(s:fixture . '/.cplus/cpc.sh', ':p'), cpluslsp#DiscoverCommand())

if !empty(v:errors)
  for s:error in v:errors
    echomsg s:error
  endfor
  cquit
endif
qa!
