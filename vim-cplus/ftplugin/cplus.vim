if exists("b:did_ftplugin")
  finish
endif
let b:did_ftplugin = 1

setlocal commentstring=//\ %s
setlocal omnifunc=cpluscomplete#Complete
setlocal suffixesadd=.cp,.c+
setlocal errorformat=%f:%l:%c:\ %m

command! -buffer -nargs=* CPlusTranscode call cplus#Run('transcode', <q-args>)
command! -buffer -nargs=* CPlusCompile call cplus#Run('compile', <q-args>)
command! -buffer -nargs=* CPlusRun call cplus#Run('run', <q-args>)
command! -buffer CPlusCheck call cplus#Check()
command! -buffer CPlusParse call cplus#Parse()
command! -buffer CPlusSymbols call cplus#Symbols()
command! -buffer CPlusImportGraph call cplus#ImportGraph()

if get(g:, 'cplus_check_on_write', 0)
  augroup cplus_buffer_check
    autocmd! * <buffer>
    autocmd BufWritePost <buffer> call cplus#Check()
  augroup END
endif

if get(g:, 'cplus_parse_on_write', 0)
  augroup cplus_buffer_parse
    autocmd! * <buffer>
    autocmd BufWritePost <buffer> call cplus#Parse()
  augroup END
endif
