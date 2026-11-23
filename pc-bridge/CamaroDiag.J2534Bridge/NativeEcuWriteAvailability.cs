namespace CamaroDiag.J2534Bridge;

/// <summary>
/// The dedicated LOCAL E39 bench programming entry point is compiled in for
/// physical hardware validation. This flag does not confer programming
/// authorization: a trusted signed bench profile, verified driver, complete
/// matching physical backup and voltage/recovery checks remain mandatory.
/// The network bridge stays read-only and cannot reach this entry point.
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
