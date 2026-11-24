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

        // Each NRC 0x78 opens a new P2* stage; the original overall
        // deadline and the initial P2 timer must never be reset.
        var budgets = new List<TimeSpan>();
        var staged = new Queue<byte[]>();
        staged.Enqueue(new byte[] {0x7F, 0x22, 0x78});
        staged.Enqueue(final);
        var stagedResult = UdsResponseSequence.AwaitFinalAsync(
            request,
            (budget, token) =>
            {
                budgets.Add(budget);
                return Task.FromResult(staged.Dequeue());
            },
            TimeSpan.FromSeconds(1), CancellationToken.None,
            p2Timeout: TimeSpan.FromMilliseconds(100),
            p2StarTimeout: TimeSpan.FromMilliseconds(500))
            .GetAwaiter().GetResult();
        Check(stagedResult.SequenceEqual(final) &&
            budgets.Count == 2 &&
            budgets[0] <= TimeSpan.FromMilliseconds(100) &&
            budgets[1] <= TimeSpan.FromMilliseconds(500) &&
            budgets[1] > budgets[0],
            "P2 and P2* per-stage receive budgets are distinct.");

        Reject<TimeoutException>(() =>
            UdsResponseSequence.AwaitFinalAsync(
                request, async (budget, token) =>
                {
                    await Task.Delay(40, token);
                    return final;
                },
                TimeSpan.FromMilliseconds(500), CancellationToken.None,
                p2Timeout: TimeSpan.FromMilliseconds(10))
                .GetAwaiter().GetResult(),
            "Late positive response cannot pass after P2.");

        var pendingCalls = 0;
        Reject<TimeoutException>(() =>
            UdsResponseSequence.AwaitFinalAsync(
                request, async (budget, token) =>
                {
                    pendingCalls++;
                    if (pendingCalls == 1)
                        return new byte[] {0x7F, 0x22, 0x78};
                    await Task.Delay(40, token);
                    return final;
                },
                TimeSpan.FromMilliseconds(500), CancellationToken.None,
                p2Timeout: TimeSpan.FromMilliseconds(100),
                p2StarTimeout: TimeSpan.FromMilliseconds(10))
                .GetAwaiter().GetResult(),
            "Late final response after NRC 0x78 cannot pass beyond P2*.");

        var pendingAgain = new Queue<byte[]>();
        pendingAgain.Enqueue(new byte[] {0x7F, 0x22, 0x78});
        pendingAgain.Enqueue(new byte[] {0x7F, 0x22, 0x78});
        pendingAgain.Enqueue(final);
        var positiveAfterWaits = UdsResponseSequence.AwaitFinalAsync(
            request, (budget, token) => Task.FromResult(pendingAgain.Dequeue()),
            TimeSpan.FromSeconds(1), CancellationToken.None,
            p2Timeout: TimeSpan.FromMilliseconds(100),
            p2StarTimeout: TimeSpan.FromMilliseconds(100))
            .GetAwaiter().GetResult();
        Check(positiveAfterWaits.SequenceEqual(final) && pendingAgain.Count == 0,
            "Each timely NRC 0x78 restarts P2* but not overall deadline.");

        Reject<ArgumentOutOfRangeException>(() =>
            UdsResponseSequence.AwaitFinalAsync(request,
                (budget, token) => Task.FromResult(final),
                TimeSpan.FromSeconds(1), CancellationToken.None,
                p2Timeout: TimeSpan.Zero).GetAwaiter().GetResult(),
            "Zero P2 is refused.");
        Reject<ArgumentOutOfRangeException>(() =>
            UdsResponseSequence.AwaitFinalAsync(request,
                (budget, token) => Task.FromResult(final),
                TimeSpan.FromSeconds(1), CancellationToken.None,
                p2StarTimeout: TimeSpan.FromMinutes(11)).GetAwaiter().GetResult(),
            "P2* exceeding ten minutes is refused.");

        Check(UdsResponseSequence.AwaitFinalAsync(request,
            (_, token) =>
            {
                token.ThrowIfCancellationRequested();
                return Task.FromResult(final);
            }, TimeSpan.FromSeconds(1), CancellationToken.None)
            .GetAwaiter().GetResult().SequenceEqual(final),
            "Direct timely response is accepted.");

        var overallCalls = 0;
        Reject<TimeoutException>(() =>
            UdsResponseSequence.AwaitFinalAsync(request,
                async (_, token) =>
                {
                    overallCalls++;
                    if (overallCalls == 1)
                        return new byte[] { 0x7F, 0x22, 0x78 };
                    await Task.Delay(120, token);
                    return final;
                }, TimeSpan.FromMilliseconds(60), CancellationToken.None)
                .GetAwaiter().GetResult(),
            "ResponsePending does not extend the overall deadline.");
        Check(overallCalls == 2, "Final receive attempted after pending response.");

        using var cancelledAfterRx = new CancellationTokenSource();
        Reject<OperationCanceledException>(() =>
            UdsResponseSequence.AwaitFinalAsync(request,
                (_, token) =>
                {
                    cancelledAfterRx.Cancel();
                    return Task.FromResult(final);
                }, TimeSpan.FromSeconds(1), cancelledAfterRx.Token)
                .GetAwaiter().GetResult(),
            "Cancellation after RX prevents publication of a completed transaction.");

        return checks;
    }
}
