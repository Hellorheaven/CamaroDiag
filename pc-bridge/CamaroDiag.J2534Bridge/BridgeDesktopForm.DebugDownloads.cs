using System.Diagnostics;
using System.Drawing;
using System.Net.Http;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Windows.Forms;

namespace CamaroDiag.J2534Bridge;

internal sealed partial class BridgeDesktopForm
{
    private sealed record GitHubDebugArtifact(
        long Id, long RunId, string Name, string Sha,
        DateTimeOffset CreatedAt, DateTimeOffset ExpiresAt)
    {
        internal string BrowserUrl =>
            "https://github.com/Hellorheaven/CamaroDiag/actions/runs/" +
            RunId + "/artifacts/" + Id;
    }

    private sealed record DebugPullRequest(int Number, string Branch);

    private static readonly HttpClient DebugArtifactClient = new()
    {
        Timeout = TimeSpan.FromSeconds(20)
    };
    private readonly Label debugBuildAuthStatus = new()
    {
        AutoSize = true, Dock = DockStyle.Fill,
        Text = "Compte Google propriétaire requis pour accéder aux versions Debug."
    };
    private readonly Button debugBuildGoogleSignIn = Button("Connexion Google");
    private readonly Button debugBuildRefresh = Button("Actualiser");
    private readonly Button debugBuildAndroidOpen = Button("Télécharger Android Debug");
    private readonly Button debugBuildWindowsOpen = Button("Télécharger Windows Debug x64/x86");
    private readonly TextBox debugBuildResults = new()
    {
        Multiline = true, ScrollBars = ScrollBars.Vertical, ReadOnly = true,
        Dock = DockStyle.Fill, Font = new Font("Consolas", 9.5f)
    };
    private GitHubDebugArtifact? currentAndroidDebug;
    private GitHubDebugArtifact? currentWindowsDebug;
    private bool debuggingBuildRefreshInProgress;

    private TabPage BuildDebugDownloadsTab()
    {
        var tab = new TabPage("Téléchargements Debug");
        var panel = Grid();
        // WideRow(panel, new Label
        // {
            // AutoSize = true, Dock = DockStyle.Fill, ForeColor = Color.DarkSlateGray,
            // Text = "Versions Debug de la PR active. Chaque ZIP provient d'un véritable " +
                   // "artefact GitHub Actions non expiré. Le téléchargement s'ouvre " +
                   // "dans votre navigateur : GitHub exige sa propre connexion."
        // }, 65);
        Row(panel, "Compte privé", debugBuildAuthStatus, debugBuildGoogleSignIn, 56);
        WideRow(panel, debugBuildRefresh, 43);
        WideRow(panel, debugBuildAndroidOpen, 47);
        WideRow(panel, debugBuildWindowsOpen, 47);
        WideRow(panel, new Label
        {
            AutoSize = true, Dock = DockStyle.Fill, ForeColor = Color.DarkSlateGray,
            Text = "L'APK Android et les installateurs Windows x64/x86 sont dans les " +
                   "ZIP distincts. Un artefact disponible n'est pas une validation CI " +
                   "ou matériel. Les artefacts GitHub expirent après 30 jours."
        }, 63);
        WideRow(panel, debugBuildResults, 155);
        debugBuildGoogleSignIn.Click += async (_, _) =>
        {
            if (debuggingBuildRefreshInProgress) return;
            await FunctionalGoogleSignInAsync();
            UpdateDebugBuildAuthentication();
            if (DateTimeOffset.UtcNow < functionalAuthorizedUntil)
                await RefreshDebugBuildsAsync();
        };
        debugBuildRefresh.Click += async (_, _) => await RefreshDebugBuildsAsync();
        debugBuildAndroidOpen.Click += (_, _) => OpenDebugArtifact(currentAndroidDebug);
        debugBuildWindowsOpen.Click += (_, _) => OpenDebugArtifact(currentWindowsDebug);
        debugBuildRefresh.Enabled = false;
        debugBuildAndroidOpen.Enabled = false;
        debugBuildWindowsOpen.Enabled = false;
        tab.Controls.Add(panel);
        return tab;
    }

