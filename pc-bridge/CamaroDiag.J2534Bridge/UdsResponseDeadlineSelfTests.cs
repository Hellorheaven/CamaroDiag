namespace CamaroDiag.J2534Bridge;

/// <summary>
/// ISO 14229 final response tests using a deliberately slow fake ECU link.
/// No actual vehicle, OEM security key or J2534 DLL is used.
/// </summary>
internal static class UdsResponseDeadlineSelfTests
{
    internal static int Run()
    {
        var checks = 0;
        void Check(bool success, string name)
        {
            checks++;
            if (!success)
                throw new InvalidOperationException("UDS response deadline: " + name);
        }
        void Reject<T>(Action action, string name) where T : Exception
        {
            var rejected = false;
            try { action(); }
            catch (T) { rejected = true; }
            Check(rejected, name);
        }
        var request = new byte[] { 0x22, 0xF1, 0x90 };

        var inTime = UdsResponseSequence.AwaitFinalAsync(
            request,
            (_, token) =>
            {
                token.ThrowIfCancellationRequested();
                return Task.FromResult(new byte[] { 0x62, 0xF1, 0x90, 0x41 });
            },
            TimeSpan.FromMilliseconds(100), CancellationToken.None)
            .GetAwaiter().GetResult();
        Check(inTime.SequenceEqual(new byte[] { 0x62, 0xF1, 0x90, 0x41 }),
            "Correct timely response is still accepted.");

        Reject<TimeoutException>(() =>
            UdsResponseSequence.AwaitFinalAsync(
                request,
                async (_, token) =>
                {
                    await Task.Delay(45, token);
                    return new byte[] { 0x62, 0xF1, 0x90, 0x41 };
                },
                TimeSpan.FromMilliseconds(10), CancellationToken.None)
                .GetAwaiter().GetResult(),
            "Vendor driver late positive response cannot become success.");

        var call = 0;
        Reject<TimeoutException>(() =>
            UdsResponseSequence.AwaitFinalAsync(
                request,
                async (_, token) =>
                {
                    call++;
                    if (call == 1)
                        return new byte[] { 0x7F, 0x22, 0x78 };
                    await Task.Delay(45, token);
                    return new byte[] { 0x62, 0xF1, 0x90, 0x41 };
                },
                TimeSpan.FromMilliseconds(10), CancellationToken.None)
                .GetAwaiter().GetResult(),
            "ResponsePending does not reset the overall UDS deadline.");
        Check(call == 2, "Late final was attempted after correlated pending response.");

        using var cancelled = new CancellationTokenSource();
        Reject<OperationCanceledException>(() =>
            UdsResponseSequence.AwaitFinalAsync(
                request,
                (_, token) =>
                {
                    cancelled.Cancel();
                    return Task.FromResult(new byte[] { 0x62, 0xF1, 0x90 });
                },
                TimeSpan.FromSeconds(1), cancelled.Token)
                .GetAwaiter().GetResult(),
            "Cancellation after RX prevents an apparently completed transaction.");
        return checks;
    }
}
