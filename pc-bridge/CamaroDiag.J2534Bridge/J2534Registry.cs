using Microsoft.Win32;

namespace CamaroDiag.J2534Bridge;

internal sealed record J2534Adapter(string Name, string FunctionLibrary, string RegistryPath, string RegistryView)
{
    public string DisplayName => $"{Name} [{RegistryView}] — {FunctionLibrary}";
}

internal static class J2534Registry
{
    private static readonly string[] RegistryKeys =
    [
        @"SOFTWARE\PassThruSupport.04.04",
        @"SOFTWARE\PassThruSupport.05.00"
    ];

    public static IReadOnlyList<J2534Adapter> Discover()
    {
        var found = new Dictionary<string, J2534Adapter>(StringComparer.OrdinalIgnoreCase);
        foreach (var view in new[] { RegistryView.Registry64, RegistryView.Registry32 })
        {
            try
            {
                using var root = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, view);
                foreach (var registryPath in RegistryKeys)
                {
                    using var key = root.OpenSubKey(registryPath);
                    if (key is null) continue;

                    foreach (var childName in key.GetSubKeyNames())
                    {
                        using var child = key.OpenSubKey(childName);
                        if (child is null) continue;

                        var rawPath = child.GetValue("FunctionLibrary") as string;
                        if (string.IsNullOrWhiteSpace(rawPath)) continue;
                        var library = Environment.ExpandEnvironmentVariables(rawPath.Trim().Trim('"'));
                        if (!File.Exists(library)) continue;

                        var name = child.GetValue("Name") as string;
                        if (string.IsNullOrWhiteSpace(name)) name = childName;
                        var adapter = new J2534Adapter(name.Trim(), Path.GetFullPath(library),
                            $@"HKLM\{registryPath}\{childName}", view.ToString());
                        found.TryAdd(adapter.FunctionLibrary, adapter);
                    }
                }
            }
            catch (Exception ex) when (ex is UnauthorizedAccessException or IOException or System.Security.SecurityException)
            {
                // One inaccessible registry view must not hide drivers in the other view.
            }
        }

        return found.Values.OrderBy(x => x.Name, StringComparer.OrdinalIgnoreCase).ToArray();
    }
}
