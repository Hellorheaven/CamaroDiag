# PR #95 must remain read-only on physical ECU hardware.
# This guard is independent of self-tests and must not be bypassed by
# changing their expected result alongside the production capability.
$ErrorActionPreference = 'Stop'
$gatePath = 'pc-bridge/CamaroDiag.J2534Bridge/NativeEcuWriteAvailability.cs'
$cliPath = 'pc-bridge/CamaroDiag.J2534Bridge/NativeE39QualifiedBenchCli.cs'
$driverPath = 'pc-bridge/CamaroDiag.J2534Bridge/J2534Device.NativeBench.cs'
$gate = Get-Content -LiteralPath $gatePath -Raw
$cli = Get-Content -LiteralPath $cliPath -Raw
$driver = Get-Content -LiteralPath $driverPath -Raw
$guardName = 'NativeEcuWriteAvailability.RequirePhysicalEcuProgrammingEnabled()'
if ($gate -notmatch 'PhysicalEcuProgrammingEnabled\s*=>\s*false\s*;') {
    throw 'PR95 security violation: physical ECU programming gate is not disabled.'
}
if ($gate -notmatch 'if\s*\(\s*!PhysicalEcuProgrammingEnabled\s*\)') {
    throw 'PR95 security violation: physical ECU programming guard is not enforced.'
}
foreach ($entry in @(
    @{ name = 'Native E39 CLI'; code = $cli; next = 'NativeE39QualifiedBenchProfile.Load' },
    @{ name = 'J2534 physical entry'; code = $driver; next = 'NativeE39PhysicalAdmission.OpenVerifiedDriverLease' }
)) {
    $gateAt = $entry.code.IndexOf($guardName, [System.StringComparison]::Ordinal)
    $hardwareAt = $entry.code.IndexOf($entry.next, [System.StringComparison]::Ordinal)
    if ($gateAt -lt 0 -or $hardwareAt -le $gateAt) {
        throw ("PR95 security violation: missing early write guard in " + $entry.name)
    }
}
Write-Output 'PASS: PR95 ECU write/erase/programming entry points remain disabled.'
