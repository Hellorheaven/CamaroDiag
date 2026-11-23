using System.Text;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Selected ISO 15765-4 CAN SAE J1979 Mode 09 vehicle information messages.
/// The caller provides the reassembled diagnostic application payload, not
/// individual CAN packets. Legacy J1850/K-Line message-count layouts and the
/// distinct SAE J1979-2 DID mapping are not handled here.
/// </summary>
internal static class ObdJ1979VehicleInfo
{
    internal sealed record Supported(IReadOnlyList<byte> InfoTypes, bool HasNextPage);
    internal sealed record CalibrationIds(IReadOnlyList<string> Identifiers);
    internal sealed record CalibrationVerificationNumbers(IReadOnlyList<string> HexValues);

    internal static Supported DecodeSupported(ReadOnlySpan<byte> response)
    {
        // 49 00 [4 information type availability bytes]
        if (response.Length != 6 || response[0] != 0x49 || response[1] != 0x00)
            throw new InvalidDataException("Mode 09 information availability must have four bytes.");
        var supported = new List<byte>(32);
        for (var bit = 0; bit < 32; bit++)
            if ((response[2 + bit / 8] & (0x80 >> (bit % 8))) != 0)
                supported.Add(checked((byte)(bit + 1)));
        return new(supported, supported.Contains(0x20));
    }

    internal static CalibrationIds DecodeCalibrationIds(ReadOnlySpan<byte> response)
    {
        // CAN record 49 04 count [count * 16 ASCII bytes].
        var count = ReadCount(response, 0x04, 16);
        var identifiers = new List<string>(count);
        for (var i = 0; i < count; i++)
        {
            var bytes = response.Slice(3 + i * 16, 16);
            var padding = false;
            foreach (var c in bytes)
            {
                if (c == 0) padding = true;
                else if (padding || c is < 0x20 or > 0x7E)
                    throw new InvalidDataException("Invalid calibration ID ASCII or padding.");
            }
            var value = Encoding.ASCII.GetString(bytes).TrimEnd('\0');
            if (value.Length == 0)
                throw new InvalidDataException("CALID must not be an empty data item.");
            identifiers.Add(value);
        }
        return new(identifiers);
    }

    internal static CalibrationVerificationNumbers DecodeCvns(ReadOnlySpan<byte> response)
    {
        // CAN record 49 06 count [count * 4-byte CVN values].
        // Never mistake the CVN for an OEM signature or authenticated proof
        // that a calibration is the expected manufacturer image.
        var count = ReadCount(response, 0x06, 4);
        var values = new List<string>(count);
        for (var i = 0; i < count; i++)
            values.Add(Convert.ToHexString(response.Slice(3 + i * 4, 4)));
        return new(values);
    }

    private static int ReadCount(ReadOnlySpan<byte> reply, byte infoType, int width)
    {
        if (reply.Length < 3 || reply[0] != 0x49 ||
            reply[1] != infoType)
            throw new InvalidDataException("Incorrect Mode 09 response info type.");
        var count = reply[2];
        if (reply.Length != 3 + count * width)
            throw new InvalidDataException("Mode 09 item count does not match data length.");
        return count;
    }
}
