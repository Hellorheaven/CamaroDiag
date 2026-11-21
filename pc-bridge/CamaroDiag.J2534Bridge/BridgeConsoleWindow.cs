using System.Runtime.InteropServices;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Prevents an unwanted terminal from sitting behind the Start-menu GUI,
/// without hiding a user's existing PowerShell/terminal window when the
/// bridge was launched intentionally from that shell.
/// </summary>
internal static class BridgeConsoleWindow
{
    [DllImport("kernel32.dll")]
    private static extern IntPtr GetConsoleWindow();

    [DllImport("kernel32.dll")]
    private static extern uint GetConsoleProcessList(
        [Out] uint[] processList, uint processCount);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool ShowWindow(IntPtr window, int command);

    internal static void HideOnlyStandaloneConsole()
    {
        if (!OperatingSystem.IsWindows()) return;
        var window = GetConsoleWindow();
        if (window == IntPtr.Zero) return;
        // A console created specifically for our process has a single
        // member; shared PowerShell consoles have at least two. Never
        // hide a shared terminal belonging to the operator.
        var attached = new uint[8];
        if (GetConsoleProcessList(attached, (uint)attached.Length) == 1)
            ShowWindow(window, 0); // SW_HIDE
    }
}
