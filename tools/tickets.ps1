# The tracker is tools/tickets.mjs (node, the same on every OS); this only calls it, for the old commands.
# UTF-8 with BOM: Windows PowerShell 5.1 reads a .ps1 without one as ANSI.
[Console]::OutputEncoding = [Text.Encoding]::UTF8
& node (Join-Path $PSScriptRoot 'tickets.mjs') @args
exit $LASTEXITCODE
