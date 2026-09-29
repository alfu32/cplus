set nocompatible
let s:repository = getcwd()
let g:cplus_command = 'java -jar ' . shellescape(s:repository . '/cli/build/libs/c-plus.jar')
let s:kotlin_candidates = glob($HOME . '/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/*/*/kotlin-stdlib-*.jar', 1, 1)
let s:kotlin = filter(s:kotlin_candidates, 'v:val !~# "sources"')[-1]
let s:treesitter_candidates = glob($HOME . '/.gradle/caches/modules-2/files-2.1/io.github.tree-sitter/ktreesitter-jvm/*/*/ktreesitter-jvm-*.jar', 1, 1)
let s:treesitter = filter(s:treesitter_candidates, 'v:val !~# "sources"')[-1]
let s:classpath = join([
      \ s:repository . '/cli/build/libs/cli-0.5.47.jar',
      \ s:repository . '/compiler/build/libs/compiler-0.5.47.jar',
      \ s:repository . '/parser-tree-sitter/build/libs/parser-tree-sitter-jvm.jar',
      \ s:treesitter,
      \ s:kotlin
      \ ], ':')
let g:cplus_language_server_command = ['java', '-cp', s:classpath, 'cplus.MainKt']
let &runtimepath = s:repository . '/vim-cplus,' . &runtimepath
execute 'edit ' . fnameescape(s:repository . '/vim-cplus/test/fixtures/outline.cp')
set filetype=cplus
runtime! ftplugin/cplus.vim
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
