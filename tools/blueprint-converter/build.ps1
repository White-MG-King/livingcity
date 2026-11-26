$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$classes = Join-Path $root 'build\classes'
$jar = Join-Path $root 'build\bpconv.jar'

if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
New-Item -ItemType Directory -Force -Path $classes | Out-Null

$sources = Get-ChildItem -Path (Join-Path $root 'src') -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
& javac -encoding UTF-8 -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

$manifest = Join-Path $root 'build\MANIFEST.MF'
Set-Content -Path $manifest -Value 'Main-Class: bpconv.Main' -Encoding ASCII
if (Test-Path $jar) { Remove-Item -Force $jar }
& jar --create --file $jar --manifest $manifest -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }

Write-Host "built $jar (zero external dependencies)"
