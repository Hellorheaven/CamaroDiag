using System.Buffers.Binary;
using System.Net.Sockets;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// ISO 13400 TCP client for both diagnostic reading AND writing over UDS.
/// The transport never invents a service blacklist or OEM seed/key.
/// Mutating transactions require the caller's existing OK then Oui/Non consent.
/// OEM programming/session requirements remain the responsibility of the caller.
/// Secure DoIP/TLS variants require a separately configured secure connection.
/// </summary>
internal sealed class DoIpDiagnosticTransport : IAsyncDisposable
{
    private readonly TcpClient socket;
    private readonly NetworkStream stream;
    private readonly ushort testerAddress;
    private readonly ushort ecuAddress;

    private DoIpDiagnosticTransport(TcpClient socket, ushort tester, ushort ecu)
    {
        this.socket = socket;
        stream = socket.GetStream();
        testerAddress = tester;
        ecuAddress = ecu;
    }

    internal static async Task<DoIpDiagnosticTransport> ConnectAsync(
        string host, ushort tester, ushort ecu, TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(host) || host.Length > 253 ||
            tester == ecu || timeout <= TimeSpan.Zero || timeout > TimeSpan.FromSeconds(30))
            throw new ArgumentException("Explicit gateway, distinct logical addresses and timeout required.");
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(timeout);
        var socket = new TcpClient();
        try
        {
            await socket.ConnectAsync(host, 13400, deadline.Token);
            var session = new DoIpDiagnosticTransport(socket, tester, ecu);
            await session.SendAsync(DoIpCodec.RoutingActivation(tester), deadline.Token);
            var routing = await session.ReceiveAsync(deadline.Token);
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
