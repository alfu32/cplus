@echo off
setlocal
set "CPLUS_HOME=%~dp0"
if "%CPLUS_HOME:~-1%"=="\" set "CPLUS_HOME=%CPLUS_HOME:~0,-1%"
java "-Dcplus.home=%CPLUS_HOME%" -jar "%CPLUS_HOME%\c-plus.jar" %*
exit /b %ERRORLEVEL%
