namespace CamaroDiag.J2534Bridge;

/// <summary>
/// SAE J1979 classic Mode 06 ISO 15765-4 CAN on-board monitor records.
/// Availability bitmaps and individual test records use different response
/// layouts. The unit-and-scaling ID is retained without inventing a unit
/// conversion from the SAE J1979DA table.
/// </summary>
internal static class ObdJ1979Mode06
{
    internal sealed record Availability(byte BaseId, IReadOnlyList<byte> MonitorIds,
        bool HasNextPage);
    internal sealed record Test(byte MonitorId, byte TestId, byte UnitScalingId,
        ushort Value, ushort Minimum, ushort Maximum);

    internal static Availability DecodeAvailability(ReadOnlySpan<byte> request,
        ReadOnlySpan<byte> response)
    {
        if (request.Length != 2 || request[0] != 0x06 ||
            request[1] > 0xE0 || request[1] % 0x20 != 0)
            throw new ArgumentException("Mode 06 availability requires MID 00,20,..,E0.", nameof(request));
        if (response.Length != 6 || response[0] != 0x46 ||
            response[1] != request[1])
            throw new InvalidDataException("Mode 06 availability response requires MID echo and four bytes.");
        var ids = new List<byte>(32);
        for (var bit = 0; bit < 32; bit++)
        {
            if ((response[2 + bit / 8] & (0x80 >> (bit % 8))) == 0)
                continue;
            var id = request[1] + bit + 1;
            if (id > 0xFF)
                throw new InvalidDataException("Mode 06 MID bitmap exceeds FF.");
            ids.Add((byte)id);
        }
        return new(request[1], ids,
            request[1] < 0xE0 && ids.Contains((byte)(request[1] + 0x20)));
    }

    internal static IReadOnlyList<Test> DecodeTests(ReadOnlySpan<byte> request,
        ReadOnlySpan<byte> response)
    {
        if (request.Length != 2 || request[0] != 0x06)
            throw new ArgumentException("Mode 06 test request requires one MID.", nameof(request));
        if (request[1] % 0x20 == 0)
            throw new ArgumentException("Availability MID is not a monitor test.", nameof(request));
        if (response.Length < 10 || response[0] != 0x46 ||
            (response.Length - 1) % 9 != 0)
            throw new InvalidDataException("Mode 06 CAN tests require nine bytes per test record.");

        var results = new List<Test>((response.Length - 1) / 9);
        for (var offset = 1; offset < response.Length; offset += 9)
        {
            if (response[offset] != request[1])
                throw new InvalidDataException("Mode 06 test MID differs from requested MID.");
            static ushort Word(ReadOnlySpan<byte> buffer) =>
                (ushort)(buffer[0] << 8 | buffer[1]);
            results.Add(new Test(response[offset], response[offset + 1],
                response[offset + 2], Word(response.Slice(offset + 3, 2)),
                Word(response.Slice(offset + 5, 2)),
                Word(response.Slice(offset + 7, 2))));
        }
        return results;
    }
}
