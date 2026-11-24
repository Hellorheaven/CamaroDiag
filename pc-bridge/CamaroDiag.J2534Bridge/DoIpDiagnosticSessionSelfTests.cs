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
        Func<Stream, Task> scenario, bool pendingRoute = false,
        byte activationType = 0x00, byte[]? activationOemData = null,
        byte protocolVersion = DoIpCodec.Version)
    {
        var activation = await ReadAsync(stream);
        if (activation.Type != DoIpCodec.RoutingActivationRequest ||
            activation.Version != protocolVersion ||
            activation.Payload.Length != (activationOemData is null ? 7 : 11) ||
            activation.Payload[0] != 0x0E || activation.Payload[1] != 0x80 ||
            activation.Payload[2] != activationType ||
            !activation.Payload.AsSpan(3, 4).SequenceEqual(new byte[] {0, 0, 0, 0}) ||
            (activationOemData is not null &&
             !activation.Payload.AsSpan(7, 4).SequenceEqual(activationOemData)))
            throw new InvalidOperationException("Unexpected DoIP routing activation.");
        if (pendingRoute)
            await SendAsync(stream, DoIpCodec.RoutingActivationResponse,
                [0x0E, 0x80, 0x12, 0x34, 0x11, 0, 0, 0, 0],
                protocolVersion);
        await SendAsync(stream, DoIpCodec.RoutingActivationResponse,
            [0x0E, 0x80, 0x12, 0x34, 0x10, 0, 0, 0, 0],
            protocolVersion);
        await scenario(stream);
    }

    private static async Task RunCaseAsync(Func<Stream, Task> gateway,
        Func<DoIpDiagnosticTransport, Task> client, bool pendingRoute = false,
        byte activationType = 0x00, byte[]? activationOemData = null,
        byte protocolVersion = DoIpCodec.Version)
    {
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var server = Task.Run(async () =>
        {
            using var peer = await listener.AcceptTcpClientAsync();
            await using var stream = peer.GetStream();
            await GatewayAsync(stream, gateway, pendingRoute,
                activationType, activationOemData, protocolVersion);
        });
        try
        {
            await using var transport = await DoIpDiagnosticTransport.ConnectAsync(
                "127.0.0.1", Tester, Ecu, TimeSpan.FromSeconds(3),
                CancellationToken.None, portOverride: port,
                protocolVersion: protocolVersion,
                activationType: activationType,
                activationOemData: activationOemData);
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
        byte[] expected = [0x0E, 0x80, 0x12, 0x34, .. Request];
        if (message.Type != DoIpCodec.DiagnosticMessage ||
            !message.Payload.SequenceEqual(expected))
            throw new InvalidOperationException("Wrong simulated UDS request.");
        return message;
    }

    private static async Task VerifyRoutingRejectedAsync(byte responseCode,
        byte responseVersion, Func<Task, Task> verify)
    {
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var server = Task.Run(async () =>
        {
            using var peer = await listener.AcceptTcpClientAsync();
            await using var stream = peer.GetStream();
            var activation = await ReadAsync(stream);
            if (activation.Type != DoIpCodec.RoutingActivationRequest)
                throw new InvalidDataException("Missing DoIP routing activation.");
            await SendAsync(stream, DoIpCodec.RoutingActivationResponse,
                [0x0E, 0x80, 0x12, 0x34, responseCode, 0, 0, 0, 0],
                responseVersion);
        });
        try
        {
            var attempt = DoIpDiagnosticTransport.ConnectAsync(
                "127.0.0.1", Tester, Ecu,
                TimeSpan.FromSeconds(2), CancellationToken.None,
                portOverride: port);
            await verify(attempt);
            await server.WaitAsync(TimeSpan.FromSeconds(3));
        }
        finally { listener.Stop(); }
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
                alive.Payload.SequenceEqual(new byte[] {0x0E, 0x80}),
                "AliveCheck returns exact logical tester identity.");
            await SendAsync(stream, DoIpCodec.DiagnosticMessage, FromEcu(Pending));
            // Split TCP header and UDS body across writes: TCP frames are a
            // byte stream, not guaranteed to fit a single ReadAsync.
            var responseFrame = DoIpCodec.Encode(DoIpCodec.DiagnosticMessage,
                FromEcu(Positive));
            await stream.WriteAsync(responseFrame.AsMemory(0, 3));
            await Task.Delay(2);
            await stream.WriteAsync(responseFrame.AsMemory(3, 6));
            await Task.Delay(2);
            await stream.WriteAsync(responseFrame.AsMemory(9));
            await stream.FlushAsync();
        }, async client =>
        {
            var response = await client.ExchangeUdsAsync(
                Request, TimeSpan.FromSeconds(2), CancellationToken.None,
                TimeSpan.FromSeconds(1), TimeSpan.FromSeconds(1));
            Check(response.SequenceEqual(Positive),
                "DoIP routing/ACK/AliveCheck/NRC/positive PDU.");
        }, pendingRoute: true);

        // An OEM-specific activation must be an operator-provided field,
        // not an invented token or a modified ISO-reserved area.
        await RunCaseAsync(async stream =>
        {
            await RequireRequestAsync(stream);
            await SendAsync(stream, DoIpCodec.DiagnosticMessage, FromEcu(Positive));
        }, async client =>
        {
            Check((await client.ExchangeUdsAsync(
                Request, TimeSpan.FromSeconds(2), CancellationToken.None))
                .SequenceEqual(Positive),
                "Operator-provided 11-byte OEM routing activation is transmitted.");
        }, activationType: 0xE0, activationOemData: [1, 2, 3, 4]);

        // End-to-end TCP routing, ACK and UDS must agree on selected DoIP
        // protocol version. These use loopback only, not an actual gateway.
        foreach (var selectedVersion in new byte[] { 0x03, 0x04 })
        {
            await RunCaseAsync(async stream =>
            {
                var requestFrame = await RequireRequestAsync(stream);
                Check(requestFrame.Version == selectedVersion,
                    "DoIP diagnostic request carries negotiated v" +
                    selectedVersion.ToString("X2") + ".");
                await SendAsync(stream, DoIpCodec.DiagnosticPositiveAck,
                    [0x12, 0x34, 0x0E, 0x80, 0x00, .. Request],
                    selectedVersion);
                await SendAsync(stream, DoIpCodec.AliveCheckRequest,
                    [], selectedVersion);
                var aliveResponse = await ReadAsync(stream);
                Check(aliveResponse.Version == selectedVersion &&
                    aliveResponse.Type == DoIpCodec.AliveCheckResponse,
                    "DoIP AliveCheck response preserves negotiated version.");
                await SendAsync(stream, DoIpCodec.DiagnosticMessage,
                    FromEcu(Positive), selectedVersion);
            }, async client =>
            {
                var answer = await client.ExchangeUdsAsync(Request,
                    TimeSpan.FromSeconds(2), CancellationToken.None);
                Check(answer.SequenceEqual(Positive),
                    "DoIP TCP routing and exchange respect version 0x" +
                    selectedVersion.ToString("X2") + ".");
            }, protocolVersion: selectedVersion);
        }

        // IPv6 unicast diagnostic TCP must work just like IPv4; the
        // gateway here is IPv6 loopback, never an automotive network.
        if (Socket.OSSupportsIPv6)
        {
            var ipv6Listener = new TcpListener(IPAddress.IPv6Loopback, 0);
            ipv6Listener.Start();
            var ipv6Port = ((IPEndPoint)ipv6Listener.LocalEndpoint).Port;
            var ipv6Server = Task.Run(async () =>
            {
                using var peer = await ipv6Listener.AcceptTcpClientAsync();
                await using var wire = peer.GetStream();
                await GatewayAsync(wire, async stream =>
                {
                    var message = await RequireRequestAsync(stream);
                    Check(message.Version == 0x02,
                        "IPv6 TCP gateway receives DoIP v02 diagnostic request.");
                    await SendAsync(stream, DoIpCodec.DiagnosticMessage,
                        FromEcu(Positive));
                });
            });
            try
            {
                await using var ipv6Session =
                    await DoIpDiagnosticTransport.ConnectAsync(
                        "::1", Tester, Ecu, TimeSpan.FromSeconds(2),
                        CancellationToken.None, portOverride: ipv6Port);
                var answer = await ipv6Session.ExchangeUdsAsync(
                    Request, TimeSpan.FromSeconds(2), CancellationToken.None);
                Check(answer.SequenceEqual(Positive),
                    "IPv6 TCP DoIP routing and UDS exchange completes on loopback.");
                await ipv6Server.WaitAsync(TimeSpan.FromSeconds(3));
            }
            finally { ipv6Listener.Stop(); }
        }

        // Read/write parity: the transport is bidirectional. This fake
        // gateway never touches an ECU, and the higher-level operator flow
        // remains responsible for double consent before any real mutation.
        byte[] simulatedWrite = [0x2E, 0xF1, 0x90, 0x01];
        await RunCaseAsync(async stream =>
        {
            var write = await ReadAsync(stream);
            Check(write.Type == DoIpCodec.DiagnosticMessage &&
                write.Payload.AsSpan(4).SequenceEqual(simulatedWrite),
                "DoIP TCP accepts a simulated UDS WriteDataByIdentifier request.");
            await SendAsync(stream, DoIpCodec.DiagnosticPositiveAck,
                [0x12, 0x34, 0x0E, 0x80, 0, .. simulatedWrite]);
            await SendAsync(stream, DoIpCodec.DiagnosticMessage,
                FromEcu([0x6E, 0xF1, 0x90]));
        }, async client =>
        {
            var final = await client.ExchangeUdsAsync(simulatedWrite,
                TimeSpan.FromSeconds(2), CancellationToken.None);
            Check(final.SequenceEqual(new byte[] { 0x6E, 0xF1, 0x90 }),
                "DoIP transports a mutating SID without a protocol blacklist.");
        });

        // A negative UDS write response is not success and cannot leave
        // the socket reusable for an unrelated subsequent request.
        await RunCaseAsync(async stream =>
        {
            var write = await ReadAsync(stream);
            Check(write.Payload.AsSpan(4).SequenceEqual(simulatedWrite),
                "Simulated write NRC test receives the intended UDS service.");
            await SendAsync(stream, DoIpCodec.DiagnosticMessage,
                FromEcu([0x7F, 0x2E, 0x33]));
        }, async client =>
        {
            await Expect<IOException>(() =>
                client.ExchangeUdsAsync(simulatedWrite,
                    TimeSpan.FromSeconds(2), CancellationToken.None),
                "UDS negative response to simulated write is never accepted.");
            await Expect<InvalidOperationException>(() =>
                client.ExchangeUdsAsync(Request, TimeSpan.FromSeconds(1),
                    CancellationToken.None),
                "Negative write NRC retires the ambiguous TCP socket.");
        });

        // The caller owns the original byte[] and might reuse it as soon
        // as an async exchange is initiated. All transmission, ACK matching
        // and UDS SID validation must use the same immutable snapshot.
        await RunCaseAsync(async stream =>
        {
            var frame = await RequireRequestAsync(stream);
            Check(frame.Payload.AsSpan(4).SequenceEqual(Request),
                "Gateway received the original approved UDS request.");
            await SendAsync(stream, DoIpCodec.DiagnosticPositiveAck,
                [0x12, 0x34, 0x0E, 0x80, 0, .. Request]);
            await SendAsync(stream, DoIpCodec.DiagnosticMessage,
                FromEcu(Positive));
        }, async client =>
        {
            var mutable = Request.ToArray();
            var operation = client.ExchangeUdsAsync(mutable,
                TimeSpan.FromSeconds(2), CancellationToken.None);
            Array.Fill(mutable, (byte)0x00);
            Check((await operation).SequenceEqual(Positive),
                "DoIP freezes caller-approved request bytes across async ACK matching.");
        });

        // DoIP has no per-request correlation token. Two callers may never
        // interleave on the same socket; serialized reuse *after* success
        // remains legitimate for a diagnostic session.
        var firstSeen = new TaskCompletionSource<bool>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        var releaseFirst = new TaskCompletionSource<bool>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        await RunCaseAsync(async stream =>
        {
            await RequireRequestAsync(stream);
            firstSeen.TrySetResult(true);
            await releaseFirst.Task.WaitAsync(TimeSpan.FromSeconds(2));
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
            releaseFirst.TrySetResult(true);
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
            catch (ObjectDisposedException) { /* closed after timeout */ }
            catch (SocketException) { /* Windows socket closed by client */ }
        }, async client =>
        {
            var attempt = client.ExchangeUdsAsync(Request,
                TimeSpan.FromSeconds(1), CancellationToken.None,
                p2Timeout: TimeSpan.FromMilliseconds(20));
            await seenForTimeout.Task.WaitAsync(TimeSpan.FromSeconds(2));
            var timedOut = false;
            try { await attempt; }
            catch (TimeoutException) { timedOut = true; }
            catch (OperationCanceledException) { timedOut = true; }
            Check(timedOut, "Late server P2 must terminate the exchange.");
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

        // A flood of correctly correlated gateway ACKs is not an ECU
        // diagnostic response and cannot extend the P2 deadline.
        await RunCaseAsync(async stream =>
        {
            await RequireRequestAsync(stream);
            for (var i = 0; i < 12; i++)
            {
                try
                {
                    await SendAsync(stream, DoIpCodec.DiagnosticPositiveAck,
                        [0x12, 0x34, 0x0E, 0x80, 0, .. Request]);
                }
                catch (Exception error) when (error is IOException or
                    SocketException or ObjectDisposedException)
                {
                    break; // client correctly retired timed-out session
                }
                await Task.Delay(7);
            }
        }, async client =>
        {
            await Expect<TimeoutException>(() =>
                client.ExchangeUdsAsync(Request, TimeSpan.FromSeconds(1),
                    CancellationToken.None,
                    p2Timeout: TimeSpan.FromMilliseconds(20)),
                "DoIP ACK flood cannot prolong ECU P2 response deadline.");
            await Expect<InvalidOperationException>(() =>
                client.ExchangeUdsAsync(Request, TimeSpan.FromSeconds(1),
                    CancellationToken.None),
                "Session receiving only ACKs is retired on response timeout.");
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

        // A syntactically valid but negative routing activation response is
        // not an authenticated/activated UDS transport.
        await VerifyRoutingRejectedAsync(0x04, 0x02, async attempt =>
        {
            await Expect<IOException>(() => attempt,
                "Routing response 0x04 rejects diagnostic access.");
        });
        await VerifyRoutingRejectedAsync(0x10, 0x04, async attempt =>
        {
            await Expect<InvalidDataException>(() => attempt,
                "Unexpected DoIP version cannot activate routing.");
        });

        // Gateway generic header NACK aborts, but never becomes UDS data.
        await RunCaseAsync(async stream =>
        {
            await RequireRequestAsync(stream);
            await SendAsync(stream, DoIpCodec.GenericNegativeAck, [0x02]);
        }, async client =>
        {
            await Expect<IOException>(() =>
                client.ExchangeUdsAsync(Request, TimeSpan.FromSeconds(1),
                    CancellationToken.None),
                "Generic header NACK aborts DoIP diagnostic operation.");
            await Expect<InvalidOperationException>(() =>
                client.ExchangeUdsAsync(Request, TimeSpan.FromSeconds(1),
                    CancellationToken.None),
                "Generic NACK retires the uncertain TCP exchange.");
        });

        await RunCaseAsync(async stream =>
        {
            await RequireRequestAsync(stream);
            await SendAsync(stream, DoIpCodec.DiagnosticMessage,
                [0x12, 0x35, 0x0E, 0x80, .. Positive]);
        }, async client =>
        {
            await Expect<InvalidDataException>(() =>
                client.ExchangeUdsAsync(Request, TimeSpan.FromSeconds(1),
                    CancellationToken.None),
                "Wrong physical ECU source logical address rejected.");
            await Expect<InvalidOperationException>(() =>
                client.ExchangeUdsAsync(Request, TimeSpan.FromSeconds(1),
                    CancellationToken.None),
                "Uncorrelated ECU response poisons this DoIP socket.");
        });

        // Explicit disposal before an exchange is not a request to reconnect
        // to a possibly different ECU with the same logical address.
        await RunCaseAsync(async stream =>
        {
            // No UDS request is permitted after the client closes.
            var one = new byte[1];
            var read = await stream.ReadAsync(one);
            Check(read == 0, "Disposed transport sends no further UDS packets.");
        }, async client =>
        {
            await client.DisposeAsync();
            await Expect<InvalidOperationException>(() =>
                client.ExchangeUdsAsync(Request, TimeSpan.FromSeconds(1),
                    CancellationToken.None),
                "Explicitly closed DoIP socket never reopens implicitly.");
        });


        // ACKs from a previous diagnostic request cannot satisfy the
        // current transaction, even if their TCP channel is still active.
        await RunCaseAsync(async stream =>
        {
            await RequireRequestAsync(stream);
            await SendAsync(stream, DoIpCodec.DiagnosticPositiveAck,
                [0x12, 0x34, 0x0E, 0x80, 0, 0x2E, 0xF1, 0x90]);
        }, async client =>
        {
            await Expect<InvalidDataException>(() =>
                client.ExchangeUdsAsync(Request, TimeSpan.FromSeconds(1),
                    CancellationToken.None),
                "Wrong echoed DoIP gateway ACK retires this exchange.");
            await Expect<InvalidOperationException>(() =>
                client.ExchangeUdsAsync(Request, TimeSpan.FromSeconds(1),
                    CancellationToken.None),
                "A stale ACK cannot be carried into a new UDS exchange.");
        });

        // Wrong UDS SID must not be accepted just because DoIP source and
        // destination logical addresses happen to match the active channel.
        await RunCaseAsync(async stream =>
        {
            await RequireRequestAsync(stream);
            await SendAsync(stream, DoIpCodec.DiagnosticMessage,
                FromEcu([0x63, 0xF1, 0x90, 0x41]));
        }, async client =>
        {
            await Expect<InvalidDataException>(() =>
                client.ExchangeUdsAsync(Request, TimeSpan.FromSeconds(1),
                    CancellationToken.None),
                "Wrong UDS positive response SID is rejected over DoIP.");
        });

        // The negotiated protocol version may not change on a live TCP
        // diagnostic session even if the new version is independently legal.
        await RunCaseAsync(async stream =>
        {
            await RequireRequestAsync(stream);
            await SendAsync(stream, DoIpCodec.DiagnosticMessage,
                FromEcu(Positive), version: 0x03);
        }, async client =>
        {
            await Expect<InvalidDataException>(() =>
                client.ExchangeUdsAsync(Request, TimeSpan.FromSeconds(1),
                    CancellationToken.None),
                "DoIP version switch inside a live diagnostic is rejected.");
        });

        // A truncated TCP payload is not a partial positive UDS response.
        await RunCaseAsync(async stream =>
        {
            await RequireRequestAsync(stream);
            var packet = DoIpCodec.Encode(DoIpCodec.DiagnosticMessage,
                FromEcu(Positive));
            await stream.WriteAsync(packet.AsMemory(0, packet.Length - 2));
            await stream.FlushAsync();
        }, async client =>
        {
            await Expect<EndOfStreamException>(() =>
                client.ExchangeUdsAsync(Request, TimeSpan.FromSeconds(1),
                    CancellationToken.None),
                "Truncated TCP diagnostic response retires DoIP connection.");
        });

        // Multiple consecutive NRC 0x78 packets renew P2* only; they are
        // not themselves positive completion or authorization to write.
        await RunCaseAsync(async stream =>
        {
            await RequireRequestAsync(stream);
            await SendAsync(stream, DoIpCodec.DiagnosticMessage, FromEcu(Pending));
            await SendAsync(stream, DoIpCodec.AliveCheckRequest, []);
            var alive = await ReadAsync(stream);
            Check(alive.Type == DoIpCodec.AliveCheckResponse,
                "AliveCheck remains answerable inside a P2* window.");
            await SendAsync(stream, DoIpCodec.DiagnosticMessage, FromEcu(Pending));
            await SendAsync(stream, DoIpCodec.DiagnosticMessage, FromEcu(Positive));
        }, async client =>
        {
            var answer = await client.ExchangeUdsAsync(
                Request, TimeSpan.FromSeconds(2), CancellationToken.None,
                TimeSpan.FromSeconds(1), TimeSpan.FromSeconds(1));
            Check(answer.SequenceEqual(Positive),
                "Two NRC 0x78 and AliveCheck yield one final matching reply.");
        });


        // TLS must never silently fall back to the clear-text DoIP port
        // if the peer responds with non-TLS data. This loopback test uses
        // no certificate, trust-store mutation, ECU, or real network.
        var plainServer = new TcpListener(IPAddress.Loopback, 0);
        plainServer.Start();
        var plainPort = ((IPEndPoint)plainServer.LocalEndpoint).Port;
        var sawClientHello = false;
        var plainTask = Task.Run(async () =>
        {
            using var peer = await plainServer.AcceptTcpClientAsync();
            await using var wire = peer.GetStream();
            var header = new byte[5];
            await wire.ReadExactlyAsync(header).AsTask().WaitAsync(TimeSpan.FromSeconds(3));
            sawClientHello = header[0] == 0x16 &&
                header[1] == 0x03; // TLS record, never raw DoIP 0x02/0x03.
            await wire.WriteAsync(new byte[]
                {0x48, 0x54, 0x54, 0x50, 0x2F, 0x31, 0x2E, 0x31});
            await wire.FlushAsync();
        });
        try
        {
            var refused = false;
            try
            {
                await using var _ = await DoIpDiagnosticTransport.ConnectAsync(
                    "127.0.0.1", Tester, Ecu, TimeSpan.FromSeconds(3),
                    CancellationToken.None, useTls: true, portOverride: plainPort);
            }
            catch (Exception error) when (error is
                System.Security.Authentication.AuthenticationException or
                IOException or InvalidDataException)
            {
                refused = true;
            }
            await plainTask.WaitAsync(TimeSpan.FromSeconds(3));
            Check(sawClientHello && refused,
                "TLS mode never falls back to plaintext DoIP routing.");
        }
        finally
        {
            plainServer.Stop();
        }


        // Two routing responses on one TCP socket must refer to the same
        // gateway. Otherwise a pending 0x11 could be completed by a
        // different logical entity without a fresh routing handshake.
        var routeListener = new TcpListener(IPAddress.Loopback, 0);
        routeListener.Start();
        var routePort = ((IPEndPoint)routeListener.LocalEndpoint).Port;
        var routeServer = Task.Run(async () =>
        {
            using var peer = await routeListener.AcceptTcpClientAsync();
            await using var wire = peer.GetStream();
            var activation = await ReadAsync(wire);
            Check(activation.Type == DoIpCodec.RoutingActivationRequest,
                "Routing-source test received the expected activation.");
            await SendAsync(wire, DoIpCodec.RoutingActivationResponse,
                [0x0E, 0x80, 0x12, 0x34, 0x11, 0, 0, 0, 0]);
            await SendAsync(wire, DoIpCodec.RoutingActivationResponse,
                [0x0E, 0x80, 0x12, 0x35, 0x10, 0, 0, 0, 0]);
        });
        try
        {
            await Expect<InvalidDataException>(() =>
                DoIpDiagnosticTransport.ConnectAsync(
                    "127.0.0.1", Tester, Ecu, TimeSpan.FromSeconds(2),
                    CancellationToken.None, portOverride: routePort),
                "Routing activation cannot switch logical gateway after 0x11.");
            await routeServer.WaitAsync(TimeSpan.FromSeconds(3));
        }
        finally { routeListener.Stop(); }


        // A gateway may hold routing activation in 0x11 while it asks the
        // vehicle for confirmation. That cannot extend the original TCP
        // activation deadline, and a late 0x10 is not an active session.
        var waitingRoute = new TcpListener(IPAddress.Loopback, 0);
        waitingRoute.Start();
        var waitingPort = ((IPEndPoint)waitingRoute.LocalEndpoint).Port;
        var waitingServer = Task.Run(async () =>
        {
            using var peer = await waitingRoute.AcceptTcpClientAsync();
            await using var wire = peer.GetStream();
            var activation = await ReadAsync(wire);
            Check(activation.Type == DoIpCodec.RoutingActivationRequest,
                "Routing timeout test received expected activation.");
            await SendAsync(wire, DoIpCodec.RoutingActivationResponse,
                [0x0E, 0x80, 0x12, 0x34, 0x11, 0, 0, 0, 0]);
            await Task.Delay(180); // Above the client's full connection budget.
            try
            {
                await SendAsync(wire, DoIpCodec.RoutingActivationResponse,
                    [0x0E, 0x80, 0x12, 0x34, 0x10, 0, 0, 0, 0]);
            }
            catch (Exception error) when (error is IOException or
                SocketException or ObjectDisposedException)
            {
                // The timed-out client correctly retired the TCP connection.
            }
        });
        try
        {
            await Expect<OperationCanceledException>(() =>
                DoIpDiagnosticTransport.ConnectAsync(
                    "127.0.0.1", Tester, Ecu,
                    TimeSpan.FromMilliseconds(80),
                    CancellationToken.None, portOverride: waitingPort),
                "Pending routing 0x11 never bypasses overall connect deadline.");
            await waitingServer.WaitAsync(TimeSpan.FromSeconds(3));
        }
        finally { waitingRoute.Stop(); }

        return checks;
    }
}
