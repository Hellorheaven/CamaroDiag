namespace CamaroDiag.J2534Bridge;

/// <summary>ISO 14230-3 application correlation tests without ECU/VCI.</summary>
internal static class Kwp2000TransactionSelfTests
{
    internal static int Run()
    {
        var checks = 0;
        void Check(bool ok, string description)
        {
            checks++;
            if (!ok) throw new InvalidOperationException("KWP2000 transaction: " + description);
        }
        void Reject<T>(Action operation, string description) where T : Exception
        {
            var rejected = false;
            try { operation(); }
            catch (T) { rejected = true; }
            Check(rejected, description);
        }

        var positive = Kwp2000Transaction.Decode(
            [0x1A, 0x90], [0x5A, 0x90, 0x41]);
        Check(positive.Status == Kwp2000Transaction.Kind.Positive &&
            positive.Service == 0x1A &&
            positive.Data.SequenceEqual([0x90, 0x41]) &&
            positive.NegativeResponseCode == null,
            "Physical ECU response correlates service before data display.");

        var negative = Kwp2000Transaction.Decode(
            [0x27, 0x01], [0x7F, 0x27, 0x33]);
        Check(negative.Status == Kwp2000Transaction.Kind.Negative &&
            negative.NegativeResponseCode == 0x33,
            "OEM SecurityAccessDenied is not presented as a successful unlock.");

        var pending = Kwp2000Transaction.Decode(
            [0x31, 0x01, 0x00], [0x7F, 0x31, 0x78]);
        Check(pending.Status == Kwp2000Transaction.Kind.ResponsePending &&
            pending.NegativeResponseCode == 0x78,
            "ResponsePending has its own inconclusive status.");

        Reject<InvalidDataException>(() =>
            Kwp2000Transaction.Decode([0x27, 1], [0x68, 1]),
            "Wrong positive service must not confirm KWP unlock.");
        Reject<InvalidDataException>(() =>
            Kwp2000Transaction.Decode([0x27, 1], [0x7F, 0x31, 0x33]),
            "Negative answer for another KWP service is rejected.");
        Reject<InvalidDataException>(() =>
            Kwp2000Transaction.Decode([0x27, 1], [0x7F, 0x27]),
            "Truncated negative KWP response rejected.");
        Reject<InvalidDataException>(() =>
            Kwp2000Transaction.Decode([0x27, 1], []),
            "Silent ECU is not a positive reply.");
        Reject<ArgumentException>(() =>
            Kwp2000Transaction.Decode([], [0x50, 1]),
            "No transmitted SID means no meaningful correlation.");
        var parsed = StandardProtocolCli.ParseJ2534([
            StandardProtocolCli.PassThruCommand,
            "--adapter-index", "0",
            "--protocol-id", J2534StandardChannel.Iso14230.ToString(),
            "--baud", "10400",
            "--kwp-service", "1A90",
            "--kwp-tx-target", "16", "--kwp-tx-source", "241",
            "--kwp-rx-target", "241", "--kwp-rx-source", "16",
            "--kwp-validate", "true", "--read-frames", "3"
        ]);
        Check(parsed.KwpRequest is not null &&
            parsed.KwpRequest.SequenceEqual(new byte[] { 0x1A, 0x90 }) &&
            parsed.KwpRxTarget == 241 && parsed.KwpRxSource == 16,
            "J2534 CLI correlates KWP application SID after physical RX framing.");

        Reject<ArgumentException>(() =>
            StandardProtocolCli.ParseJ2534([
                StandardProtocolCli.PassThruCommand,
                "--adapter-index", "0",
                "--protocol-id", J2534StandardChannel.Iso14230.ToString(),
                "--baud", "10400", "--data", "1A90",
                "--kwp-validate", "true"
            ]), "Cannot validate application SID without KWP framing and target.");
        Reject<ArgumentException>(() =>
            StandardProtocolCli.ParseJ2534([
                StandardProtocolCli.PassThruCommand,
                "--adapter-index", "0",
                "--protocol-id", J2534StandardChannel.Iso14230.ToString(),
                "--baud", "10400",
                "--kwp-service", "1A90",
                "--kwp-tx-target", "16", "--kwp-tx-source", "241",
                "--kwp-validate", "true"
            ]), "Missing RX address cannot claim KWP response correlation.");

        return checks;
    }
}
