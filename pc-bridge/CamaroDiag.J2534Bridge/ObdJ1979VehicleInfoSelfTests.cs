using System.Text;

namespace CamaroDiag.J2534Bridge;

/// <summary>Mode 09 CALID/CVN/InfoType vectors on CAN ISO-TP application payloads.</summary>
internal static class ObdJ1979VehicleInfoSelfTests
{
    internal static int Run()
    {
        var checkedCount = 0;
        void Check(bool success, string name)
        {
            checkedCount++;
            if (!success)
                throw new InvalidOperationException("J1979 Mode 09: " + name);
        }
        void Reject<T>(Action action, string name) where T : Exception
        {
            var thrown = false;
            try { action(); }
            catch (T) { thrown = true; }
            Check(thrown, name);
        }
        var available = ObdJ1979VehicleInfo.DecodeSupported(
            [0x49, 0x00, 0x54, 0, 0, 0x01]);
        Check(available.InfoTypes.SequenceEqual(new byte[] { 0x02, 0x04, 0x06, 0x20 }) &&
            available.HasNextPage,
            "Mode 09 bitmap enumerates InfoTypes without invented availability.");
        var noNext = ObdJ1979VehicleInfo.DecodeSupported(
            [0x49, 0x00, 0x40, 0, 0, 0]);
        Check(noNext.InfoTypes.SequenceEqual(new byte[] { 2 }) &&
            !noNext.HasNextPage,
            "Mode 09 bitmap has no extension unless InfoType 20 is announced.");

        const string first = "ECU-CAL-ALPHA001";
        const string second = "ECU-CAL-BETA0002";
        var ids = new byte[] { 0x49, 0x04, 0x02 }
            .Concat(Encoding.ASCII.GetBytes(first))
            .Concat(Encoding.ASCII.GetBytes(second)).ToArray();
        Check(first.Length == 16 && second.Length == 16,
            "Test CALID strings fit one 16-byte CAN record.");
        var decodedIds = ObdJ1979VehicleInfo.DecodeCalibrationIds(ids);
        Check(decodedIds.Identifiers.SequenceEqual(new[] { first, second }),
            "Two CALID records must preserve order and exact ASCII values.");
        var padded = new byte[] { 0x49, 0x04, 0x01 }
            .Concat(Encoding.ASCII.GetBytes("CAL-A"))
            .Concat(new byte[11]).ToArray();
        Check(ObdJ1979VehicleInfo.DecodeCalibrationIds(padded)
            .Identifiers.Single() == "CAL-A",
            "Only trailing NUL padding may be removed.");

        var cvns = ObdJ1979VehicleInfo.DecodeCvns(
            [0x49, 0x06, 0x02, 0x01, 0x02, 0x03, 0x04, 0xAB, 0xCD, 0xEF, 0x10]);
        Check(cvns.HexValues.SequenceEqual(new[] { "01020304", "ABCDEF10" }),
            "CVN remains four raw bytes per record; no OEM proof inferred.");
        Check(ObdJ1979VehicleInfo.DecodeCalibrationIds(
                [0x49, 0x04, 0x00]).Identifiers.Count == 0,
            "Positive empty CALID list is not invented.");
        Reject<InvalidDataException>(() =>
            ObdJ1979VehicleInfo.DecodeCvns([0x49, 0x06, 0x02, 1, 2, 3, 4]),
            "Declared CVN count must match exactly.");
        Reject<InvalidDataException>(() =>
            ObdJ1979VehicleInfo.DecodeCalibrationIds(
                [0x49, 0x04, 0x01, 0x41, 0x42]),
            "Truncated CALID is rejected.");
        Reject<InvalidDataException>(() =>
            ObdJ1979VehicleInfo.DecodeCvns([0x49, 0x04, 0x00]),
            "Wrong InfoType does not become successful CVN read.");
        Reject<InvalidDataException>(() =>
            ObdJ1979VehicleInfo.DecodeSupported([0x49, 0x00, 0, 0, 0]),
            "Truncated InfoType bitmap rejected.");
        var invalid = padded.ToArray(); invalid[3] = 0;
        invalid[4] = (byte)'A';
        Reject<InvalidDataException>(() =>
            ObdJ1979VehicleInfo.DecodeCalibrationIds(invalid),
            "CALID NUL padding must not hide nonzero subsequent bytes.");
        var name = new byte[23];
        name[0] = 0x49;
        name[1] = 0x0A;
        name[2] = 1;
        "ECM"u8.CopyTo(name.AsSpan(3));
        "-EngineControl"u8.CopyTo(name.AsSpan(7));
        var interpreted = ObdJ1979Interpretation.Interpret([0x09, 0x0A], name);
        Check(interpreted.Positive && interpreted.Description == "SAE J1979 ECU=ECM-EngineControl",
            "ECU name with an independently padded acronym reaches CLI interpretation.");
        name[6] = (byte)'1';
        Check(ObdJ1979VehicleInfo.DecodeEcuName(name) == "ECM1-EngineControl",
            "Fourth acronym character is retained.");
        foreach (var (index, value) in new (int, byte)[] {
            (2, 0), (2, 2), (7, (byte)'/'), (4, 0), (11, 0), (10, 0x80)
        })
        {
            var malformed = name.ToArray();
            malformed[index] = value;
            Reject<InvalidDataException>(() => ObdJ1979VehicleInfo.DecodeEcuName(malformed),
                "Invalid ECU name count, delimiter, padding or character.");
        }
        Reject<InvalidDataException>(() => ObdJ1979VehicleInfo.DecodeEcuName(name[..^1]),
            "Incomplete 20-byte ECU name record.");
        return checkedCount;
    }
}
