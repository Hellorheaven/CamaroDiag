using System.Net;
using System.Net.Security;
using System.Security.Authentication;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace CamaroDiag.J2534Bridge;

internal sealed class BridgeServer
{
    private const int MaxRequestLineChars = 64 * 1024;
    private static readonly TimeSpan TlsHandshakeTimeout = TimeSpan.FromSeconds(10);
    private static readonly TimeSpan RequestReadTimeout = TimeSpan.FromSeconds(30);
    private readonly J2534Adapter _adapter;
    private readonly string _token;
    private readonly int _port;
    private readonly J2534MemoryReadScope? _memoryReadScope;
    private readonly BridgeTlsIdentity _tlsIdentity;
    private readonly Action? _onListening;

    public BridgeServer(
        J2534Adapter adapter, string token, int port, BridgeTlsIdentity tlsIdentity,
        J2534MemoryReadScope? memoryReadScope = null, Action? onListening = null)
    {
        _adapter = adapter;
        _token = token;
        _port = port;
        _tlsIdentity = tlsIdentity;
        _memoryReadScope = memoryReadScope;
        _onListening = onListening;
    }

    public async Task RunAsync(CancellationToken cancellationToken = default)
    {
        var listener = new TcpListener(IPAddress.Any, _port);
        listener.Start();
        _onListening?.Invoke();
        Console.WriteLine($"Listening with mandatory TLS 1.2+ on TCP {_port}; public remote addresses are rejected.");
        Console.WriteLine("Connect the Android device to the same trusted local network.");
        Console.WriteLine("Press Ctrl+C to stop. Do not forward this port to the Internet.");

        try
        {
            while (!cancellationToken.IsCancellationRequested)
            {
                using var client = await listener.AcceptTcpClientAsync(cancellationToken);
                client.NoDelay = true;
                var remoteAddress = (client.Client.RemoteEndPoint as IPEndPoint)?.Address;
                if (remoteAddress is null || !IsPrivateNetworkAddress(remoteAddress))
                {
                    Console.WriteLine($"Rejected non-local client: {client.Client.RemoteEndPoint}");
                    continue;
                }
                Console.WriteLine($"Local client connected: {client.Client.RemoteEndPoint}");
                await HandleClientAsync(client, cancellationToken);
                Console.WriteLine("Client disconnected; J2534 channel released.");
            }
        }
        finally
        {
            listener.Stop();
        }
    }

