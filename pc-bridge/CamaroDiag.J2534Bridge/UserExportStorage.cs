namespace CamaroDiag.J2534Bridge;

/// <summary>Default user-visible export destinations; never stores keys or private state.</summary>
internal static class UserExportStorage
{
    internal enum Category { EcuBackups, DiagnosticSessions, Calibrations, Reports,
        Console, Recovery, Telemetry, Tests, Exports, Profiles, Updates }

    internal static string[] Segments(Category category) => category switch
    {
        Category.EcuBackups => ["Backups", "ECU"],
        Category.DiagnosticSessions => ["Backups", "Diagnostics"],
        Category.Calibrations => ["Calibrations"],
        Category.Reports => ["Reports"],
        Category.Console => ["Logs", "Console"],
        Category.Recovery => ["Logs", "Recovery"],
        Category.Telemetry => ["Telemetry"],
        Category.Tests => ["Tests"],
        Category.Exports => ["Exports"],
        Category.Profiles => ["Profiles"],
        Category.Updates => ["Updates"],
        _ => throw new ArgumentOutOfRangeException(nameof(category))
    };

    internal static string DefaultRoot()
    {
        string documents = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);
        if (!Path.IsPathFullyQualified(documents) || !Directory.Exists(documents))
            throw new IOException("Documents folder unavailable.");
        return Path.Combine(documents, "CamaroDiag");
    }

    internal static string EnsureCategory(string root, Category category)
    {
        if (!Path.IsPathFullyQualified(root)) throw new ArgumentException("Export root must be absolute.");
        string path = Path.GetFullPath(root);
        EnsureDirectory(path);
        foreach (string segment in Segments(category))
        {
            path = Path.Combine(path, segment);
            EnsureDirectory(path);
        }
        return path;
    }

    private static void EnsureDirectory(string path)
    {
        Directory.CreateDirectory(path);
        var attributes = File.GetAttributes(path);
        if ((attributes & FileAttributes.Directory) == 0 || (attributes & FileAttributes.ReparsePoint) != 0)
            throw new IOException("Invalid or redirected CamaroDiag export directory: " + path);
    }

    internal static string NewFileName(string name)
    {
        if (string.IsNullOrWhiteSpace(name) || name != Path.GetFileName(name) ||
            name.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0 || name is "." or "..")
            throw new ArgumentException("Invalid export filename.");
        string extension = Path.GetExtension(name);
        return Path.GetFileNameWithoutExtension(name) + "_" + Guid.NewGuid().ToString("N") + extension;
    }
}
