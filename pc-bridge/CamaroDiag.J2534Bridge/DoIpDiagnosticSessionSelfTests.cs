using System.Net;
using System.Net.Sockets;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// ISO 13400 loopback gateway exercises real TCP framing but no VCI, LAN,
/// OEM server or ECU. No mutation is performed on a vehicle.
/// </summary>
internal static class DoIpDiagnosticSessionSelfTests
{
    private const ushort Tester = 0x0E80;
    private const ushort Ecu = 0x1234;
    private static readonly byte[] Request = [0x22, 0xF1, 0x90];
    private static readonly byte[] Positive = [0x62, 0xF1, 0x90, 0x41];
    private static readonly byte[] Pending = [0x7F, 0x22, 0x78];

    private static async Task<DoIpCodec.Message> ReadAsync(Stream stream)
    {
        var header = new byte[8];
        await stream.ReadExactlyAsync(header);
        var size = DoIpCodec.ReadPayloadLength(header);
        var frame = new byte[8 + size];
        header.CopyTo(frame, 0);
        if (size != 0) await stream.ReadExactlyAsync(frame.AsMemory(8));
        return DoIpCodec.Decode(frame);
    }

    private static async Task SendAsync(Stream stream, ushort type,
        byte[] body, byte version = DoIpCodec.Version)
    {
        await stream.WriteAsync(DoIpCodec.Encode(type, body, version));
        await stream.FlushAsync();
    }

    private static byte[] FromEcu(byte[] uds) =>
        [0x12, 0x34, 0x0E, 0x80, .. uds];

    private static async Task GatewayAsync(Stream stream,
        Func<Stream, Task> scenario, bool pendingRoute = false)
    {
        var activation = await ReadAsync(stream);
        if (activation.Type != DoIpCodec.RoutingActivationRequest ||
            activation.Payload.Length != 7 ||
            activation.Payload[0] != 0x0E || activation.Payload[1] != 0x80)
            throw new InvalidOperationException("Unexpected DoIP routing activation.");
        if (pendingRoute)
            await SendAsync(stream, DoIpCodec.RoutingActivationResponse,
                [0x0E, 0x80, 0x12, 0x34, 0x11, 0, 0, 0, 0]);
        await SendAsync(stream, DoIpCodec.RoutingActivationResponse,
            [0x0E, 0x80, 0x12, 0x34, 0x10, 0, 0, 0, 0]);
        await scenario(stream);
    }

    private static async Task RunCaseAsync(Func<Stream, Task> gateway,
        Func<DoIpDiagnosticTransport, Task> client, bool pendingRoute = false)
    {
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var server = Task.Run(async () =>
        {
            using var peer = await listener.AcceptTcpClientAsync();
            await using var stream = peer.GetStream();
            await GatewayAsync(stream, gateway, pendingRoute);
        });
        try
        {
            await using var transport = await DoIpDiagnosticTransport.ConnectAsync(
                "127.0.0.1", Tester, Ecu, TimeSpan.FromSeconds(3),
                CancellationToken.None, portOverride: port);
            await client(transport);
            await server.WaitAsync(TimeSpan.FromSeconds(3));
        }
        finally
        {
            listener.Stop();
        }
    }

    private static async Task<DoIpCodec.Message> RequireRequestAsync(Stream stream)
    {
        var message = await ReadAsync(stream);
        if (message.Type != DoIpCodec.DiagnosticMessage ||
            !message.Payload.SequenceEqual([0x0E, 0x80, 0x12, 0x34, .. Request]))
            throw new InvalidOperationException("Wrong simulated UDS request.");
        return message;
    }

    internal static int Run() => RunAsync().GetAwaiter().GetResult();

