namespace CamaroDiag.J2534Bridge;

/// <summary>Observe emissions ECU response headers on one documented legacy
/// transport. This is a bounded discovery, not proof of the complete network.
/// Never select a source silently or infer a module from its numeric address.</summary>
internal static class ObdJ1979LegacyDiscovery
{
    internal sealed record Source(string ResponseSource, string Status, string? Detail,
        IReadOnlyList<ObdJ1979LegacyScan.Evidence> Records);
    internal sealed record Result(string RequestHex, string TxHex, IReadOnlyList<Source> Sources)
    {
        public bool HasVerifiedSource => Sources.Any(x => x.Status == "PASSED");
    }

    internal static Result Run(Func<byte[], int, ObdLegacyJ2534Transport.Exchange> exchange, Action requireActive,
        int timeoutMs = 1800)
    {
        if (timeoutMs is < 1 or > 4000) throw new ArgumentOutOfRangeException(nameof(timeoutMs));
        requireActive();
        var result = exchange([1, 0], timeoutMs);
        requireActive();
        var rows = result.Replies.GroupBy(x => x.Source).OrderBy(g => g.Key).Select(group =>
        {
            var evidence = group.Select(packet => new ObdJ1979LegacyScan.Evidence(packet.Source.ToString("X2"),
                packet.RawHex, Convert.ToHexString(packet.Payload), packet.Timestamp,
                packet.InFrameResponseHex)).ToArray();
            var payloads = group.Select(p => Convert.ToHexString(p.Payload)).Distinct().ToArray();
            var allPositive = group.All(packet => packet.Payload.Length == 6 &&
                packet.Payload[0] == 0x41 && packet.Payload[1] == 0);
            var uniquePositive = allPositive && payloads.Length == 1;
            var negative = group.All(packet => packet.Payload.Length == 3 &&
                packet.Payload[0] == 0x7F && packet.Payload[1] == 1 && packet.Payload[2] != 0);
            return new Source(group.Key.ToString("X2"), uniquePositive ? "PASSED" : negative ? "NOT_TESTED" : "FAILED",
                uniquePositive ? payloads[0][4..] : negative ? "NEGATIVE_RESPONSE" : "CONFLICTING_OR_MALFORMED_RESPONSE",
                evidence);
        }).ToArray();
        return new("0100", result.TxHex, rows);
    }
}