    private async Task HandleClientAsync(TcpClient client, CancellationToken cancellationToken)
    {
        using var networkStream = client.GetStream();
        using var stream = new SslStream(networkStream, leaveInnerStreamOpen: false);
        using var handshakeTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        handshakeTimeout.CancelAfter(TlsHandshakeTimeout);
        try
        {
            await stream.AuthenticateAsServerAsync(new SslServerAuthenticationOptions
            {
                ServerCertificate = _tlsIdentity.Certificate,
                EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
                ClientCertificateRequired = false,
                AllowRenegotiation = false
            }, handshakeTimeout.Token);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            Console.Error.WriteLine("TLS handshake timed out; closing unauthenticated client.");
            return;
        }
        catch (AuthenticationException)
        {
            // Never parse application JSON or reveal pairing details over a
            // plaintext/invalid TLS connection.
            Console.Error.WriteLine("Rejected client without a valid TLS handshake.");
            return;
        }
        catch (IOException)
        {
            // A plaintext peer may simply close the socket on TLS failure.
            return;
        }
        using var reader = new StreamReader(stream, new UTF8Encoding(false), false, 4096, leaveOpen: true);
        await using var writer = new StreamWriter(stream, new UTF8Encoding(false), 4096, leaveOpen: true) { AutoFlush = true };
        using var device = new J2534Device(_adapter, _memoryReadScope);

        while (!cancellationToken.IsCancellationRequested && client.Connected)
        {
            string? line;
            using var readTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            readTimeout.CancelAfter(RequestReadTimeout);
            try { line = await ReadBoundedLineAsync(reader, readTimeout.Token); }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                Console.Error.WriteLine("Timed out waiting for a bridge request; connection closed.");
                break;
            }
            catch (InvalidDataException)
            {
                await WriteErrorAsync(writer, "Request exceeds the 64 KiB bridge limit.");
                break;
            }
            catch (IOException) { break; }
            if (line is null) break;

            try
            {
                using var json = JsonDocument.Parse(line);
                var root = json.RootElement;
                if (!TokenMatches(root.TryGetProperty("token", out var tokenElement) ? tokenElement.GetString() : null))
                    throw new UnauthorizedAccessException("Invalid pairing token.");

                var operation = root.TryGetProperty("op", out var opElement) ? opElement.GetString() : null;
                switch (operation)
                {
                    case "open":
                    {
                        var requestId = ParseHexId(root, "requestCanId");
                        var responseId = ParseHexId(root, "responseCanId");
                        var can29 = root.TryGetProperty("can29Bit", out var canElement) && canElement.GetBoolean();
                        var protocolNumber = root.TryGetProperty("protocolNumber", out var protocolElement)
                            ? protocolElement.GetInt32() : 33;
                        var baudRate = protocolNumber switch
                        {
                            33 or 34 => 500000,
                            35 or 36 => 250000,
                            63 or 64 => 62500,
                            _ => throw new ArgumentOutOfRangeException(nameof(protocolNumber), "Unsupported CAN protocol preset.")
                        };
                        device.Open(requestId, responseId, can29, protocolNumber);
                        await writer.WriteLineAsync(JsonSerializer.Serialize(new
                        {
                            ok = true,
                            adapter = device.AdapterName,
                            driver = Path.GetFileName(device.DriverPath),
                            protocol = protocolNumber is 63 or 64 ? "SW-ISO15765" : "ISO15765",
                            protocolNumber,
                            baudRate,
                            requestCanId = requestId.ToString(can29 ? "X8" : "X3"),
                            responseCanId = responseId.ToString(can29 ? "X8" : "X3"),
                            batteryMillivolts = device.ReadBatteryMillivolts(),
                            readOnly = true,
                            scopedPhysicalMemoryRead = device.IsMemoryReadEnabled,
                            standardObdPhysicalRead = device.IsStandardObdReadEnabled
                        }));
                        break;
                    }
                    case "identify":
                    {
                        var identity = device.ReadCalibrationIdentification();
                        await writer.WriteLineAsync(JsonSerializer.Serialize(new { ok = true, identity }));
                        break;
                    }
                    case "identifyGmlan":
                    {
                        // Physical GM CAN VIN/OSID read only. Never grants
                        // SecurityAccess or programming privileges.
                        var identity = await Task.Run(
                            () => device.ReadGmlanIdentification(), cancellationToken);
                        await writer.WriteLineAsync(JsonSerializer.Serialize(new
                        {
                            ok = true,
                            identity,
                            responseCanId = device.ResponseCanIdLabel,
                            readOnly = true,
                            flashQualified = false
                        }));
                        break;
                    }
                    case "exchange":
                    {
                        var payload = ParseHexPayload(root);
                        var timeout = root.TryGetProperty("timeoutMs", out var timeoutElement) ? timeoutElement.GetInt32() : 3000;
                        var response = await Task.Run(() => device.Exchange(payload, timeout), cancellationToken);
                        await writer.WriteLineAsync(JsonSerializer.Serialize(new { ok = true, payloadHex = Convert.ToHexString(response) }));
                        break;
                    }
                    case "sendOnly":
                    {
                        var payload = ParseHexPayload(root);
                        await Task.Run(() => device.SendOnly(payload), cancellationToken);
                        await writer.WriteLineAsync(JsonSerializer.Serialize(new { ok = true }));
                        break;
                    }
                    case "close":
                        device.Close();
                        await writer.WriteLineAsync(JsonSerializer.Serialize(new { ok = true }));
                        break;
                    default:
                        throw new InvalidOperationException($"Unknown bridge operation: {operation ?? "(null)"}");
                }
            }
            catch (Exception ex)
            {
                await WriteErrorAsync(writer, ex.Message);
            }
        }
    }

    // Read one bounded request without StreamReader.ReadLineAsync buffering an
    // attacker-controlled unbounded line before the length check.
    internal static async Task<string?> ReadBoundedLineAsync(
        StreamReader reader, CancellationToken cancellationToken)
    {
        var line = new StringBuilder();
        var character = new char[1];
        var received = 0;
        while (true)
        {
            var count = await reader.ReadAsync(character.AsMemory(), cancellationToken);
            if (count == 0)
            {
                if (received == 0) return null;
                throw new IOException("Bridge request ended without a newline.");
            }
            if (character[0] == '\n') return line.ToString();
            if (++received > MaxRequestLineChars)
                throw new InvalidDataException("Bridge request exceeds the 64 KiB limit.");
            if (character[0] != '\r') line.Append(character[0]);
        }
    }

    internal static bool IsPrivateNetworkAddress(IPAddress address)
    {
        if (address.IsIPv4MappedToIPv6) address = address.MapToIPv4();
        if (IPAddress.IsLoopback(address)) return true;

        var bytes = address.GetAddressBytes();
        if (address.AddressFamily == AddressFamily.InterNetwork)
        {
            return bytes[0] == 10 ||
                   (bytes[0] == 172 && bytes[1] is >= 16 and <= 31) ||
                   (bytes[0] == 192 && bytes[1] == 168) ||
                   (bytes[0] == 169 && bytes[1] == 254);
        }

        if (address.AddressFamily == AddressFamily.InterNetworkV6)
            return address.IsIPv6LinkLocal || (bytes[0] & 0xFE) == 0xFC;

        return false;
    }

    private bool TokenMatches(string? supplied)
    {
        if (string.IsNullOrEmpty(supplied)) return false;
        var expected = Encoding.UTF8.GetBytes(_token);
        var actual = Encoding.UTF8.GetBytes(supplied);
        return expected.Length == actual.Length && CryptographicOperations.FixedTimeEquals(expected, actual);
    }

    internal static int ParseHexId(JsonElement root, string property)
    {
        if (!root.TryGetProperty(property, out var element)) throw new ArgumentException($"Missing {property}.");
        var text = element.GetString()?.Trim().Replace("0x", "", StringComparison.OrdinalIgnoreCase);
        if (string.IsNullOrWhiteSpace(text) || !int.TryParse(text, System.Globalization.NumberStyles.HexNumber,
                System.Globalization.CultureInfo.InvariantCulture, out var value))
            throw new ArgumentException($"Invalid hexadecimal CAN ID in {property}.");
        return value;
    }

    internal static byte[] ParseHexPayload(JsonElement root)
    {
        if (!root.TryGetProperty("payloadHex", out var element)) throw new ArgumentException("Missing payloadHex.");
        var text = element.GetString()?.Replace(" ", "").Replace("-", "").Trim();
        if (string.IsNullOrWhiteSpace(text) || text.Length % 2 != 0)
            throw new ArgumentException("payloadHex must contain complete hexadecimal bytes.");
        try { return Convert.FromHexString(text); }
        catch (FormatException) { throw new ArgumentException("payloadHex contains non-hexadecimal characters."); }
    }

    private static Task WriteErrorAsync(StreamWriter writer, string message) =>
        writer.WriteLineAsync(JsonSerializer.Serialize(new { ok = false, error = message }));
}
