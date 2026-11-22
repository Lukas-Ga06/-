@echo off
rem Double-click to start the backend (same as: powershell -ExecutionPolicy Bypass -File run-dev.ps1).
rem Arguments are forwarded, e.g.  run-dev.cmd -Jar   /   run-dev.cmd -Check
rem Keep this file ASCII-only with CRLF endings: cmd.exe reads .cmd as ANSI and
rem mis-parses UTF-8 text and LF-only line breaks.
setlocal
cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-dev.ps1" %*
set "RC=%ERRORLEVEL%"

rem Pause only when launched by double-click (cmd.exe /c ...), so the window stays open.
echo %cmdcmdline% | find /i " /c " >nul
if not errorlevel 1 pause

exit /b %RC%