    private void UpdateDebugBuildAuthentication()
    {
        var allowed = DateTimeOffset.UtcNow < functionalAuthorizedUntil;
        debugBuildAuthStatus.Text = allowed
            ? "Autorisation Firebase Google active (durée limitée)."
            : "Non autorisé : connexion Google requise.";
        debugBuildRefresh.Enabled = allowed && !debuggingBuildRefreshInProgress;
        debugBuildAndroidOpen.Enabled = allowed &&
            currentAndroidDebug?.ExpiresAt > DateTimeOffset.UtcNow;
        debugBuildWindowsOpen.Enabled = allowed &&
            currentWindowsDebug?.ExpiresAt > DateTimeOffset.UtcNow;
    }

    private async Task RefreshDebugBuildsAsync()
    {
        if (debuggingBuildRefreshInProgress) return;
        if (DateTimeOffset.UtcNow >= functionalAuthorizedUntil)
        {
            currentAndroidDebug = null;
            currentWindowsDebug = null;
            UpdateDebugBuildAuthentication();
            return;
        }
        debuggingBuildRefreshInProgress = true;
        debugBuildRefresh.Enabled = debugBuildAndroidOpen.Enabled =
            debugBuildWindowsOpen.Enabled = false;
        debugBuildResults.Text = "Recherche GitHub Actions en cours...";
        try
        {
            var result = await FetchDebugArtifactsAsync();
            if (IsDisposed || Disposing) return;
            if (DateTimeOffset.UtcNow >= functionalAuthorizedUntil)
            {
                currentAndroidDebug = null;
                currentWindowsDebug = null;
                debugBuildResults.Text = "La session Google a expiré.";
                return;
            }
            currentAndroidDebug = result.Android;
            currentWindowsDebug = result.Windows;
            static string Describe(string label, GitHubDebugArtifact? item) =>
                item is null
                    ? label + " : aucun artefact non expiré pour la PR active."
                    : label + Environment.NewLine +
                      "  Commit : " + item.Sha[..12] + Environment.NewLine +
                      "  Création UTC : " + item.CreatedAt.ToString("u") + Environment.NewLine +
                      "  Expiration UTC : " + item.ExpiresAt.ToString("u");
            debugBuildResults.Text = Describe("Android Debug", currentAndroidDebug) +
                Environment.NewLine + Environment.NewLine +
                Describe("Windows J2534 Debug (x64/x86)", currentWindowsDebug);
        }
        catch (Exception error) when (error is HttpRequestException or
            TaskCanceledException or JsonException or InvalidOperationException)
        {
            currentAndroidDebug = null;
            currentWindowsDebug = null;
            if (!IsDisposed && !Disposing)
                debugBuildResults.Text =
                    "Impossible de lire GitHub Actions : " + error.GetType().Name +
                    ". Réessayez après avoir vérifié Internet.";
        }
        finally
        {
            debuggingBuildRefreshInProgress = false;
            if (!IsDisposed && !Disposing) UpdateDebugBuildAuthentication();
        }
    }

    private sealed record DebugArtifactListing(GitHubDebugArtifact? Android, GitHubDebugArtifact? Windows);

    private static async Task<DebugPullRequest?> FetchActivePullRequestAsync()
    {
        const string repo = "Hellorheaven/CamaroDiag";
        var endpoint = "https://api.github.com/repos/" + repo + "/pulls?state=open&per_page=100";
        using var request = new HttpRequestMessage(HttpMethod.Get, endpoint);
        request.Headers.TryAddWithoutValidation("User-Agent", "CamaroDiag-Debug-Builds");
        request.Headers.TryAddWithoutValidation("Accept", "application/vnd.github+json");
        using var response = await DebugArtifactClient.SendAsync(request);
        response.EnsureSuccessStatusCode();
        using var data = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        DebugPullRequest? active = null;
        foreach (var item in data.RootElement.EnumerateArray())
        {
            if (!item.TryGetProperty("base", out var basePr) ||
                basePr.GetProperty("ref").GetString() != "main" ||
                !item.TryGetProperty("head", out var headPr) ||
                !headPr.TryGetProperty("repo", out var headRepo) ||
                headRepo.ValueKind != JsonValueKind.Object ||
                headRepo.GetProperty("full_name").GetString() != repo) continue;
            var number = item.GetProperty("number").GetInt32();
            var branch = headPr.GetProperty("ref").GetString();
            if (number <= 0 || string.IsNullOrWhiteSpace(branch)) continue;
            if (active is not null)
                throw new InvalidOperationException("Multiple active CamaroDiag PRs.");
            active = new DebugPullRequest(number, branch);
        }
        return active;
    }

