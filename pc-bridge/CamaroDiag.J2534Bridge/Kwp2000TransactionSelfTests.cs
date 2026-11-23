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

        var manufacturerService = Kwp2000Transaction.Decode(
            [0xC1, 0xAA], [0x01, 0xAA]);
        Check(manufacturerService.Status == Kwp2000Transaction.Kind.Positive &&
            manufacturerService.Service == 0xC1,
            "Optional parser has no artificial manufacturer SID whitelist.");

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


        var echoed = Kwp2000Transaction.Decode(
            [0x1A, 0x90], [0x5A, 0x90, 0x41], echoedRequestBytes: 1);
        Check(echoed.Status == Kwp2000Transaction.Kind.Positive &&
            echoed.Data.SequenceEqual(new byte[] { 0x90, 0x41 }),
            "Explicit KWP identifier echo is correlated on a positive reply.");
        Reject<InvalidDataException>(() =>
            Kwp2000Transaction.Decode(
                [0x1A, 0x90], [0x5A, 0x91, 0x41], echoedRequestBytes: 1),
            "A different local identifier may not complete an active KWP request.");
        Reject<InvalidDataException>(() =>
            Kwp2000Transaction.Decode(
                [0x1A, 0x90], [0x5A], echoedRequestBytes: 1),
            "Truncated echoed KWP identifier is not a final success.");
        Reject<ArgumentOutOfRangeException>(() =>
            Kwp2000Transaction.Decode(
                [0x1A, 0x90], [0x5A, 0x90], echoedRequestBytes: 2),
            "Echo cannot exceed number of outgoing request parameters.");
        Reject<ArgumentOutOfRangeException>(() =>
            Kwp2000Transaction.Decode(
                [0x1A, 0x90], [0x5A, 0x90], echoedRequestBytes: -1),
            "Negative echoed-byte count rejected.");
        Check(Kwp2000Transaction.Decode(
                [0x27, 0x01], [0x7F, 0x27, 0x78], echoedRequestBytes: 1).Status ==
                Kwp2000Transaction.Kind.ResponsePending,
            "NRC ResponsePending remains valid without a positive echo.");

        var echoOptions = StandardProtocolCli.ParseJ2534([
            StandardProtocolCli.PassThruCommand,
            "--adapter-index", "0",
            "--protocol-id", J2534StandardChannel.Iso14230.ToString(),
            "--baud", "10400",
            "--kwp-service", "1A90", "--kwp-tx-target", "16",
            "--kwp-tx-source", "241", "--kwp-rx-target", "241",
            "--kwp-rx-source", "16", "--kwp-validate", "true",
            "--kwp-echo-bytes", "1", "--read-frames", "3"
        ]);
        Check(echoOptions.KwpEchoBytes == 1 &&
            echoOptions.KwpRequest!.SequenceEqual([0x1A, 0x90]),
            "Explicit echo option reaches the real J2534 KWP reply handler.");
        Reject<ArgumentException>(() =>
            StandardProtocolCli.ParseJ2534([
                StandardProtocolCli.PassThruCommand,
                "--adapter-index", "0",
                "--protocol-id", J2534StandardChannel.Iso14230.ToString(),
                "--baud", "10400", "--data", "1A90",
                "--kwp-echo-bytes", "1"
            ]),
            "Raw frames cannot claim a KWP request-parameter echo.");
        Reject<ArgumentException>(() =>
            StandardProtocolCli.ParseJ2534([
                StandardProtocolCli.PassThruCommand,
                "--adapter-index", "0",
                "--protocol-id", J2534StandardChannel.Iso14230.ToString(),
                "--baud", "10400", "--kwp-service", "1A90",
                "--kwp-tx-target", "16", "--kwp-tx-source", "241",
                "--kwp-rx-target", "241", "--kwp-rx-source", "16",
                "--kwp-validate", "true", "--kwp-echo-bytes", "2",
                "--read-frames", "3"
            ]),
            "KWP echo count cannot exceed number of request parameters.");

        return checks;
    }
}
