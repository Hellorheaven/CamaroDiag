namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Offline ISO 14229 response-deadline regression tests.
/// Adds no OEM seed/key, ECU authorization, or physical flash capability.
/// </summary>
internal static class UdsResponseDeadlineSelfTests
{
    internal static int Run()
    {
        var checks = 0;
        void Check(bool result, string label)
        {
            checks++;
            if (!result)
                throw new InvalidOperationException("UDS response deadline: " + label);
        }
        void Reject<T>(Action work, string label) where T : Exception
        {
            var threw = false;
            try { work(); }
            catch (T) { threw = true; }
            Check(threw, label);
        }

        var request = new byte[] { 0x22, 0xF1, 0x90 };
        var final = new byte[] { 0x62, 0xF1, 0x90, 0x41 };
        var pending = new Queue<byte[]>();
        pending.Enqueue(new byte[] { 0x7F, 0x22, 0x78 });
        pending.Enqueue(final);
        var observed = UdsResponseSequence.AwaitFinalAsync(
            request, (remaining, token) =>
                Task.FromResult(pending.Dequeue()),
            TimeSpan.FromSeconds(1), CancellationToken.None)
            .GetAwaiter().GetResult();
        Check(observed.SequenceEqual(final) && pending.Count == 0,
            "NRC 0x78 is interim; a matching final UDS response completes.");

        Reject<IOException>(() =>
            UdsResponseSequence.AwaitFinalAsync(request,
                (remaining, token) => Task.FromResult(
                    new byte[] { 0x7F, 0x22, 0x31 }),
                TimeSpan.FromSeconds(1), CancellationToken.None)
                .GetAwaiter().GetResult(),
            "Final negative UDS response cannot be reported as success.");
        Reject<InvalidDataException>(() =>
            UdsResponseSequence.AwaitFinalAsync(request,
                (remaining, token) => Task.FromResult(
                    new byte[] { 0x63, 0xF1, 0x90, 0x41 }),
                TimeSpan.FromSeconds(1), CancellationToken.None)
                .GetAwaiter().GetResult(),
            "Positive response with another SID is rejected.");

        Reject<TimeoutException>(() =>
            UdsResponseSequence.AwaitFinalAsync(request,
                async (remaining, token) =>
                {
                    // Simulate a vendor driver that returns *after* its
                    // advised per-call deadline: never credit the late reply.
                    await Task.Delay(40, token);
                    return final;
                },
                TimeSpan.FromMilliseconds(10), CancellationToken.None)
                .GetAwaiter().GetResult(),
            "Late positive UDS response is refused after overall deadline.");

        using var cancelled = new CancellationTokenSource();
        cancelled.Cancel();
        var wasRead = false;
        Reject<OperationCanceledException>(() =>
            UdsResponseSequence.AwaitFinalAsync(request,
                (remaining, token) =>
                {
                    wasRead = true;
                    return Task.FromResult(final);
                },
                TimeSpan.FromSeconds(1), cancelled.Token)
                .GetAwaiter().GetResult(),
            "Pre-cancelled UDS transaction fails without reading ECU.");
        Check(!wasRead, "Cancelled transaction does not invoke receive.");
        return checks;
    }
}
