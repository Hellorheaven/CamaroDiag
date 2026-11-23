namespace CamaroDiag.J2534Bridge;

internal static class UdsStandardServicesSelfTests
{
    internal static int Run()
    {
        var checks = 0;
        void Check(bool result, string name)
        {
            checks++;
            if (!result) throw new InvalidOperationException("ISO 14229 self-test: " + name);
        }
        void Reject<T>(Action action, string name) where T : Exception
        {
            var rejected = false;
            try { action(); }
            catch (T) { rejected = true; }
            Check(rejected, name);
        }

        void Equal(byte[] bytes, string expected, string name) =>
            Check(Convert.ToHexString(bytes) == expected, name);
        Equal(UdsStandardServices.Session(0x02), "1002", "Programming session");
        Equal(UdsStandardServices.Reset(0x01), "1101", "ECU reset");
        Equal(UdsStandardServices.TesterPresent(true), "3E80",
            "Tester present suppress positive");
        Equal(UdsStandardServices.ReadDid(0xF190), "22F190", "Read DID");
        Equal(UdsStandardServices.WriteDid(0xF190, [0x43, 0x44]),
            "2EF1904344", "Write DID full wire bytes");
        Equal(UdsStandardServices.ReadMemory(0x80001000, 0x20),
            "23448000100000000020", "Read MemoryByAddress ALFI");
        Equal(UdsStandardServices.WriteMemory(0x80001000, [0xCA, 0xFE]),
            "3D448000100000000002CAFE", "Write MemoryByAddress ALFI");
        Equal(UdsStandardServices.RequestDownload(0x80001000, 0x20),
            "3400448000100000000020", "RequestDownload");
        Equal(UdsStandardServices.RequestUpload(0x80001000, 0x20),
            "3500448000100000000020", "RequestUpload");
        Equal(UdsStandardServices.TransferData(3, [0x10, 0x20]),
            "36031020", "TransferData block counter & payload");
        Equal(UdsStandardServices.TransferExit([0xAA, 0xBB]),
            "37AABB", "RequestTransferExit optional parameters");
        Equal(UdsStandardServices.Routine(1, 0xFF00, [0xAB]),
            "3101FF00AB", "StartRoutine and OEM routine ID");
        Equal(UdsStandardServices.SecurityAccess(1, []),
            "2701", "Request security seed");
        Equal(UdsStandardServices.SecurityAccess(2, [1, 2, 3]),
            "2702010203", "Send OEM key bytes without invented algorithm");
        Equal(UdsStandardServices.ClearDtc(0xFFFFFF),
            "14FFFFFF", "Clear DTC group");
        Equal(UdsStandardServices.ReadDtc(0x02, [0xFF]),
            "1902FF", "Read DTC by mask");
        Check(UdsStandardServices.RequireResponse([0x2E, 0xF1, 0x90, 0x01],
            [0x6E, 0xF1, 0x90]).SequenceEqual(new byte[] {0x6E, 0xF1, 0x90}),
            "UDS write DID positive acknowledgement correlated");
        Check(UdsStandardServices.RequireResponse([0x31, 1, 0xFF, 0x00],
            [0x71, 1, 0xFF, 0x00]).Length == 4,
            "UDS routine response correlated");
        Check(UdsStandardServices.RequireResponse([0x36, 3, 0xFF],
            [0x76, 3]).Length == 2,
            "UDS transfer counter response correlated");
        Reject<InvalidDataException>(() =>
            UdsStandardServices.RequireResponse([0x2E, 0xF1, 0x90, 0x01],
                [0x6E, 0xF1, 0x91]),
            "Wrong DID is rejected");
        Reject<InvalidDataException>(() =>
            UdsStandardServices.RequireResponse([0x31, 1, 0xFF, 0x00],
                [0x71, 1, 0xFF, 0x01]),
            "Wrong routine identifier rejected");
        Reject<InvalidDataException>(() =>
            UdsStandardServices.RequireResponse([0x36, 3, 0xFF],
                [0x76, 4]),
            "Wrong block counter rejected");
        Reject<IOException>(() =>
            UdsStandardServices.RequireResponse([0x2E, 0xF1, 0x90, 0x01],
                [0x7F, 0x2E, 0x33]),
            "True OEM security access denial honored");
        Reject<InvalidDataException>(() =>
            UdsStandardServices.RequireResponse([0x2E, 0xF1, 0x90],
                [0x7F, 0x22, 0x33]),
            "Misdirected negative response rejected");
        Reject<ArgumentOutOfRangeException>(() =>
            UdsStandardServices.ReadMemory(0xFFFF_FFFF, 2),
            "Address overflow rejected");
        Reject<ArgumentOutOfRangeException>(() =>
            UdsStandardServices.WriteMemory(0x100, new byte[0]),
            "Zero write bytes refused");
        Reject<ArgumentOutOfRangeException>(() =>
            UdsStandardServices.WriteDid(0xF190, new byte[0]),
            "Zero DID write bytes refused");
        Reject<ArgumentOutOfRangeException>(() =>
            UdsStandardServices.Routine(0, 0xFF00, []),
            "Invalid UDS routine control type rejected");
        Reject<ArgumentOutOfRangeException>(() =>
            UdsStandardServices.SecurityAccess(0, []),
            "Invalid security subfunction rejected");
        return checks;
    }
}
