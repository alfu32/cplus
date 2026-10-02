set nocompatible
set encoding=utf-8
set runtimepath^=vim-cplus
call assert_equal(4, cpluslsp#Utf16Column('a😀b'))
call assert_equal(6, cpluslsp#ByteColumn('a😀b', 3))
call assert_equal('/tmp/café file.cp', cpluslsp#Path('file:///tmp/caf%C3%A9%20file.cp'))
call cpluslsp#Diagnostics({'uri': 'file:///tmp/one.cp', 'diagnostics': [{'message': 'one', 'severity': 2, 'range': {'start': {'line': 0, 'character': 0}}}]})
call cpluslsp#Diagnostics({'uri': 'file:///tmp/two.cp', 'diagnostics': [{'message': 'two', 'severity': 1, 'range': {'start': {'line': 0, 'character': 0}}}]})
call assert_equal(2, len(getqflist()))
call assert_equal(['W', 'E'], map(getqflist(), 'v:val.type'))
call cpluslsp#Diagnostics({'uri': 'file:///tmp/one.cp', 'diagnostics': []})
call assert_equal(1, len(getqflist()))
if !empty(v:errors)
  for error in v:errors | echomsg error | endfor
  cquit
endif
qa!
