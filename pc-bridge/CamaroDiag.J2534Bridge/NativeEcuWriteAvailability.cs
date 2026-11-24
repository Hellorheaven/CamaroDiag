namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Local E39/E39A physical bench entry is enabled for explicit hardware tests.
/// This is not a license or a GM SecurityAccess substitute.
/// The Android/network bridge remains read-only; live ECU identity, full
/// backup, stable power and write verification still guard real operations.
/// </summary>
internal static class NativeEcuWriteAvailability
{
    internal static bool PhysicalEcuProgrammingEnabled => true;

    internal static void RequirePhysicalEcuProgrammingEnabled()
    {
        if (!PhysicalEcuProgrammingEnabled)
            throw new UnauthorizedAccessException(
                "Physical ECU programming is unavailable in this CamaroDiag build.");
    }
}
