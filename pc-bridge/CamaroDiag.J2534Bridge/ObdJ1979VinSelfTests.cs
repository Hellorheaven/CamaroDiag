using System.Text;

namespace CamaroDiag.J2534Bridge;

/// <summary>Software-only SAE J1979 VIN response checks; no vehicle or bus.</summary>
internal static class ObdJ1979VinSelfTests
{
    internal static int Run()
    {
        var checks = 0;
        void Check(bool condition, string title)
        {
            checks++;
            if (!condition) throw new InvalidOperationException("J1979 VIN: " + title);
        }
        void Reject<T>(Action action, string title) where T : Exception
        {
            var threw = false;
            try { action(); }
            catch (T) { threw = true; }
            Check(threw, title);
        }

        const string vin = "1G1FF1R70C0100001";
        var answer = new byte[] { 0x49, 0x02, 0x01 }
            .Concat(Encoding.ASCII.GetBytes(vin)).ToArray();
        Check(ObdJ1979Vin.Request.SequenceEqual([0x09, 0x02]),
            "Mode 09 PID 02 request.");
        Check(ObdJ1979Vin.DecodeIsoTp(answer) == vin,
            "Reassembled 17-character VIN returned without invented characters.");
        Reject<InvalidDataException>(() =>
            ObdJ1979Vin.DecodeIsoTp(answer.AsSpan(0, 19)),
            "Truncated VIN rejected.");
        Reject<InvalidDataException>(() =>
            ObdJ1979Vin.DecodeIsoTp(answer.Concat(new byte[] {0}).ToArray()),
            "Unexpected padding after ISO-TP application payload rejected.");
        var wrongPid = answer.ToArray(); wrongPid[1] = 0x04;
        Reject<InvalidDataException>(() =>
            ObdJ1979Vin.DecodeIsoTp(wrongPid),
            "Non-VIN PID is rejected.");
        var wrongSequence = answer.ToArray(); wrongSequence[2] = 0x02;
        Reject<InvalidDataException>(() =>
            ObdJ1979Vin.DecodeIsoTp(wrongSequence),
            "Non-first VIN record index rejected.");
        var invalid = answer.ToArray(); invalid[3] = (byte)'I';
        Reject<InvalidDataException>(() =>
            ObdJ1979Vin.DecodeIsoTp(invalid),
            "VIN excludes I/O/Q.");
        var invalidAscii = answer.ToArray(); invalidAscii[3] = 0xFF;
        Reject<InvalidDataException>(() =>
            ObdJ1979Vin.DecodeIsoTp(invalidAscii),
            "Non-ASCII bytes rejected.");
        return checks;
    }
}
