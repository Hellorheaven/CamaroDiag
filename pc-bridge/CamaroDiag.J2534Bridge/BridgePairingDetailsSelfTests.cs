namespace CamaroDiag.J2534Bridge;

internal static class BridgePairingDetailsSelfTests
{
    internal static int Run()
    {
        var checks = 0;
        void Assert(bool value, string message)
        {
            checks++;
            if (!value)
                throw new InvalidOperationException("Desktop pairing test failed: " + message);
        }
        static void MustReject<T>(Action call) where T : Exception
        {
            try { call(); }
            catch (T) { return; }
            throw new InvalidOperationException("Missing " + typeof(T).Name);
        }

        // Valid shape only: 32 random bytes encoded as 43 base64url chars
        // followed by the pinned TLS certificate fingerprint.
        var code = new string('T', 43) + "." + new string('A', 64);
        var text = BridgePairingDetails.FormatForAndroid("192.168.1.42", 35001, code);
        Assert(text.Contains("192.168.1.42", StringComparison.Ordinal) &&
               text.Contains("35001", StringComparison.Ordinal) &&
               text.Contains(code, StringComparison.Ordinal),
            "All three live Android settings copied verbatim");
        Assert(text.Contains("TLS", StringComparison.Ordinal),
            "Android instructions explain certificate-pinned transport");

        MustReject<ArgumentException>(() =>
            BridgePairingDetails.FormatForAndroid("8.8.8.8", 35001, code));
        checks++;
        MustReject<ArgumentException>(() =>
            BridgePairingDetails.FormatForAndroid("127.0.0.1", 35001, code));
        checks++;
        MustReject<ArgumentException>(() =>
            BridgePairingDetails.FormatForAndroid("192.168.1.2", 80, code));
        checks++;
        MustReject<ArgumentException>(() =>
            BridgePairingDetails.FormatForAndroid("192.168.1.2", 35001, "tokenOnly"));
        checks++;
        MustReject<ArgumentException>(() =>
            BridgePairingDetails.FormatForAndroid(
                "192.168.1.2", 35001, "session." + new string('Z', 64)));
        checks++;
        MustReject<ArgumentException>(() =>
            BridgePairingDetails.FormatForAndroid("0.0.0.0", 35001, code));
        checks++;
        foreach (var invalid in new[]
        {
            "short." + new string('A', 64),
            new string('T', 42) + "." + new string('A', 64),
            new string('T', 44) + "." + new string('A', 64),
            new string('T', 43) + ".\\n" + new string('A', 64),
            new string('T', 42) + "\\n." + new string('A', 64),
            new string('T', 42) + "+." + new string('A', 64),
            new string('T', 43) + "." + new string('A', 63)
        })
        {
            MustReject<ArgumentException>(() =>
                BridgePairingDetails.FormatForAndroid("192.168.1.42", 35001, invalid));
            checks++;
        }

        Console.WriteLine($"PASS: {checks} Android TLS pairing UI format tests.");
        return checks;
    }
}
