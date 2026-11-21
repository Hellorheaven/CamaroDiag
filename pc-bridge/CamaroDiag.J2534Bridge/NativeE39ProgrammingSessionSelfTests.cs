namespace CamaroDiag.J2534Bridge;

internal static class NativeE39ProgrammingSessionSelfTests
{
    private sealed class FakeTransport : NativeE39ProgrammingSession.ITransport
    {
        internal readonly List<string> Trace = [];
        internal byte[] SeedReply = [0x67, 0x01, 0x12, 0x34];
        internal byte[] SessionReply = [0x50, 0x02];
        internal byte[] CommunicationReply = [0x68];
        internal byte[] KeyReply = [0x67, 0x02];
        internal byte[] ProgrammingReply = [0xE5];

        public Task<byte[]> ExchangeAsync(byte[] request, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Trace.Add("E:" + Convert.ToHexString(request));
            byte[] reply = request[0] switch
            {
                0x10 => SessionReply,
                0x28 => CommunicationReply,
                0x27 when request[1] == 1 => SeedReply,
                0x27 when request[1] == 2 => KeyReply,
                0xA5 => ProgrammingReply,
                _ => [0x7F, request[0], 0x11]
            };
            return Task.FromResult(reply);
        }

        public Task SendAsync(byte[] request, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Trace.Add("S:" + Convert.ToHexString(request));
            return Task.CompletedTask;
        }

        public Task DelayAsync(TimeSpan duration, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Trace.Add("D:" + (int)duration.TotalMilliseconds);
            return Task.CompletedTask;
        }
    }

    internal static int Run()
    {
        const string vin = "1G1FA1RXXR0123456";
        var identity = new NativeGmlanIdentification.Identity(
            vin, 12664221, 0xC1, true, "Live read identity");
        var q = new NativeE39ProgrammingSession.Qualification(
            vin, 12664221, true, true, true);
        var checks = 0;
        void Assert(bool pass, string reason)
        {
            checks++;
            if (!pass)
                throw new InvalidOperationException("Native E39 session self-test failed: " + reason);
        }
        static void Reject<T>(Action execute) where T : Exception
        {
            try { execute(); }
            catch (T) { return; }
            throw new InvalidOperationException("Expected " + typeof(T).Name);
        }
        static NativeE39ProgrammingSession.Result Prepare(
            FakeTransport mock, NativeGmlanIdentification.Identity i,
            NativeE39ProgrammingSession.Qualification policy,
            CancellationToken token = default) =>
            NativeE39ProgrammingSession.PrepareAsync(mock, i, policy, token)
                .GetAwaiter().GetResult();

        var normal = new FakeTransport();
        var result = Prepare(normal, identity, q);
        Assert(result.InProgrammingMode && result.SecurityGranted &&
               result.OsId == identity.OperatingSystemId,
               "Explicitly attested ECU session enters programming mode");
        Assert(normal.Trace.SequenceEqual(new[]
        {
            "E:1002", "E:28", "S:3E", "E:2701", "E:2702BD17",
            "E:A501", "S:3E", "D:2000", "S:A503", "D:300"
        }), "GM session/security A5 stages occur in required order");

        var granted = new FakeTransport { SeedReply = [0x67, 0x01, 0, 0] };
        Prepare(granted, identity, q);
        Assert(!granted.Trace.Any(x => x.StartsWith("E:2702", StringComparison.Ordinal)),
               "Zero seed means already unlocked and forbids sending another key");

        var ignoredComm = new FakeTransport { CommunicationReply = [0x7F, 0x28, 0x12] };
        Prepare(ignoredComm, identity, q);
        Assert(ignoredComm.Trace.Contains("S:A503"),
               "Unsupported optional communication control does not stop safe session");

        var unqualified = new FakeTransport();
        Reject<UnauthorizedAccessException>(() =>
            Prepare(unqualified, identity, q with { RecoveryProcedureProven = false }));
        Assert(unqualified.Trace.Count == 0, "No bus traffic without proven bench recovery");

        var wrongVin = new FakeTransport();
        Reject<UnauthorizedAccessException>(() =>
            Prepare(wrongVin, identity, q with { ExpectedVin = "1G1FA1RXXR0123457" }));
        Assert(wrongVin.Trace.Count == 0, "VIN mismatch is rejected before ECU traffic");

        var notPhysical = new FakeTransport();
        Reject<UnauthorizedAccessException>(() =>
            Prepare(notPhysical, identity with { PhysicalRead = false }, q));
        Assert(notPhysical.Trace.Count == 0, "Synthetic ECU identity cannot authorize physical session");

        var badSession = new FakeTransport { SessionReply = [0x7F, 0x10, 0x22] };
        Reject<IOException>(() => Prepare(badSession, identity, q));
        Assert(badSession.Trace.Count == 1, "Programming session refusal halts immediately");

        var lockout = new FakeTransport { SeedReply = [0x7F, 0x27, 0x37] };
        Reject<IOException>(() => Prepare(lockout, identity, q));
        Assert(!lockout.Trace.Any(x => x.StartsWith("E:A5", StringComparison.Ordinal)),
               "Security lockout does not trigger brute force or ECU programming");

        var invalidKey = new FakeTransport { KeyReply = [0x7F, 0x27, 0x35] };
        Reject<IOException>(() => Prepare(invalidKey, identity, q));
        Assert(!invalidKey.Trace.Any(x => x.StartsWith("E:A5", StringComparison.Ordinal)),
               "Invalid key stops without trying a different algorithm");

        var badA5 = new FakeTransport { ProgrammingReply = [0x7F, 0xA5, 0x22] };
        Reject<IOException>(() => Prepare(badA5, identity, q));
        Assert(!badA5.Trace.Contains("S:A503"),
               "Denied A5/01 never sends the final transition");

        var abort = new FakeTransport();
        using (var tokenSource = new CancellationTokenSource())
        {
            tokenSource.Cancel();
            Reject<OperationCanceledException>(() =>
                Prepare(abort, identity, q, tokenSource.Token));
        }
        Assert(abort.Trace.Count == 0, "Cancelled request issues no programming commands");

        Console.WriteLine($"PASS: {checks} native E39 programming-session self-tests.");
        return checks;
    }
}
