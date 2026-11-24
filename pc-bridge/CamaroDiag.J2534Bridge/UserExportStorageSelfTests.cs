namespace CamaroDiag.J2534Bridge;

internal static class UserExportStorageSelfTests
{
    internal static void Run()
    {
        static void Check(bool value) { if (!value) throw new InvalidOperationException("Export storage self-test"); }
        string root = Path.Combine(Path.GetTempPath(), "CamaroDiag-storage-" + Guid.NewGuid().ToString("N"));
        try
        {
            var dirs = Enum.GetValues<UserExportStorage.Category>()
                .Select(cat => UserExportStorage.EnsureCategory(root, cat)).ToArray();
            Check(dirs.Distinct(StringComparer.OrdinalIgnoreCase).Count() == dirs.Length);
            Check(dirs.All(dir => Directory.Exists(dir) &&
                dir.StartsWith(root + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)));
            Check(UserExportStorage.EnsureCategory(root, UserExportStorage.Category.EcuBackups) ==
                Path.Combine(root, "Backups", "ECU"));
            Check(UserExportStorage.EnsureCategory(root, UserExportStorage.Category.Recovery) ==
                Path.Combine(root, "Logs", "Recovery"));
            string first = UserExportStorage.NewFileName("backup.zip");
            string second = UserExportStorage.NewFileName("backup.zip");
            Check(first != second && first.EndsWith(".zip", StringComparison.Ordinal));
            foreach (string bad in new[] {"..", @"..\backup.zip", "folder/backup.zip"})
            {
                bool rejected = false;
                try { UserExportStorage.NewFileName(bad); } catch (ArgumentException) { rejected = true; }
                Check(rejected);
            }
            string broken = Path.Combine(root, "blocked");
            File.WriteAllText(broken, "preserve");
            bool conflict = false;
            try { UserExportStorage.EnsureCategory(broken, UserExportStorage.Category.Recovery); }
            catch (IOException) { conflict = true; }
            Check(conflict && File.ReadAllText(broken) == "preserve");
        }
        finally { if (Directory.Exists(root)) Directory.Delete(root, recursive: true); }
    }
}