    private static async Task<DebugArtifactListing> FetchDebugArtifactsAsync()
    {
        var pr = await FetchActivePullRequestAsync();
        if (pr is null) return new DebugArtifactListing(null, null);
        var candidates = new List<(GitHubDebugArtifact Item, bool Android)>();
        for (var page = 1; page <= 4; page++)
        {
            var endpoint = "https://api.github.com/repos/Hellorheaven/CamaroDiag/actions/artifacts" +
                "?per_page=100&page=" + page;
            using var request = new HttpRequestMessage(HttpMethod.Get, endpoint);
            request.Headers.TryAddWithoutValidation("User-Agent", "CamaroDiag-Owner-Debug-Builds");
            request.Headers.TryAddWithoutValidation("Accept", "application/vnd.github+json");
            using var response = await DebugArtifactClient.SendAsync(request);
            response.EnsureSuccessStatusCode();
            using var json = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
            var entries = json.RootElement.GetProperty("artifacts");
            foreach (var element in entries.EnumerateArray())
            {
                if (!element.TryGetProperty("workflow_run", out var run) ||
                    run.ValueKind != JsonValueKind.Object) continue;
                var name = element.GetProperty("name").GetString() ?? "";
                var android = Regex.Match(name, "^CamaroDiag-debug-pr-" + pr.Number + "-([0-9a-f]{40})$");
                var windows = Regex.Match(name, "^CamaroDiag-J2534-Bridge-debug-pr-" + pr.Number + "-([0-9a-f]{40})$");
                if (!android.Success && !windows.Success) continue;
                var match = android.Success ? android : windows;
                if (run.GetProperty("head_branch").GetString() != pr.Branch ||
                    run.GetProperty("head_sha").GetString() != match.Groups[1].Value ||
                    element.GetProperty("expired").GetBoolean()) continue;
                var id = element.GetProperty("id").GetInt64();
                var runId = run.GetProperty("id").GetInt64();
                if (id <= 0 || runId <= 0 ||
                    !DateTimeOffset.TryParse(element.GetProperty("created_at").GetString(), out var created) ||
                    !DateTimeOffset.TryParse(element.GetProperty("expires_at").GetString(), out var expires) ||
                    expires <= DateTimeOffset.UtcNow) continue;
                candidates.Add((
                    new GitHubDebugArtifact(id, runId, name, match.Groups[1].Value, created, expires),
                    android.Success
                ));
            }
            if (entries.GetArrayLength() < 100 ||
                (candidates.Any(x => x.Android) && candidates.Any(x => !x.Android))) break;
        }
        return new DebugArtifactListing(
            candidates.Where(x => x.Android).Select(x => x.Item).OrderByDescending(x => x.CreatedAt).FirstOrDefault(),
            candidates.Where(x => !x.Android).Select(x => x.Item).OrderByDescending(x => x.CreatedAt).FirstOrDefault()
        );
    }

    private void OpenDebugArtifact(GitHubDebugArtifact? item)
    {
        if (item is null || DateTimeOffset.UtcNow >= functionalAuthorizedUntil ||
            item.ExpiresAt <= DateTimeOffset.UtcNow)
        {
            UpdateDebugBuildAuthentication();
            ShowError("Téléchargement indisponible : reconnectez-vous ou actualisez les versions.");
            return;
        }
        Process.Start(new ProcessStartInfo(item.BrowserUrl) { UseShellExecute = true });
    }
}
