set nocompatible
let s:repository = getcwd()
let g:cplus_command = 'java -jar ' . shellescape(s:repository . '/cli/build/libs/c-plus.jar')
let g:cplus_language_server_command = ['java', '-jar', s:repository . '/cli/build/libs/c-plus.jar']
let &runtimepath = s:repository . '/vim-cplus,' . &runtimepath
execute 'edit ' . fnameescape(s:repository . '/vim-cplus/test/fixtures/outline.cp')
set filetype=cplus
runtime! ftplugin/cplus.vim
call cpluslsp#Diagnostics({'uri': 'file:///warning.cp', 'diagnostics': [{'range': {'start': {'line': 0, 'character': 0}, 'end': {'line': 0, 'character': 3}}, 'severity': 2, 'message': 'unmapped AST fragment'}]})
call assert_equal('W', getqflist()[0].type, 'LSP warning severity should reach Vim quickfix')
call cpluslsp#Start()
call setline(1, ['int main( {'])
call cpluslsp#ChangeCurrent()
sleep 500m
let s:title = getqflist({'title': 1}).title
call assert_equal('C-plus language-server diagnostics', s:title)
call assert_true(len(getqflist()) > 0, 'language-server diagnostics should reach Vim quickfix')
call cpluslsp#Restart()
sleep 500m
call assert_true(cpluslsp#Running(), 'language server should restart')
call cpluslsp#ChangeCurrent()
sleep 500m
call assert_true(len(getqflist()) > 0, 'restarted server should publish diagnostics')
call cpluslsp#Stop()

if !empty(v:errors)
  for s:error in v:errors
    echomsg s:error
  endfor
  cquit
endif
qa!
