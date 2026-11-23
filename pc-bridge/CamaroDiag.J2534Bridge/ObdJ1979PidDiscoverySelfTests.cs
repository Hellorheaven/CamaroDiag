namespace CamaroDiag.J2534Bridge;

/// <summary>Link-simulated SAE J1979 PID discovery; no J2534 DLL or real ECU.</summary>
internal static class ObdJ1979PidDiscoverySelfTests
{
    private sealed class FakeLink : IsoTpCanFdSession.ICanLink
    {
        internal readonly Queue<IsoTpCanFdSession.CanFrame> Responses = new();
        internal readonly List<byte[]> SentPayloads = new();

        public Task SendAsync(IsoTpCanFdSession.CanFrame frame,
            CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            if (frame.Id != 0x7E0 || frame.IsFd || frame.Data.Length != 8)
                throw new InvalidOperationException("Unexpected test CAN link identity.");
            var length = frame.Data[0];
            if (length == 0 || length > 7)
                throw new InvalidOperationException("Expected one ISO-TP single frame.");
            SentPayloads.Add(frame.Data.AsSpan(1, length).ToArray());
            return Task.CompletedTask;
        }

        public Task<IsoTpCanFdSession.CanFrame> ReceiveAsync(
            TimeSpan timeout, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            if (Responses.Count == 0)
                throw new TimeoutException("Mock ECU did not reply.");
            return Task.FromResult(Responses.Dequeue());
        }

        public Task WaitMinimumSeparationAsync(
            TimeSpan minimum, CancellationToken token) => Task.CompletedTask;

        internal void AddResponse(params byte[] application)
        {
            var frame = new byte[8];
            frame[0] = checked((byte)application.Length);
            application.CopyTo(frame, 1);
            Responses.Enqueue(new(0x7E8, false, frame));
        }
    }

    internal static int Run()
    {
        var count = 0;
        void Check(bool ok, string label)
        {
            count++;
            if (!ok) throw new InvalidOperationException("J1979 discovery: " + label);
        }
        void Reject<T>(Action action, string label) where T : Exception
        {
            bool threw = false;
            try { action(); }
            catch (T) { threw = true; }
            Check(threw, label);
        }
        static ObdJ1979PidDiscovery.Discovery Discover(FakeLink link,
            byte mode = 0x01, byte freezeFrame = 0) =>
            ObdJ1979PidDiscovery.DiscoverAsync(link,
                new(0x7E0, 0x7E8, 8, false),
                mode, freezeFrame, TimeSpan.FromSeconds(2),
                CancellationToken.None).GetAwaiter().GetResult();

        var cli = new[]
        {
            StandardProtocolCli.J1979PidDiscoveryCommand,
            "--adapter-index", "0", "--tx-id", "7E0", "--rx-id", "7E8",
            "--baud", "500000", "--j1979-mode", "2",
            "--freeze-frame", "1", "--timeout-ms", "2000"
        };
        var options = StandardProtocolCli.ParseJ1979PidDiscovery(cli);
        Check(options.AdapterIndex == 0 && options.Settings.TxId == 0x7E0 &&
            options.Settings.RxId == 0x7E8 && options.Mode == 2 &&
            options.FreezeFrame == 1 && !options.Extended,
            "Explicit physical CAN options parsed without driver.");
        Reject<ArgumentException>(() =>
            StandardProtocolCli.ParseJ1979PidDiscovery(
                cli.Concat(new[] { "--payload", "04" }).ToArray()),
            "PID discovery cannot smuggle a mutating raw payload.");
        Reject<ArgumentException>(() =>
            StandardProtocolCli.ParseJ1979PidDiscovery(
                new[] { StandardProtocolCli.J1979PidDiscoveryCommand, "--adapter-index", "0", "--tx-id", "FFFFFFFF", "--rx-id", "7E8", "--baud", "500000" }),
            "Out of range CAN ID rejected.");
        var modeOne = new[] {
            StandardProtocolCli.J1979PidDiscoveryCommand,
            "--adapter-index", "0", "--tx-id", "7E0", "--rx-id", "7E8",
            "--baud", "500000", "--freeze-frame", "1"
        };
        Reject<ArgumentException>(() =>
            StandardProtocolCli.ParseJ1979PidDiscovery(modeOne),
            "Mode 01 cannot request a freeze frame.");
        var twoPages = new FakeLink();
        twoPages.AddResponse(0x41, 0x00, 0x80, 0, 0, 1);
        twoPages.AddResponse(0x41, 0x20, 0x80, 0, 0, 0);
        var list = Discover(twoPages);
        Check(list.Pages.Count == 2 &&
            list.SupportedPids.SequenceEqual(new byte[] { 0x01, 0x20, 0x21 }),
            "Continuation bit fetches exactly two pages in order.");
        Check(twoPages.SentPayloads.Count == 2 &&
            twoPages.SentPayloads[0].SequenceEqual([0x01, 0x00]) &&
            twoPages.SentPayloads[1].SequenceEqual([0x01, 0x20]),
            "Requests sent through the real ISO-TP SendAsync path.");

        var frameMode = new FakeLink();
        frameMode.AddResponse(0x42, 0x00, 0x02, 0x80, 0, 0, 0);
        var freeze = Discover(frameMode, 0x02, 0x02);
        Check(freeze.FreezeFrame == 2 && freeze.Pages.Count == 1 &&
            frameMode.SentPayloads[0].SequenceEqual([0x02, 0x00, 0x02]),
            "Mode 02 frame number is explicitly emitted and checked.");

        var unsupported = new FakeLink();
        unsupported.AddResponse(0x7F, 0x01, 0x12);
        Reject<InvalidDataException>(() => Discover(unsupported),
            "Negative ECU response does not return false success.");

        var absent = new FakeLink();
        Reject<TimeoutException>(() => Discover(absent),
            "Silent ECU results in timeout, not empty supported PID set.");

        var malformed = new FakeLink();
        malformed.AddResponse(0x41, 0x20, 0, 0, 0, 0);
        Reject<InvalidDataException>(() => Discover(malformed),
            "Unexpected support base rejected.");

        using var stop = new CancellationTokenSource();
        stop.Cancel();
        var noSend = new FakeLink();
        Reject<OperationCanceledException>(() =>
            ObdJ1979PidDiscovery.DiscoverAsync(noSend,
                new(0x7E0, 0x7E8, 8, false),
                1, 0, TimeSpan.FromSeconds(2), stop.Token)
                .GetAwaiter().GetResult(),
            "Cancelled discovery aborts before transmission.");
        Check(noSend.SentPayloads.Count == 0, "No TX after cancellation.");
        Reject<ArgumentException>(() => Discover(new FakeLink(), 3),
            "Non-support mode rejected before transmission.");
        return count;
    }
}
