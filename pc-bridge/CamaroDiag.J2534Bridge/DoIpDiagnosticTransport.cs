using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// ISO 13400 TCP client for both diagnostic reading AND writing over UDS.
/// The transport never invents a service blacklist or OEM seed/key.
/// Mutating transactions require the caller's existing OK then Oui/Non consent.
/// OEM programming/session requirements remain the responsibility of the caller.
/// TLS uses the secured DoIP port and the operating-system trust store.
/// OEM client certificates or private OEM authorities may require additional
/// configuration not represented by this minimal transport.
/// </summary>
internal sealed class DoIpDiagnosticTransport : IAsyncDisposable
{
    private readonly TcpClient socket;
    private readonly Stream stream;
    private readonly ushort testerAddress;
    private readonly ushort ecuAddress;

    private DoIpDiagnosticTransport(TcpClient socket, Stream dataStream,
        ushort tester, ushort ecu)
    {
        this.socket = socket;
        stream = dataStream;
        testerAddress = tester;
        ecuAddress = ecu;
    }

    internal static async Task<DoIpDiagnosticTransport> ConnectAsync(
        string host, ushort tester, ushort ecu, TimeSpan timeout,
        CancellationToken cancellationToken, bool useTls = false)
    {
        if (string.IsNullOrWhiteSpace(host) || host.Length > 253 ||
            tester == ecu || timeout <= TimeSpan.Zero || timeout > TimeSpan.FromSeconds(30))
            throw new ArgumentException("Explicit gateway, distinct logical addresses and timeout required.");
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(timeout);
        var socket = new TcpClient();
        try
        {
            await socket.ConnectAsync(host, useTls ? 3496 : 13400, deadline.Token);
            Stream dataStream = socket.GetStream();
            if (useTls)
            {
                // Never downgrade to TCP or bypass certificate/hostname
                // verification when an OEM gateway requires secure DoIP.
                var tls = new SslStream(dataStream, leaveInnerStreamOpen: false);
                await tls.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
                {
                    TargetHost = host,
                    EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
                    CertificateRevocationCheckMode = X509RevocationMode.Online
                }, deadline.Token);
                dataStream = tls;
            }
            var session = new DoIpDiagnosticTransport(socket, dataStream,
                tester, ecu);
            await session.SendAsync(DoIpCodec.RoutingActivation(tester), deadline.Token);
            DoIpCodec.Message routing;
            do
            {
                routing = await session.ReceiveAsync(deadline.Token);
                if (routing.Type == DoIpCodec.AliveCheckRequest)
                    await session.SendAsync(DoIpCodec.ReplyToAliveCheck(
                        routing, tester), deadline.Token);
            }
            while (routing.Type == DoIpCodec.AliveCheckRequest);
            DoIpCodec.RequireRoutingActive(routing, tester);
            return session;
        }
        catch
        {
            socket.Dispose();
            throw;
        }
    }

    internal async Task<byte[]> ExchangeUdsAsync(byte[] request,
        TimeSpan timeout, CancellationToken cancellationToken)
    {
        if (request is null || request.Length == 0 || request.Length > DoIpCodec.MaxPayload - 4)
            throw new ArgumentException("UDS request must fit the DoIP diagnostic PDU.", nameof(request));
        if (timeout <= TimeSpan.Zero || timeout > TimeSpan.FromSeconds(30))
            throw new ArgumentOutOfRangeException(nameof(timeout));
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(timeout);
        await SendAsync(DoIpCodec.Diagnostic(testerAddress, ecuAddress, request), deadline.Token);
        for (var count = 0; count < 12; count++)
        {
            var message = await ReceiveAsync(deadline.Token);
            if (message.Type == DoIpCodec.AliveCheckRequest)
            {
                await SendAsync(DoIpCodec.ReplyToAliveCheck(
                    message, testerAddress), deadline.Token);
                continue;
            }
            if (DoIpCodec.IsCorrelatedDiagnosticAck(message, testerAddress, ecuAddress))
                continue;
            var uds = DoIpCodec.RequireDiagnosticResponse(message, testerAddress, ecuAddress);
            // The UDS server may report a negative response. Do not mistake
            // it for a passing post-programming self-test.
            if (uds.Length >= 3 && uds[0] == 0x7F && uds[1] == request[0])
            {
                if (uds[2] == 0x78) continue; // UDS ResponsePending; stay within deadline
                throw new IOException($"ECU negative UDS response: 0x{uds[2]:X2}.");
            }
            if (uds.Length < 1 || uds[0] != (request[0] | 0x40))
                throw new InvalidDataException("Uncorrelated UDS response service.");
            if (request[0] is 0x22 or 0x2E &&
                (request.Length < 3 || uds.Length < 3 ||
                 uds[1] != request[1] || uds[2] != request[2]))
                throw new InvalidDataException("DoIP ECU returned a different DID.");
            if (request[0] == 0x31 &&
                (request.Length < 4 || uds.Length < 4 ||
                 uds[1] != (request[1] & 0x7F) ||
                 uds[2] != request[2] || uds[3] != request[3]))
                throw new InvalidDataException("DoIP ECU replied to another routine.");
            if (request[0] == 0x36 && 
                (request.Length < 2 || uds.Length < 2 || uds[1] != request[1]))
                throw new InvalidDataException("DoIP UDS TransferData block-counter mismatch.");
            return uds;
        }
        throw new IOException("No correlated diagnostic reply within DoIP message limit.");
    }

    private async Task SendAsync(byte[] frame, CancellationToken token)
    {
        await stream.WriteAsync(frame, token);
        await stream.FlushAsync(token);
    }

    private async Task<DoIpCodec.Message> ReceiveAsync(CancellationToken token)
    {
        var header = new byte[8];
        await stream.ReadExactlyAsync(header, token);
        var length = DoIpCodec.ReadPayloadLength(header);
        var message = new byte[8 + length];
        header.CopyTo(message, 0);
        if (length > 0) await stream.ReadExactlyAsync(message.AsMemory(8), token);
        return DoIpCodec.Decode(message);
    }

    public ValueTask DisposeAsync()
    {
        stream.Dispose();
        socket.Dispose();
        return ValueTask.CompletedTask;
    }
}
