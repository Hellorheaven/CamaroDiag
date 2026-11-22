using System.Diagnostics;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Interactive desktop Google sign-in via the system browser and PKCE.
/// A Firebase-signed ID token must carry the provisioned owner claim.
/// No email, token, HMAC key or password is bundled or stored on disk.
/// </summary>
internal static class DesktopOwnerGoogleAuth
{
    private static readonly HttpClient Http = new()
    {
        Timeout = TimeSpan.FromSeconds(30)
    };

    internal static async Task<DateTimeOffset> SignInAsync()
    {
        var clientId = Environment.GetEnvironmentVariable("CAMARODIAG_GOOGLE_DESKTOP_CLIENT_ID");
        var firebaseKey = Environment.GetEnvironmentVariable("CAMARODIAG_FIREBASE_WEB_API_KEY");
        var projectId = Environment.GetEnvironmentVariable("CAMARODIAG_FIREBASE_PROJECT_ID");
        if (string.IsNullOrWhiteSpace(clientId) ||
            string.IsNullOrWhiteSpace(firebaseKey) ||
            string.IsNullOrWhiteSpace(projectId))
            throw new InvalidOperationException(
                "Configurez d'abord les identifiants OAuth Desktop et le projet Firebase.");

        using var timeout = new CancellationTokenSource(TimeSpan.FromMinutes(3));
        var verifier = Base64Url(RandomNumberGenerator.GetBytes(64));
        var challenge = Base64Url(SHA256.HashData(Encoding.ASCII.GetBytes(verifier)));
        var state = Base64Url(RandomNumberGenerator.GetBytes(32));
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        try
        {
            var localPort = ((IPEndPoint)listener.LocalEndpoint).Port;
            var redirect = "http://127.0.0.1:" + localPort + "/";
            var authorize =
                "https://accounts.google.com/o/oauth2/v2/auth?response_type=code" +
                "&client_id=" + Uri.EscapeDataString(clientId) +
                "&redirect_uri=" + Uri.EscapeDataString(redirect) +
                "&scope=" + Uri.EscapeDataString("openid email") +
                "&code_challenge=" + Uri.EscapeDataString(challenge) +
                "&code_challenge_method=S256" +
                "&state=" + Uri.EscapeDataString(state) +
                "&prompt=select_account";
            Process.Start(new ProcessStartInfo(authorize) { UseShellExecute = true });
            using var client = await listener.AcceptTcpClientAsync(timeout.Token);
            using var network = client.GetStream();
            using var reader = new StreamReader(network, Encoding.ASCII,
                detectEncodingFromByteOrderMarks: false, bufferSize: 4096, leaveOpen: true);
            var request = await reader.ReadLineAsync(timeout.Token);
            if (request is null || !request.StartsWith("GET /?", StringComparison.Ordinal))
                throw new InvalidDataException("Réponse OAuth locale invalide.");
            var pieces = request.Split(' ');
            if (pieces.Length < 2) throw new InvalidDataException("Requête OAuth invalide.");
            var query = ParseQuery(new Uri("http://127.0.0.1:" + localPort + pieces[1]).Query);
            string? headerLine;
            do { headerLine = await reader.ReadLineAsync(timeout.Token); }
            while (!string.IsNullOrEmpty(headerLine));
            var validState = query.TryGetValue("state", out var supplied) &&
                CryptographicOperations.FixedTimeEquals(
                    Encoding.UTF8.GetBytes(state), Encoding.UTF8.GetBytes(supplied));
            var validCode = query.TryGetValue("code", out var code) &&
                !string.IsNullOrWhiteSpace(code);
            var responseText = validState && validCode
                ? "Authentification reçue. Vous pouvez fermer cette fenêtre."
                : "Authentification refusée. Fermez cette fenêtre.";
            var responseBytes = Encoding.UTF8.GetBytes(responseText);
            var httpResponse = Encoding.ASCII.GetBytes(
                "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\nContent-Length: " +
                responseBytes.Length + "\r\n\r\n");
            await network.WriteAsync(httpResponse, timeout.Token);
            await network.WriteAsync(responseBytes, timeout.Token);
            await network.FlushAsync(timeout.Token);
            if (!validState || !validCode)
                throw new UnauthorizedAccessException("OAuth state/code invalid.");
            var googleToken = await ExchangeGoogleCodeAsync(
                clientId, code!, redirect, verifier, timeout.Token);
            var firebaseToken = await FirebaseSignInAsync(
                firebaseKey, googleToken, redirect, timeout.Token);
            return await VerifyFirebaseOwnerTokenAsync(
                firebaseToken, projectId, timeout.Token);
        }
        finally
        {
            listener.Stop();
        }
    }

