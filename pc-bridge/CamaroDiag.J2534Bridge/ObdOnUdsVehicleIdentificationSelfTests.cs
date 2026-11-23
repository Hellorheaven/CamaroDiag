using System.Text;

namespace CamaroDiag.J2534Bridge;

/// <summary>Hardware-free UDS F802 VIN correlation/format vectors.</summary>
internal static class ObdOnUdsVehicleIdentificationSelfTests
{
    internal static int Run()
    {
        int checks = 0;
        void Check(bool ok, string label)
        {
            checks++;
            if (!ok)
                throw new InvalidOperationException("OBDonUDS F802: " + label);
        }
        void Reject<T>(Action action, string label) where T : Exception
        {
            bool raised = false;
            try { action(); }
            catch (T) { raised = true; }
            Check(raised, label);
        }
        const string vin = "1G1FF1R70C0100001";
        var positive = new byte[] { 0x62, 0xF8, 0x02 }
            .Concat(Encoding.ASCII.GetBytes(vin)).ToArray();
        Check(ObdOnUdsVehicleIdentification.ReadVin()
            .SequenceEqual(new byte[] {0x22, 0xF8, 0x02}),
            "DID F802 uses UDS 22, not legacy J1979 09 02.");
        Check(ObdOnUdsVehicleIdentification.DecodeVin(positive) == vin,
            "Positive F802 VIN retains its exact seventeen characters.");
        Reject<InvalidDataException>(() =>
            ObdOnUdsVehicleIdentification.DecodeVin(positive[..^1]),
            "Truncated VIN rejected.");
        Reject<InvalidDataException>(() =>
            ObdOnUdsVehicleIdentification.DecodeVin(
                positive.Concat(new byte[] { 0 }).ToArray()),
            "Extra payload byte rejected.");
        var incorrect = positive.ToArray();
        incorrect[2] = 0x04;
        Reject<InvalidDataException>(() =>
            ObdOnUdsVehicleIdentification.DecodeVin(incorrect),
            "A different F8xx InfoType cannot impersonate VIN.");
        var badLetter = positive.ToArray();
        badLetter[3] = (byte)'O';
        Reject<InvalidDataException>(() =>
            ObdOnUdsVehicleIdentification.DecodeVin(badLetter),
            "Disallowed VIN letter O is rejected.");
        var badAscii = positive.ToArray();
        badAscii[3] = 0xFF;
        Reject<InvalidDataException>(() =>
            ObdOnUdsVehicleIdentification.DecodeVin(badAscii),
            "Non-ASCII F802 data rejected.");
        Reject<InvalidDataException>(() =>
            ObdOnUdsVehicleIdentification.DecodeVin([0x7F, 0x22, 0x31]),
            "UDS negative response must never be parsed as VIN.");
        return checks;
    }
}
