using System.Globalization;
using System.Resources;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Offline help dispatch. A help token takes precedence over ANY hardware,
/// network, updater or ECU option, including malformed mixed invocations.
/// </summary>
internal static class BridgeCommandLineHelp
{
    private static readonly ResourceManager Resources =
        new("CamaroDiag.J2534Bridge.BridgeCommandLineHelp", typeof(BridgeCommandLineHelp).Assembly);

    internal static bool IsHelpToken(string? arg) =>
        arg is not null &&
        (arg.Equals("--help", StringComparison.OrdinalIgnoreCase) ||
         arg.Equals("-h", StringComparison.OrdinalIgnoreCase) ||
         arg.Equals("/?", StringComparison.OrdinalIgnoreCase));

    internal static bool IsHelpRequest(string[] args) => args.Any(IsHelpToken);

    internal static string Render(CultureInfo? culture = null) =>
        Resources.GetString("HelpText", culture ?? CultureInfo.CurrentUICulture)
        ?? throw new InvalidOperationException("Windows bridge CLI help resources missing.");

    internal static int Run(string[] args)
    {
        // --help with a mutating option must NEVER fall through to that option.
        if (args.Length != 1 || !IsHelpToken(args[0]))
        {
            Console.Error.WriteLine("Use --help by itself / Utiliser --help seul.");
            return 2;
        }
        Console.WriteLine(Render());
        return 0;
    }
}