    private static Dictionary<string, string> ParseQuery(string query)
    {
        var result = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var pair in query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var kv = pair.Split('=', 2);
            if (kv.Length == 2)
                result[Uri.UnescapeDataString(kv[0])] =
                    Uri.UnescapeDataString(kv[1].Replace('+', ' '));
        }
        return result;
    }

    private static async Task<string> ExchangeGoogleCodeAsync(
        string clientId, string code, string redirect, string verifier, CancellationToken cancellation)
    {
        var values = new Dictionary<string, string>
        {
            ["client_id"] = clientId,
            ["code"] = code,
            ["redirect_uri"] = redirect,
            ["grant_type"] = "authorization_code",
            ["code_verifier"] = verifier
        };
        // Desktop OAuth client secrets are public-client identifiers, never
        // owner secrets; Google can require the value for some installed clients.
        var desktopSecret = Environment.GetEnvironmentVariable(
            "CAMARODIAG_GOOGLE_DESKTOP_CLIENT_SECRET");
        if (!string.IsNullOrEmpty(desktopSecret)) values["client_secret"] = desktopSecret;
        using var body = new FormUrlEncodedContent(values);
        using var response = await Http.PostAsync(
            "https://oauth2.googleapis.com/token", body, cancellation);
        response.EnsureSuccessStatusCode();
        using var document = JsonDocument.Parse(
            await response.Content.ReadAsStringAsync(cancellation));
        return document.RootElement.GetProperty("id_token").GetString() ??
            throw new InvalidDataException("No Google ID token returned.");
    }

    private static async Task<string> FirebaseSignInAsync(
        string apiKey, string googleToken, string redirect, CancellationToken cancellation)
    {
        var json = JsonSerializer.Serialize(new
        {
            requestUri = redirect,
            postBody = "id_token=" + Uri.EscapeDataString(googleToken) + "&providerId=google.com",
            returnSecureToken = true,
            returnIdpCredential = false
        });
        using var body = new StringContent(json, Encoding.UTF8, "application/json");
        using var response = await Http.PostAsync(
            "https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=" +
                Uri.EscapeDataString(apiKey), body, cancellation);
        response.EnsureSuccessStatusCode();
        using var document = JsonDocument.Parse(
            await response.Content.ReadAsStringAsync(cancellation));
        return document.RootElement.GetProperty("idToken").GetString() ??
            throw new InvalidDataException("Firebase did not return a token.");
    }

    internal static async Task<DateTimeOffset> VerifyFirebaseOwnerTokenAsync(
        string token, string projectId, CancellationToken cancellation)
    {
        var pieces = token.Split('.');
        if (pieces.Length != 3) throw new UnauthorizedAccessException("Invalid Firebase JWT.");
        using var header = JsonDocument.Parse(Decode(pieces[0]));
        using var claims = JsonDocument.Parse(Decode(pieces[1]));
        var head = header.RootElement;
        var payload = claims.RootElement;
        if (head.GetProperty("alg").GetString() != "RS256" ||
            !head.TryGetProperty("kid", out var keyId) ||
            string.IsNullOrWhiteSpace(keyId.GetString()))
            throw new UnauthorizedAccessException("Unsupported Firebase signature.");
        using var certificates = JsonDocument.Parse(await Http.GetStringAsync(
            "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com",
            cancellation));
        if (!certificates.RootElement.TryGetProperty(keyId.GetString()!, out var pem))
            throw new UnauthorizedAccessException("Firebase signing key not recognized.");
        using var cert = X509Certificate2.CreateFromPem(pem.GetString()!);
        using var rsa = cert.GetRSAPublicKey();
        if (rsa is null || !rsa.VerifyData(
            Encoding.ASCII.GetBytes(pieces[0] + "." + pieces[1]),
            Decode(pieces[2]), HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1))
            throw new UnauthorizedAccessException("Firebase token signature invalid.");

        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        if (!payload.TryGetProperty("aud", out var audience) ||
            audience.GetString() != projectId ||
            !payload.TryGetProperty("iss", out var issuer) ||
            issuer.GetString() != "https://securetoken.google.com/" + projectId ||
            !payload.TryGetProperty("sub", out var subject) ||
            string.IsNullOrWhiteSpace(subject.GetString()) ||
            !payload.TryGetProperty("exp", out var expiry) ||
            expiry.GetInt64() <= now ||
            !payload.TryGetProperty("iat", out var issued) ||
            issued.GetInt64() > now + 60 ||
            !payload.TryGetProperty("auth_time", out var authenticationTime) ||
            authenticationTime.GetInt64() > now + 60 ||
            !payload.TryGetProperty("owner_functional_tests", out var owner) ||
            owner.ValueKind != JsonValueKind.True ||
            !payload.TryGetProperty("firebase", out var firebase) ||
            !firebase.TryGetProperty("sign_in_provider", out var signInProvider) ||
            signInProvider.GetString() != "google.com")
            throw new UnauthorizedAccessException("Google account not authorized for private tests.");

        var signedExpiry = DateTimeOffset.FromUnixTimeSeconds(expiry.GetInt64());
        var shortExpiry = DateTimeOffset.UtcNow.AddMinutes(10);
        return signedExpiry < shortExpiry ? signedExpiry : shortExpiry;
    }

    private static string Base64Url(byte[] data) =>
        Convert.ToBase64String(data).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static byte[] Decode(string encoded)
    {
        var padded = encoded.Replace('-', '+').Replace('_', '/');
        return Convert.FromBase64String(padded.PadRight((padded.Length + 3) / 4 * 4, '='));
    }
}
