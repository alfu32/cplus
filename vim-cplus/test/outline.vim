set nocompatible
let s:repository = getcwd()
let g:cplus_command = 'java -jar ' . shellescape(s:repository . '/cli/build/libs/c-plus.jar')
let &runtimepath = s:repository . '/vim-cplus,' . &runtimepath
execute 'edit ' . fnameescape(s:repository . '/vim-cplus/test/fixtures/outline.cp')
call append('$', 'int unsaved_parser_symbol(void);')
set filetype=cplus
runtime! ftplugin/cplus.vim
call cplus#Parse()
let s:diagnostic_list_title = getqflist({'title': 1}).title
let s:source_buffer = bufnr('%')
let s:source_window = win_getid()
call cplus#Symbols()

let s:items = getloclist(s:source_window, {'items': 1}).items
let s:labels = map(copy(s:items), 'v:val.text')
call assert_true(index(s:labels, 'struct sample_t') >= 0, 'AST outline should include the struct alias')
call assert_true(index(s:labels, 'sample_t.value (field)') >= 0, 'AST outline should include struct fields')
call assert_true(index(s:labels, 'sample_t.get (method)') >= 0, 'AST outline should include instance methods')
call assert_true(index(s:labels, 'sample_t.create (method)') >= 0, 'AST outline should include static methods')
call assert_true(index(s:labels, 'main (function)') >= 0, 'AST outline should include top-level functions')
call assert_true(index(s:labels, 'first (function)') >= 0, 'AST outline should include prototypes after multibyte text')
call assert_true(index(s:labels, 'unsaved_parser_symbol (function)') >= 0, 'AST outline should parse the unsaved buffer contents')
call assert_equal(s:diagnostic_list_title, getqflist({'title': 1}).title, 'symbol outline should preserve parser diagnostics quickfix list')
let s:first_item = s:items[index(s:labels, 'first (function)')]
let s:first_line = getbufline(s:source_buffer, s:first_item.lnum)[0]
call assert_equal('first', matchstr(strpart(s:first_line, s:first_item.col - 1), '^[A-Za-z_][A-Za-z0-9_]*'), 'Unicode-aware symbol location should select the function name')

execute 'edit ' . fnameescape(s:repository . '/vim-cplus/test/fixtures/import_graph.cp')
set filetype=cplus
runtime! ftplugin/cplus.vim
let s:graph_diagnostic_title = getqflist({'title': 1}).title
call cplus#ImportGraph()
let s:graph_window = win_getid()
let s:graph_items = getloclist(s:graph_window, {'items': 1}).items
call assert_equal(1, len(s:graph_items), 'import graph should list the resolved imported source')
call assert_equal(fnamemodify(s:repository . '/vim-cplus/test/fixtures/import_graph_dependency.cp', ':p'),
      \ fnamemodify(bufname(get(s:graph_items[0], 'bufnr', -1)), ':p'), 'import graph entries should navigate to imported files')
call assert_match('import_graph.cp:1:1', s:graph_items[0].text, 'import graph entries should retain requesting source location')
call assert_equal(s:graph_diagnostic_title, getqflist({'title': 1}).title, 'import graph should preserve parser diagnostics quickfix list')

if !empty(v:errors)
  for s:error in v:errors
    echomsg s:error
  endfor
  cquit
endif
qa!
