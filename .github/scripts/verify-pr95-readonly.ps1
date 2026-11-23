# Historical filename retained for the C6 workflow.
# Prevent reintroducing a general physical-programming hard stop.
# Only the dedicated local E39 bench may write; the network bridge stays read-only.
$ErrorActionPreference = 'Stop'
$prefix = 'pc-bridge/CamaroDiag.J2534Bridge/'
$gate = Get-Content -LiteralPath ($prefix + 'NativeEcuWriteAvailability.cs') -Raw
$cli = Get-Content -LiteralPath ($prefix + 'NativeE39QualifiedBenchCli.cs') -Raw
$driver = Get-Content -LiteralPath ($prefix + 'J2534Device.NativeBench.cs') -Raw
$server = Get-Content -LiteralPath ($prefix + 'BridgeServer.cs') -Raw
$guardName = 'NativeEcuWriteAvailability.RequirePhysicalEcuProgrammingEnabled()'
if ($gate -notmatch 'PhysicalEcuProgrammingEnabled\s*=>\s*true\s*;') {
    throw 'C6 regression: the local E39 bench entry was hard-disabled again.'
}
foreach ($entry in @(
    @{ name = 'local E39 CLI'; code = $cli; next = 'NativeE39QualifiedBenchProfile.Load' },
    @{ name = 'native J2534 E39 bench'; code = $driver; next = 'NativeE39PhysicalAdmission.OpenVerifiedDriverLease' }
)) {
    $entryAt = $entry.code.IndexOf($guardName, [System.StringComparison]::Ordinal)
    $nextAt = $entry.code.IndexOf($entry.next, [System.StringComparison]::Ordinal)
    if ($entryAt -lt 0 -or $nextAt -le $entryAt) {
        throw ('Missing local E39 bench boundary in ' + $entry.name)
    }
}
if ($server -match 'RunQualifiedNativeE39BenchAsync|NativeE39QualifiedBenchCli') {
    throw 'Physical bench writer was exposed through the Android network bridge.'
}
Write-Output 'PASS: local E39 bench is available; network bridge remains read-only.'
