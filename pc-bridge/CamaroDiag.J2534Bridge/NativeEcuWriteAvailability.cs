namespace CamaroDiag.J2534Bridge;

/// <summary>
/// PR #95 exposes verified read-only diagnostics, memory capture and offline
/// analysis, but does NOT qualify any physical ECU programming/recovery route.
/// This compile-time security boundary has no CLI, environment-variable or
/// network bypass. A later, separately reviewed hardware-qualified release
/// must explicitly replace it before enabling any on-device flash operation.
/// </summary>
internal static class NativeEcuWriteAvailability
{
    internal static bool PhysicalEcuProgrammingEnabled => false;

    internal static void RequirePhysicalEcuProgrammingEnabled()
    {
        if (!PhysicalEcuProgrammingEnabled)
            throw new UnauthorizedAccessException(
                "Physical ECU programming is disabled in this read-only CamaroDiag build. " +
                "Offline analysis and test simulation remain available.");
    }
}
