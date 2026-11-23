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
    private readonly bool _allowStandardObdClear;

    public BridgeServer(
        J2534Adapter adapter, string token, int port, BridgeTlsIdentity tlsIdentity,
        J2534MemoryReadScope? memoryReadScope = null, Action? onListening = null,
        bool allowStandardObdClear = false)
    {
        _adapter = adapter;
        _token = token;
        _port = port;
        _tlsIdentity = tlsIdentity;
        _memoryReadScope = memoryReadScope;
        _onListening = onListening;
        _allowStandardObdClear = allowStandardObdClear;
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
                try
                {
                    await HandleClientAsync(client, cancellationToken);
                }
                catch (IOException)
                {
                    // A TLS peer closing its socket while the bridge writes a
                    // reply must never terminate the LAN listener.
                }
                catch (System.Net.Sockets.SocketException)
                {
                    // Network interruptions are scoped to the current client.
                }
                catch (AuthenticationException)
                {
                    // Per-peer handshake/record failure; no ECU operation.
                }
                catch (ObjectDisposedException)
                {
                    // A peer may close between socket acceptance and handling.
                }
                catch (DecoderFallbackException)
                {
                    // Invalid UTF-8 in a single request is not a server fault.
                }
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
        using var reader = new StreamReader(
            stream, new UTF8Encoding(false, true), detectEncodingFromByteOrderMarks: false,
            bufferSize: 4096, leaveOpen: true);
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
                await WriteErrorAsync(writer, "Invalid bridge request framing or size.", sessionTerminated: true);
                break;
            }
            catch (IOException) { break; }
            if (line is null) break;

            try
            {
                using var json = JsonDocument.Parse(line);
                var root = json.RootElement;
                if (!HasValidRequestToken(root, _token))
                {
                    // Reject the first unauthorized frame and close its TLS
                    // session. A local client must not keep trying passwords
                    // against one device-owning session or probe ECU operations.
                    await WriteErrorAsync(writer, "Bridge pairing authorization failed.", sessionTerminated: true);
                    break;
                }

                // Authentication alone is not request authorization. Reject
                // duplicate or unexpected fields before parsing CAN identifiers,
                // payloads, privileges or opening the native driver.
                if (!HasCanonicalRequestShape(root))
                {
                    await WriteErrorAsync(writer, "Noncanonical bridge request.", sessionTerminated: true);
                    break;
                }

                var operation = root.GetProperty("op").GetString();
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
                            readOnly = true, // Legacy exchange remains diagnostic only.
                            rawUdsConsoleAvailable = true, // Paired, explicit operator raw route.
                            scopedPhysicalMemoryRead = device.IsMemoryReadEnabled,
                            // The actual Windows read allowlist is the sole authority for
                            // region identity, session and memory boundaries. Android
                            // must verify these before labelling acquired bytes.
                            memoryReadVin = device.IsMemoryReadEnabled ? _memoryReadScope?.ExpectedVin : null,
                            memoryReadRegion = device.IsMemoryReadEnabled ? _memoryReadScope?.Region : null,
                            memoryReadStartAddress = device.IsMemoryReadEnabled ? _memoryReadScope?.StartAddress : null,
                            memoryReadLengthBytes = device.IsMemoryReadEnabled ? _memoryReadScope?.Length : null,
                            memoryReadMaxReadBytes = device.IsMemoryReadEnabled ? _memoryReadScope?.MaxReadBytes : null,
                            memoryReadSession = device.IsMemoryReadEnabled ? _memoryReadScope?.Session : null,
                            standardObdPhysicalRead = device.IsStandardObdReadEnabled,
                            standardObdClear = _allowStandardObdClear && device.IsStandardObdReadEnabled
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
                        try
                        {
                            var identity = await Task.Run(
                                () => device.ReadGmlanIdentification(), cancellationToken);
                            await writer.WriteLineAsync(JsonSerializer.Serialize(new
                            {
                                ok = true,
                                identityAvailable = true,
                                identity,
                                responseCanId = device.ResponseCanIdLabel,
                                readOnly = true,
                                flashQualified = false
                            }));
                        }
                        catch (NativeGmlanIdentification.IdentityUnavailableException)
                        {
                            await writer.WriteLineAsync(JsonSerializer.Serialize(new
                            {
                                ok = true, identityAvailable = false,
                                responseCanId = device.ResponseCanIdLabel,
                                readOnly = true, flashQualified = false
                            }));
                        }
                        catch (TimeoutException)
                        {
                            // Mandatory legacy VIN read was silent. The approved
                            // UDS F190 scope is still independently enforced.
                            await writer.WriteLineAsync(JsonSerializer.Serialize(new
                            {
                                ok = true, identityAvailable = false,
                                responseCanId = device.ResponseCanIdLabel,
                                readOnly = true, flashQualified = false
                            }));
                        }
                        break;
                    }
                    case "clearStandardObdDtcs":
                    {
                        if (!_allowStandardObdClear || !device.IsStandardObdReadEnabled ||
                            !root.TryGetProperty("confirmed", out var confirmation) ||
                            confirmation.ValueKind != JsonValueKind.True)
                            throw new UnauthorizedAccessException("Explicit emissions DTC clear consent is required.");
                        await Task.Run(() => device.ClearStandardObdDtcs(_allowStandardObdClear), cancellationToken);
                        await writer.WriteLineAsync(JsonSerializer.Serialize(new
                        {
                            ok = true, payloadHex = "44", responseCanId = device.ResponseCanIdLabel,
                            permanentDtcsCleared = false
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
                    // Explicit operator UDS route, on the already authenticated
                    // TLS/J2534 session. Do not impose a second CamaroDiag key
                    // or a read-only service allowlist: the ECU's actual protocol
                    // controls SecurityAccess and acknowledges each request.
                    // Keep this separate from background diagnostic exchanges.
                    case "exchangeRaw":
                    {
                        if (!HasExplicitRawWriteConsent(root))
                            throw new UnauthorizedAccessException(
                                "Explicit warning OK and subsequent YES are required before raw ECU transmission.");
                        var payload = ParseHexPayload(root);
                        var timeout = root.TryGetProperty("timeoutMs", out var timeoutElement)
                            ? timeoutElement.GetInt32() : 3000;
                        var response = await Task.Run(
                            () => device.ExchangeConsole(payload, timeout), cancellationToken);
                        await writer.WriteLineAsync(JsonSerializer.Serialize(new
                            { ok = true, payloadHex = Convert.ToHexString(response) }));
                        break;
                    }
                    case "sendRaw":
                    {
                        if (!HasExplicitRawWriteConsent(root))
                            throw new UnauthorizedAccessException(
                                "Explicit warning OK and subsequent YES are required before raw ECU transmission.");
                        var payload = ParseHexPayload(root);
                        await Task.Run(() => device.SendConsoleOnly(payload), cancellationToken);
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
            catch (JsonException)
            {
                // Malformed protocol or duplicate security field attempts must
                // not keep an authenticated transport session alive.
                await WriteErrorAsync(writer, "Malformed bridge request.", sessionTerminated: true);
                break;
            }
            catch (Exception ex) when (RequiresSessionTermination(ex))
            {
                // Authorization failures and uncertain physical I/O invalidate
                // the device-owning TLS session. Do not let the same client
                // retry privileged services or reuse a questionable ECU state.
                await WriteErrorAsync(writer, "J2534 operation denied or physical channel failed; reconnect and requalify.", sessionTerminated: true);
                break;
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
        var endingCarriageReturn = false;
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
            if (character[0] == '\r')
            {
                if (endingCarriageReturn)
                    throw new InvalidDataException("Only one terminal CR is allowed in a bridge request.");
                endingCarriageReturn = true;
                continue;
            }
            // All wire keys, hex payloads, token and command values in this
            // protocol use printable ASCII. Reject other raw characters,
            // including UTF-8 multibyte data, to enforce the 64 KiB budget
            // in BYTES as well as characters and prevent parser ambiguities.
            if (endingCarriageReturn || character[0] < ' ' || character[0] > '~')
                throw new InvalidDataException("Noncanonical bridge request framing.");
            line.Append(character[0]);
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

    /// <summary>
    /// Authentication precedes operation decoding, driver access and CAN I/O.
    /// A non-object root, missing/malformed bearer token or mismatched token
    /// denies the entire TLS session, not merely the individual operation.
    /// </summary>
    internal static bool HasValidRequestToken(JsonElement root, string expectedToken)
    {
        if (root.ValueKind != JsonValueKind.Object ||
            !root.TryGetProperty("token", out var token) ||
            token.ValueKind != JsonValueKind.String)
            return false;
        // Do not let last-key-wins JSON parsers reinterpret credentials
        // or route an authenticated request to a different operation.
        int tokens = 0;
        int operations = 0;
        foreach (var field in root.EnumerateObject())
        {
            if (field.NameEquals("token")) ++tokens;
            if (field.NameEquals("op")) ++operations;
        }
        if (tokens != 1 || operations != 1 ||
            !root.TryGetProperty("op", out var operation) ||
            operation.ValueKind != JsonValueKind.String ||
            string.IsNullOrWhiteSpace(operation.GetString()))
            return false;
        var supplied = token.GetString();
        if (string.IsNullOrEmpty(supplied) || string.IsNullOrEmpty(expectedToken))
            return false;
        var expected = Encoding.UTF8.GetBytes(expectedToken);
        var actual = Encoding.UTF8.GetBytes(supplied);
        return expected.Length == actual.Length &&
            CryptographicOperations.FixedTimeEquals(expected, actual);
    }

    /// <summary>
    /// Closed schema for the current JSON-lines J2534 protocol. In particular,
    /// duplicate payloadHex, confirmed, CAN ID or timeout fields are rejected:
    /// "last key wins" cannot reinterpret an authenticated CAN operation.
    /// Legacy clients may omit optional open defaults or timeoutMs.
    /// </summary>
    /// <summary>
    /// Explicit UI confirmation fields for raw ECU transmissions. This is
    /// operator acknowledgement, NOT a new ECU signing key or manufacturer
    /// SecurityAccess replacement. Require both fields on the same request.
    /// </summary>
    internal static bool HasExplicitRawWriteConsent(JsonElement root) =>
        root.ValueKind == JsonValueKind.Object &&
        root.TryGetProperty("warningAcknowledged", out var warning) &&
        warning.ValueKind == JsonValueKind.True &&
        root.TryGetProperty("writeConfirmed", out var confirmation) &&
        confirmation.ValueKind == JsonValueKind.True;

    internal static bool HasCanonicalRequestShape(JsonElement root)
    {
        if (root.ValueKind != JsonValueKind.Object ||
            !root.TryGetProperty("op", out var operation) ||
            operation.ValueKind != JsonValueKind.String)
            return false;
        var op = operation.GetString();
        string[] required = op switch
        {
            "open" => ["token", "op", "requestCanId", "responseCanId"],
            "identify" or "identifyGmlan" or "close" => ["token", "op"],
            "clearStandardObdDtcs" => ["token", "op", "confirmed"],
            "exchange" or "sendOnly" => ["token", "op", "payloadHex"],
            "exchangeRaw" or "sendRaw" => ["token", "op", "payloadHex",
                "warningAcknowledged", "writeConfirmed"],
            _ => []
        };
        if (required.Length == 0) return false;
        string[] allowedExtra = op switch
        {
            "open" => ["can29Bit", "protocolNumber"],
            "exchange" or "sendOnly" or "exchangeRaw" or "sendRaw" => ["timeoutMs"],
            _ => []
        };
        var names = new HashSet<string>(StringComparer.Ordinal);
        foreach (var field in root.EnumerateObject())
        {
            if (!names.Add(field.Name) ||
                (!required.Contains(field.Name, StringComparer.Ordinal) &&
                 !allowedExtra.Contains(field.Name, StringComparer.Ordinal)))
                return false;
            switch (field.Name)
            {
                case "token" or "op" or "requestCanId" or "responseCanId" or "payloadHex":
                    if (field.Value.ValueKind != JsonValueKind.String) return false;
                    break;
                case "can29Bit" or "confirmed" or "warningAcknowledged" or "writeConfirmed":
                    if (field.Value.ValueKind is not (JsonValueKind.True or JsonValueKind.False))
                        return false;
                    break;
                case "protocolNumber" or "timeoutMs":
                    if (field.Value.ValueKind != JsonValueKind.Number ||
                        !field.Value.TryGetInt32(out _))
                        return false;
                    break;
            }
        }
        return required.All(names.Contains);
    }

    /// <summary>
    /// Unsafe requests and uncertain native driver state terminate the entire
    /// bridge session. An ordinary ECU silence (TimeoutException) is not
    /// independently proof of a lost CAN device and may remain recoverable.
    /// </summary>
    internal static bool RequiresSessionTermination(Exception failure) =>
        failure is UnauthorizedAccessException or InvalidDataException or IOException;

    internal static int ParseHexId(JsonElement root, string property)
    {
        if (!root.TryGetProperty(property, out var element)) throw new ArgumentException($"Missing {property}.");
        var text = element.GetString()?.Trim();
        if (text != null && text.StartsWith("0x", StringComparison.OrdinalIgnoreCase))
            text = text[2..];
        // Only a single, leading 0x marker may be omitted. Never silently
        // rewrite malformed input like "70xE0" into a different CAN target.
        if (string.IsNullOrWhiteSpace(text) || text.Length > 8 ||
            !text.All(Uri.IsHexDigit) ||
            !int.TryParse(text, System.Globalization.NumberStyles.HexNumber,
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

    internal static string SerializeErrorResponse(string message, bool sessionTerminated) =>
        JsonSerializer.Serialize(new { ok = false, error = message, sessionTerminated });

    private static Task WriteErrorAsync(StreamWriter writer, string message,
        bool sessionTerminated = false) =>
        writer.WriteLineAsync(SerializeErrorResponse(message, sessionTerminated));
}
