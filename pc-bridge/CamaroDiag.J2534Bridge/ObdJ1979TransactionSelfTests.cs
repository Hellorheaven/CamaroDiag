namespace CamaroDiag.J2534Bridge;

/// <summary>Software-only SAE J1979 codec tests; no VCI or vehicle exercised.</summary>
internal static class ObdJ1979TransactionSelfTests
{
    internal static int Run()
    {
        var count = 0;
        void Check(bool ok, string title)
        {
            count++;
            if (!ok) throw new InvalidOperationException("J1979: " + title);
        }
        void Reject<T>(Action action, string title) where T : Exception
        {
            var rejected = false;
            try { action(); }
            catch (T) { rejected = true; }
            Check(rejected, title);
        }

        for (byte service = 1; service <= 0x0A; service++)
        {
            var request = ObdJ1979Transaction.Request(service, [0x00]);
            Check(request.SequenceEqual(new byte[] { service, 0 }),
                "Every standard service is encodable: " + service);
            var reply = ObdJ1979Transaction.Decode(request,
                [(byte)(service + 0x40), 0x00, 0x55], true);
            Check(reply.Kind == ObdJ1979Transaction.ReplyKind.Positive &&
                reply.Service == service && reply.Data.SequenceEqual([0, 0x55]),
                "Positive response and echoed identifier: " + service);
            var negative = ObdJ1979Transaction.Decode(request,
                [0x7F, service, 0x12]);
            Check(negative.Kind == ObdJ1979Transaction.ReplyKind.Negative &&
                negative.NegativeResponseCode == 0x12 &&
                negative.Data.Length == 0,
                "Negative response does not become success: " + service);
        }

        var clear = ObdJ1979Transaction.Request(0x04, []);
        Check(clear.SequenceEqual([0x04]) &&
            ObdJ1979Transaction.Decode(clear, [0x44]).Kind ==
                ObdJ1979Transaction.ReplyKind.Positive,
            "Mode 04 command and acknowledgement supported without an invented SID block.");
        Reject<InvalidDataException>(() =>
            ObdJ1979Transaction.Decode([0x01, 0x0C], [0x41, 0x0D, 0x00], true),
            "PID response from a different request is rejected.");
        Reject<InvalidDataException>(() =>
            ObdJ1979Transaction.Decode([0x04], [0x43]),
            "Clear response cannot be confused with a DTC read.");
        Reject<InvalidDataException>(() =>
            ObdJ1979Transaction.Decode([0x04], [0x7F, 0x03, 0x12]),
            "Negative response for another service rejected.");
        Reject<InvalidDataException>(() =>
            ObdJ1979Transaction.Decode([0x04], [0x7F, 0x04]),
            "Truncated NRC rejected.");
        Reject<InvalidDataException>(() =>
            ObdJ1979Transaction.Decode([0x04], [0x7F, 0x04, 0]),
            "Zero NRC rejected.");
        Reject<InvalidDataException>(() =>
            ObdJ1979Transaction.Decode([0x01, 0x00], [0x41], true),
            "Missing echoed PID rejected.");
        Reject<ArgumentOutOfRangeException>(() =>
            ObdJ1979Transaction.Request(0x0B, []),
            "Non-J1979 service rejected.");
        return count;
    }
}
