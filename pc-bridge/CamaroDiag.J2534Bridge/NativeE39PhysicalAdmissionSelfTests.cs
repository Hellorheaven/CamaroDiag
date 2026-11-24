using System.Security.Cryptography;

namespace CamaroDiag.J2534Bridge;

internal static class NativeE39PhysicalAdmissionSelfTests
{
    internal static int Run()
    {
        var path = Path.GetTempFileName();
        int checks = 0;
        void Check(bool ok, string message) { checks++; if (!ok) throw new InvalidOperationException(message); }
        static bool Rejected(Action action)
        {
            try { action(); return false; }
            catch (UnauthorizedAccessException) { return true; }
        }
        try
        {
            byte[] sample = [1, 2, 3, 4];
            File.WriteAllBytes(path,sample);
            var adapter = new J2534Adapter("test-only",path,"fixture","Registry64");
            var scope = new J2534MemoryReadScope("1G1FA1RXXR0123456",0x7E0,0x7E8,33,
                0,NativeE39ImageInspector.ImageBytes,64,3);
            var channel = new NativeE39PhysicalAdmission.QualifiedChannel(
                Convert.ToHexString(SHA256.HashData(sample)),"Registry64",0x7E0,0x7E8,33,64,3);
            void Match(J2534Adapter a, NativeE39PhysicalAdmission.QualifiedChannel c)
            {
                using var lease = NativeE39PhysicalAdmission.OpenVerifiedDriverLease(a,scope,c);
            }
            Match(adapter,channel);
            Check(true,"Exact test driver/channel admitted");
            using (var lease = NativeE39PhysicalAdmission.OpenVerifiedDriverLease(adapter,scope,channel))
            {
                if (OperatingSystem.IsWindows())
                {
                    bool denied = false;
                    try
                    {
                        using var replacement = new FileStream(path, FileMode.Open, FileAccess.Write, FileShare.Read);
                    }
                    catch (IOException) { denied = true; }
                    Check(denied, "Verified Windows driver cannot be opened for modification during operation");
                }
            }
            Check(Rejected(() => Match(adapter,channel with { ResponseCanId = 0x7E9 })),
                "Wrong responder rejected");
            Check(Rejected(() => Match(adapter,channel with { MaximumStockReadBytes = 32 })),
                "Unqualified stock block size rejected");
            Check(Rejected(() => Match(adapter,channel with { StockReadSession = 1 })),
                "Different diagnostic session rejected");
            Check(Rejected(() => Match(adapter with { RegistryView = "Registry32" },channel)),
                "Driver architecture mismatch rejected");
            Check(Rejected(() => Match(adapter with {
                FunctionLibrary = @"\\server\share\vendor.dll"
            }, channel)), "Network-hosted vendor DLL refused");
            Check(Rejected(() => Match(adapter with {
                FunctionLibrary = @"\\?\C:\vendor\driver.dll"
            }, channel)), "Windows device-path DLL refused");
            File.WriteAllBytes(path,[1,2,3,5]);
            Check(Rejected(() => Match(adapter,channel)),"Operator-pinned driver mismatch rejected");
            Match(adapter,channel with { DriverSha256 = "" });
            Check(true,"Unpinned J2534 DLL update requires no external signing key");
        }
        finally { File.Delete(path); }
        Console.WriteLine($"PASS: {checks} qualified J2534 channel checks.");
        return checks;
    }
}
