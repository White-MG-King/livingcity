$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

& (Join-Path $root 'build.ps1')

$jar = Join-Path $root 'build\bpconv.jar'
$testClasses = Join-Path $root 'build\testclasses'
if (Test-Path $testClasses) { Remove-Item -Recurse -Force $testClasses }
New-Item -ItemType Directory -Force -Path $testClasses | Out-Null

& javac -encoding UTF-8 -d $testClasses (Join-Path $root 'testdata\GenSamples.java')
if ($LASTEXITCODE -ne 0) { throw 'GenSamples compile failed' }
& java -cp $testClasses GenSamples (Join-Path $root 'testdata\inputs') | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'GenSamples run failed' }

$formats = @(
    @{ Format = 'litematic'; File = 'sample_house.litematic' },
    @{ Format = 'nbt'; File = 'sample_house.nbt' },
    @{ Format = 'schematic'; File = 'sample_house.schematic' },
    @{ Format = 'bp'; File = 'sample_house.bp' }
)
$common = @('--kind', 'HOUSE', '--id', 'sample_house', '--author', 'bpconv-testdata',
    '--license', 'CC0', '--entrance', '1,0,2')

function Compare-Bytes([string]$Left, [string]$Right) {
    $a = [IO.File]::ReadAllBytes($Left)
    $b = [IO.File]::ReadAllBytes($Right)
    if ($a.Length -ne $b.Length) { return $false }
    for ($i = 0; $i -lt $a.Length; $i++) {
        if ($a[$i] -ne $b[$i]) { return $false }
    }
    return $true
}

function Get-Body([string]$Path) {
    return ((Get-Content -LiteralPath $Path -Encoding UTF8) |
        Where-Object { $_.Trim() -ne '' -and -not $_.StartsWith('#') -and -not $_.StartsWith('@') }) -join "`n"
}

$bodies = @{}
foreach ($format in $formats) {
    $outDir = Join-Path $root "build\testout\$($format.Format)"
    if (Test-Path $outDir) { Remove-Item -Recurse -Force $outDir }

    $javaArgs = @('-cp', $jar, 'bpconv.Main',
        (Join-Path $root "testdata\inputs\$($format.File)"),
        '-o', $outDir) + $common
    & java @javaArgs | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "convert failed ($($format.Format)) exit=$LASTEXITCODE" }

    $actual = Join-Path $outDir 'HOUSE\sample_house.bp'
    $expected = Join-Path $root "testdata\expected\$($format.Format)\HOUSE\sample_house.bp"
    if (-not (Test-Path $actual)) { throw "missing output: $actual" }
    if (-not (Test-Path $expected)) { throw "missing golden file: $expected" }
    if (-not (Compare-Bytes $actual $expected)) {
        throw "golden diff failed: $($format.Format)"
    }
    $bodies[$format.Format] = Get-Body $actual
}

# cross-format consistency
if ($bodies['litematic'] -ne $bodies['nbt'] -or $bodies['nbt'] -ne $bodies['schematic'] -or $bodies['schematic'] -ne $bodies['bp']) {
    throw 'cross-format voxel sequence mismatch'
}

# determinism
foreach ($format in $formats) {
    $run1 = Join-Path $root "build\determinism\$($format.Format)-1"
    $run2 = Join-Path $root "build\determinism\$($format.Format)-2"
    foreach ($dir in @($run1, $run2)) {
        if (Test-Path $dir) { Remove-Item -Recurse -Force $dir }
        $javaArgs = @('-cp', $jar, 'bpconv.Main',
            (Join-Path $root "testdata\inputs\$($format.File)"),
            '-o', $dir) + $common
        & java @javaArgs | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "determinism run failed ($($format.Format))" }
    }
    if (-not (Compare-Bytes (Join-Path $run1 'HOUSE\sample_house.bp') (Join-Path $run2 'HOUSE\sample_house.bp'))) {
        throw "non-deterministic output: $($format.Format)"
    }
}

# license gate: CC-BY-SA must exit 2 and produce nothing
$licenseOut = Join-Path $root 'build\license-gate'
if (Test-Path $licenseOut) { Remove-Item -Recurse -Force $licenseOut }
$licenseArgs = @('-cp', $jar, 'bpconv.Main',
    (Join-Path $root 'testdata\inputs\sample_house.litematic'),
    '-o', $licenseOut, '--kind', 'HOUSE', '--id', 'sample_house',
    '--author', 'bpconv-testdata', '--license', 'CC-BY-SA-4.0', '--entrance', '1,0,2')
& java @licenseArgs | Out-Null
if ($LASTEXITCODE -ne 2) { throw "license gate expected exit 2, got $LASTEXITCODE" }
if (Test-Path (Join-Path $licenseOut 'HOUSE\sample_house.bp')) { throw 'license-gated output must not exist' }

# validation gate: HOUSE with 0 beds must exit 1 and produce nothing
$invalidOut = Join-Path $root 'build\invalid-gate'
if (Test-Path $invalidOut) { Remove-Item -Recurse -Force $invalidOut }
$invalidArgs = @('-cp', $jar, 'bpconv.Main',
    (Join-Path $root 'testdata\inputs\sample_house.litematic'),
    '-o', $invalidOut, '--kind', 'HOUSE', '--id', 'bad_house',
    '--author', 'bpconv-testdata', '--license', 'CC0', '--entrance', '1,0,2', '--beds', '0')
& java @invalidArgs | Out-Null
if ($LASTEXITCODE -ne 1) { throw "validation gate expected exit 1, got $LASTEXITCODE" }
if (Test-Path (Join-Path $invalidOut 'HOUSE\bad_house.bp')) { throw 'invalid output must not exist' }

# topology: floor < bed foot < bed head; support < torch
$golden = Join-Path $root 'testdata\expected\litematic\HOUSE\sample_house.bp'
$lines = Get-Content -LiteralPath $golden -Encoding UTF8
$floorForBed = -1
$foot = -1
$head = -1
$floorForTorch = -1
$torch = -1
for ($i = 0; $i -lt $lines.Length; $i++) {
    $line = $lines[$i]
    if ($line -match '^0,0,1 minecraft:stone ') { $floorForBed = $i }
    if ($line -match '^0,1,1 minecraft:red_bed\[facing=east,part=foot\] ') { $foot = $i }
    if ($line -match '^1,1,1 minecraft:red_bed\[facing=east,part=head\] ') { $head = $i }
    if ($line -match '^2,0,1 minecraft:stone ') { $floorForTorch = $i }
    if ($line -match '^2,1,1 minecraft:torch ') { $torch = $i }
}
if ($floorForBed -lt 0 -or $foot -lt 0 -or $head -lt 0 -or $floorForTorch -lt 0 -or $torch -lt 0) {
    throw 'topology check could not locate expected lines'
}
if (-not ($floorForBed -lt $foot)) { throw 'bed must come after its floor' }
if (-not ($foot -lt $head)) { throw 'bed head must come after foot' }
if (-not ($floorForTorch -lt $torch)) { throw 'torch must come after its support' }

Write-Host ''
Write-Host 'ALL CONVERTER TESTS PASSED'
Write-Host '  golden diff: litematic / nbt / schematic / bp'
Write-Host '  cross-format voxel sequence: identical'
Write-Host '  determinism: identical'
Write-Host '  license gate: CC-BY-SA rejected (exit 2)'
Write-Host '  validation gate: HOUSE beds=0 rejected (exit 1)'
Write-Host '  topology: floor < bed foot < bed head, support < torch'
