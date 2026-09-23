@echo off
setlocal
set "CPLUS_HOME=%~dp0"
java -Dcplus.home="%CPLUS_HOME%" -jar "%CPLUS_HOME%c-plus.jar" %*
exit /b %ERRORLEVEL%
