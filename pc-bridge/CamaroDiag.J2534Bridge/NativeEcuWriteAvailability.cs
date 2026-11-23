namespace CamaroDiag.J2534Bridge;

/// <summary>
/// PR #95 permits read-only physical diagnostics and backup only.
/// Programming, including the local E39 bench entry, is deferred until the
/// separately qualified C3/anti-brick work after audit #106.
/// This is an immutable fail-closed gate: no CLI, network or runtime toggle.
/// </summary>
internal static class NativeEcuWriteAvailability
{
    internal static bool PhysicalEcuProgrammingEnabled => false;

    internal static void RequirePhysicalEcuProgrammingEnabled()
    {
        if (!PhysicalEcuProgrammingEnabled)
            throw new UnauthorizedAccessException(
                "Physical ECU programming is unavailable in this CamaroDiag build.");
    }
}