    private static async Task<int> RunAsync()
    {
        var checks = 0;
        void Check(bool value, string label)
        {
            checks++;
            if (!value)
                throw new InvalidOperationException("DoIP TCP simulation: " + label);
        }
        async Task Expect<T>(Func<Task> action, string label)
            where T : Exception
        {
            var matched = false;
            try { await action(); }
            catch (T) { matched = true; }
            Check(matched, label);
        }

        // Full happy path: routing 0x11 pending then 0x10, gateway ACK,
        // AliveCheck and UDS NRC 0x78 followed by final correlated response.
        await RunCaseAsync(async stream =>
        {
            await RequireRequestAsync(stream);
            await SendAsync(stream, DoIpCodec.DiagnosticPositiveAck,
                [0x12, 0x34, 0x0E, 0x80, 0, .. Request]);
            await SendAsync(stream, DoIpCodec.AliveCheckRequest, []);
            var alive = await ReadAsync(stream);
            Check(alive.Type == DoIpCodec.AliveCheckResponse &&
                alive.Payload.SequenceEqual([0x0E, 0x80]),
                "AliveCheck returns exact logical tester identity.");
            await SendAsync(stream, DoIpCodec.DiagnosticMessage, FromEcu(Pending));
            await SendAsync(stream, DoIpCodec.DiagnosticMessage, FromEcu(Positive));
        }, async client =>
        {
            var response = await client.ExchangeUdsAsync(
                Request, TimeSpan.FromSeconds(2), CancellationToken.None,
                TimeSpan.FromSeconds(1), TimeSpan.FromSeconds(1));
            Check(response.SequenceEqual(Positive),
                "DoIP routing/ACK/AliveCheck/NRC/positive PDU.");
        }, pendingRoute: true);

        // DoIP has no per-request correlation token. Two callers may never
        // interleave on the same socket; serialized reuse *after* success
        // remains legitimate for a diagnostic session.
        var firstSeen = new TaskCompletionSource<bool>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        await RunCaseAsync(async stream =>
        {
            await RequireRequestAsync(stream);
            firstSeen.TrySetResult(true);
            await Task.Delay(40);
            await SendAsync(stream, DoIpCodec.DiagnosticMessage, FromEcu(Positive));
            await RequireRequestAsync(stream);
            await SendAsync(stream, DoIpCodec.DiagnosticMessage, FromEcu(Positive));
        }, async client =>
        {
            var inFlight = client.ExchangeUdsAsync(
                Request, TimeSpan.FromSeconds(2), CancellationToken.None);
            await firstSeen.Task.WaitAsync(TimeSpan.FromSeconds(2));
            await Expect<InvalidOperationException>(() =>
                client.ExchangeUdsAsync(
                    Request, TimeSpan.FromSeconds(2), CancellationToken.None),
                "Concurrent DoIP exchange refused before a second TX.");
            Check((await inFlight).SequenceEqual(Positive),
                "First in-flight UDS transaction completed.");
            Check((await client.ExchangeUdsAsync(
                Request, TimeSpan.FromSeconds(2), CancellationToken.None))
                .SequenceEqual(Positive),
                "Sequential exchange on a healthy DoIP socket is permitted.");
        });

        var seenForTimeout = new TaskCompletionSource<bool>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        await RunCaseAsync(async stream =>
        {
            await RequireRequestAsync(stream);
            seenForTimeout.TrySetResult(true);
            // A vendor gateway may answer after P2. The client must reject
            // its reply and prevent an ambiguous *next* request on this socket.
            await Task.Delay(100);
            try
            {
                await SendAsync(stream, DoIpCodec.DiagnosticMessage,
                    FromEcu(Positive));
            }
            catch (IOException) { /* peer closed the ambiguous connection */ }
        }, async client =>
        {
            var attempt = client.ExchangeUdsAsync(Request,
                TimeSpan.FromSeconds(1), CancellationToken.None,
                p2Timeout: TimeSpan.FromMilliseconds(20));
            await seenForTimeout.Task.WaitAsync(TimeSpan.FromSeconds(2));
            await Expect<Exception>(async () =>
            {
                try { await attempt; }
                catch (TimeoutException) { throw; }
                catch (OperationCanceledException) { throw; }
            }, "Late server P2 must terminate the exchange.");
            await Expect<InvalidOperationException>(() =>
                client.ExchangeUdsAsync(Request, TimeSpan.FromSeconds(1),
                    CancellationToken.None),
                "Session with a timed-out send cannot be reused.");
        });

        // A correlated diagnostic NACK must never be considered a valid UDS
        // response. The failed socket is permanently retired.
        await RunCaseAsync(async stream =>
        {
            await RequireRequestAsync(stream);
            await SendAsync(stream, DoIpCodec.DiagnosticNegativeAck,
                [0x12, 0x34, 0x0E, 0x80, 0x02]);
        }, async client =>
        {
            await Expect<IOException>(() =>
                client.ExchangeUdsAsync(Request, TimeSpan.FromSeconds(1),
                    CancellationToken.None),
                "Gateway diagnostic negative ACK rejects diagnostic request.");
            await Expect<InvalidOperationException>(() =>
                client.ExchangeUdsAsync(Request, TimeSpan.FromSeconds(1),
                    CancellationToken.None),
                "DoIP transport must retire on correlated negative ACK.");
        });

        // Explicit user cancellation is different from receive timeout.
        var seenForCancel = new TaskCompletionSource<bool>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        await RunCaseAsync(async stream =>
        {
            await RequireRequestAsync(stream);
            seenForCancel.TrySetResult(true);
            await Task.Delay(60);
        }, async client =>
        {
            using var cancellation = new CancellationTokenSource();
            var attempt = client.ExchangeUdsAsync(Request,
                TimeSpan.FromSeconds(2), cancellation.Token);
            await seenForCancel.Task.WaitAsync(TimeSpan.FromSeconds(2));
            cancellation.Cancel();
            await Expect<OperationCanceledException>(() => attempt,
                "Caller cancellation terminates DoIP UDS exchange.");
            await Expect<InvalidOperationException>(() =>
                client.ExchangeUdsAsync(Request, TimeSpan.FromSeconds(1),
                    CancellationToken.None),
                "Cancelled DoIP UDS exchange retires the shared socket.");
        });

        // Invalid P2 or P2* must fail before an on-wire diagnostic request.
        await RunCaseAsync(async stream =>
        {
            // Test the socket remains idle and safe for a later valid request.
            await RequireRequestAsync(stream);
            await SendAsync(stream, DoIpCodec.DiagnosticMessage, FromEcu(Positive));
        }, async client =>
        {
            await Expect<ArgumentOutOfRangeException>(() =>
                client.ExchangeUdsAsync(Request, TimeSpan.FromSeconds(1),
                    CancellationToken.None, p2Timeout: TimeSpan.Zero),
                "Invalid P2 value fails before any ECU transmission.");
            Check((await client.ExchangeUdsAsync(Request,
                TimeSpan.FromSeconds(1), CancellationToken.None))
                .SequenceEqual(Positive),
                "Bad argument does not poison an otherwise unused socket.");
        });
        return checks;
    }
}
