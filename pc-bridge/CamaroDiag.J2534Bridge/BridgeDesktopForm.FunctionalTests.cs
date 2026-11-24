using System.Drawing;
using System.Text;
using System.Text.Json;
using System.Windows.Forms;

namespace CamaroDiag.J2534Bridge;

internal sealed partial class BridgeDesktopForm
{
    private sealed record WindowsFunctionalCheck(
        string Group, string Id, string Label, string Status, string Detail);

    private readonly Button functionalGoogleSignIn = Button("Se connecter avec Google");
    private readonly Button functionalRunAll = Button("Tout lancer");
    private readonly Button functionalCancel = Button("Arrêter");
    private readonly Button functionalExport = Button("Exporter JSON");
    private readonly CheckBox functionalEcmProfileConfirmed = new()
    {
        Text = "J'ai vérifié les CAN IDs / le protocole du CALCULATEUR MOTEUR (ECM).",
        AutoSize = true, Dock = DockStyle.Left
    };
    private readonly Label functionalAuthentication = new()
    {
        Text = "Tests privés verrouillés — compte Google administrateur requis.",
        AutoSize = true, ForeColor = Color.DarkSlateGray, Dock = DockStyle.Fill
    };
    private readonly TextBox functionalResults = new()
    {
        Multiline = true, ScrollBars = ScrollBars.Both, ReadOnly = true,
        Dock = DockStyle.Fill, Font = new Font("Consolas", 9.5f),
        WordWrap = false
    };
    private DateTimeOffset functionalAuthorizedUntil = DateTimeOffset.MinValue;
    private CancellationTokenSource? functionalCancellation;
    private List<WindowsFunctionalCheck> completedFunctionalChecks = [];

    private TabPage BuildFunctionalTestsTab()
    {
        var tab = new TabPage("Tests fonctionnels privés");
        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill, RowCount = 2, ColumnCount = 1
        };
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 39));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 61));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        var fields = Grid();
        // WideRow(fields, new Label
        // {
            // Text = "Campagne de recette lecture seule : pilotes J2534, " +
                   // "connexion ECU sur le profil vérifié, et liste de tous les calculateurs. " +
                   // "Bluetooth, BLE, USB ELM/STN et Wi-Fi se testent sur Android.",
            // AutoSize = true, ForeColor = Color.DarkSlateGray
        // }, 59);
        Row(fields, "Accès privé", functionalAuthentication, functionalGoogleSignIn, 52);
        WideRow(fields, functionalEcmProfileConfirmed, 48);
        var actions = new FlowLayoutPanel
        {
            FlowDirection = FlowDirection.LeftToRight, Dock = DockStyle.Fill
        };
        actions.Controls.Add(functionalRunAll);
        actions.Controls.Add(functionalCancel);
        actions.Controls.Add(functionalExport);
        WideRow(fields, actions, 48);
        WideRow(fields, new Label
        {
            Text = "Le bouton « Tout lancer » ne programme, n'efface et ne commande " +
                   "aucun actionneur. Les calculateurs sans adressage physique validé " +
                   "restent NON TESTÉS, jamais OK. Le test physique ECM nécessite " +
                   "l'arrêt du pont et de la console.",
            AutoSize = true, ForeColor = Color.DarkSlateGray
        }, 64);

        functionalGoogleSignIn.Click += async (_, _) => await FunctionalGoogleSignInAsync();
        functionalRunAll.Click += async (_, _) => await RunAllWindowsFunctionalTestsAsync();
        functionalCancel.Click += (_, _) => functionalCancellation?.Cancel();
        functionalExport.Click += (_, _) => ExportWindowsFunctionalResults();
        functionalRunAll.Enabled = functionalCancel.Enabled = functionalExport.Enabled = false;
        layout.Controls.Add(fields, 0, 0);
        layout.Controls.Add(functionalResults, 0, 1);
        tab.Controls.Add(layout);
        return tab;
    }

    private async Task FunctionalGoogleSignInAsync()
    {
        if (functionalCancellation is not null) return;
        functionalGoogleSignIn.Enabled = false;
        functionalAuthentication.Text = "Connexion Google dans le navigateur système...";
        functionalRunAll.Enabled = false;
        functionalAuthorizedUntil = DateTimeOffset.MinValue;
        try
        {
            functionalAuthorizedUntil = await DesktopOwnerGoogleAuth.SignInAsync();
            functionalAuthentication.Text =
                "Compte Google autorisé par Firebase (session privée de 10 min maximum).";
            functionalRunAll.Enabled = true;
        }
        catch (Exception)
        {
            functionalAuthentication.Text =
                "Accès refusé ou indisponible. Aucun compte autorisé n'a été confirmé.";
            ShowError("La connexion Google ou le droit administrateur Firebase a échoué.");
        }
        finally
        {
            functionalGoogleSignIn.Enabled = true;
        }
    }

    private async Task RunAllWindowsFunctionalTestsAsync()
    {
        if (functionalCancellation is not null) return;
        if (DateTimeOffset.UtcNow >= functionalAuthorizedUntil)
        {
            functionalRunAll.Enabled = false;
            functionalAuthentication.Text = "Session expirée : reconnectez votre compte Google.";
            return;
        }
        if (bridgeTask is { IsCompleted: false } || consoleDevice is not null || busy)
        {
            ShowError("Arrêtez le pont, fermez la console et terminez les autres opérations.");
            return;
        }

        var selected = adapter.SelectedItem as AdapterChoice;
        var selectedRequest = requestId.Text.Trim();
        var selectedResponse = responseId.Text.Trim();
        var selectedProtocol = protocol.SelectedItem?.ToString() ?? "33";
        var verifiedEcmProfile = functionalEcmProfileConfirmed.Checked;
        NativeGmlanBenchProbe.Options? options = null;
        if (verifiedEcmProfile)
        {
            try
            {
                options = NativeGmlanBenchProbe.Parse([
                    NativeGmlanBenchProbe.Command,
                    "--adapter-index", adapter.SelectedIndex.ToString(),
                    "--request-can-id", selectedRequest,
                    "--response-can-id", selectedResponse,
                    "--protocol", selectedProtocol
                ]);
            }
            catch (ArgumentException error)
            {
                ShowError("Le profil ECM confirmé est invalide : " + error.Message);
                return;
            }
        }

        busy = true;
        functionalRunAll.Enabled = functionalGoogleSignIn.Enabled = functionalExport.Enabled = false;
        functionalCancel.Enabled = true;
        functionalCancellation = new CancellationTokenSource();
        var cancellation = functionalCancellation;
        functionalResults.Clear();
        functionalResults.AppendText("Campagne en cours — seule la lecture est autorisée." +
            Environment.NewLine);
        try
        {
            var rows = await Task.Run(() =>
                RunAllWindowsFunctionalChecks(selected?.Adapter, options,
                    functionalAuthorizedUntil, cancellation.Token));
            completedFunctionalChecks = rows;
            DisplayWindowsFunctionalChecks(rows);
            functionalExport.Enabled = true;
        }
        catch (OperationCanceledException)
        {
            functionalResults.AppendText("Campagne annulée ; aucun test incomplet déclaré OK." +
                Environment.NewLine);
            completedFunctionalChecks = [];
        }
        catch (Exception error)
        {
            functionalResults.AppendText("Échec de la campagne : " +
                error.GetType().Name + Environment.NewLine);
            completedFunctionalChecks = [];
        }
        finally
        {
            cancellation.Dispose();
            functionalCancellation = null;
            busy = false;
            functionalCancel.Enabled = false;
            functionalGoogleSignIn.Enabled = true;
            functionalRunAll.Enabled = DateTimeOffset.UtcNow < functionalAuthorizedUntil;
        }
    }

    private static List<WindowsFunctionalCheck> RunAllWindowsFunctionalChecks(
        J2534Adapter? selected, NativeGmlanBenchProbe.Options? ecmProfile,
        DateTimeOffset accessExpiry, CancellationToken cancellation)
    {
        var rows = new List<WindowsFunctionalCheck>();
        void RequireActive()
        {
            cancellation.ThrowIfCancellationRequested();
            if (DateTimeOffset.UtcNow >= accessExpiry)
                throw new OperationCanceledException("Owner authorization expired.");
        }
        RequireActive();
        foreach (var transport in new[] { "Bluetooth classique / ELM327", "Bluetooth LE / OBDLink",
            "USB OTG ELM / STN", "Wi-Fi ELM327" })
            rows.Add(new WindowsFunctionalCheck(
                "connector", transport, transport, "NON TESTÉ",
                "Canal Android : aucun essai matériel Windows effectué."));

        var adapters = J2534Registry.Discover();
        if (adapters.Count == 0)
            rows.Add(new WindowsFunctionalCheck(
                "connector", "J2534", "Pilote J2534", "NON TESTÉ",
                "Aucun pilote constructeur installé."));
        foreach (var item in adapters)
        {
            RequireActive();
            try
            {
                var result = J2534DriverProbe.Probe(item);
                rows.Add(new WindowsFunctionalCheck(
                    "connector", item.DisplayName, item.DisplayName,
                    result.DriverOperational ? "OK" : "ÉCHEC", result.Detail));
            }
            catch (Exception error)
            {
                rows.Add(new WindowsFunctionalCheck(
                    "connector", item.DisplayName, item.DisplayName, "ÉCHEC",
                    "Sonde J2534 : " + error.GetType().Name));
            }
        }

        RequireActive();
        try
        {
            var offlineTests = BridgeSelfTests.Run();
            rows.Add(new WindowsFunctionalCheck(
                "software", "selftest", "Autotests logiciel du pont",
                offlineTests == 0 ? "OK" : "ÉCHEC",
                "Vérifications logicielles hors ligne — pas une preuve de contact ECU."));
        }
        catch (Exception error)
        {
            rows.Add(new WindowsFunctionalCheck(
                "software", "selftest", "Autotests logiciel du pont", "ÉCHEC",
                error.GetType().Name));
        }

        foreach (var module in new[]
        {
            "ECM", "TCM", "BCM", "IPC", "EBCM/ABS", "SDM/AIRBAG", "HVAC",
            "PSCM", "RCDLR", "RADIO", "AMP", "DDM", "PDM", "FPCM",
            "PARK_ASSIST", "HMI"
        })
        {
            rows.Add(new WindowsFunctionalCheck(
                "ecu", module, module, "NON TESTÉ",
                "Pas de lecture physique de ce module sans profil ECU vérifié."));
        }
        if (selected is not null && ecmProfile is not null)
        {
            RequireActive();
            try
            {
                using var device = new J2534Device(selected);
                device.Open(ecmProfile.RequestCanId, ecmProfile.ResponseCanId,
                    ecmProfile.Can29Bit, ecmProfile.ProtocolNumber);
                var first = device.ReadGmlanIdentification();
                RequireActive();
                var second = device.ReadGmlanIdentification();
                var stable = NativeGmlanBenchProbe.RequireStableIdentity(first, second);
                var rowIndex = rows.FindIndex(row => row.Group == "ecu" && row.Id == "ECM");
                rows[rowIndex] = new WindowsFunctionalCheck(
                    "ecu", "ECM", "ECM — profil physique confirmé", "OK",
                    "VIN/OSID identiques sur deux lectures physiques ; OSID " +
                    stable.OperatingSystemId + ". Aucune écriture testée.");
            }
            catch (OperationCanceledException) { throw; }
            catch (Exception error)
            {
                var rowIndex = rows.FindIndex(row => row.Group == "ecu" && row.Id == "ECM");
                rows[rowIndex] = new WindowsFunctionalCheck(
                    "ecu", "ECM", "ECM — profil physique confirmé", "ÉCHEC",
                    "Identification GMLAN échouée : " + error.GetType().Name);
            }
        }
        RequireActive();
        return rows;
    }

    private void DisplayWindowsFunctionalChecks(List<WindowsFunctionalCheck> rows)
    {
        var passed = rows.Count(row => row.Status == "OK");
        var failed = rows.Count(row => row.Status == "ÉCHEC");
        var untested = rows.Count(row => row.Status == "NON TESTÉ");
        var output = new StringBuilder();
        output.AppendLine("Résultats : " + passed + " OK / " + failed + " échecs / " +
            untested + " non testés.");
        foreach (var row in rows)
            output.AppendLine("[" + row.Status + "] " + row.Label +
                Environment.NewLine + "  " + row.Detail);
        functionalResults.Text = output.ToString();
    }

    private void ExportWindowsFunctionalResults()
    {
        if (DateTimeOffset.UtcNow >= functionalAuthorizedUntil || completedFunctionalChecks.Count == 0)
            return;
        using var dialog = new SaveFileDialog
        {
            Filter = "Rapport JSON (*.json)|*.json",
            AddExtension = true, DefaultExt = "json",
            FileName = "CamaroDiag_Windows_functional_tests.json",
            OverwritePrompt = true
        };
        if (dialog.ShowDialog(this) != DialogResult.OK) return;
        try
        {
            var json = JsonSerializer.Serialize(new
            {
                schema = "camarodiag-functional-test-v1",
                generatedAtUtc = DateTimeOffset.UtcNow,
                platform = "Windows J2534",
                warning = "NOT_TESTED does not mean PASS. No ECU write/erase/actuation tested.",
                checks = completedFunctionalChecks
            }, new JsonSerializerOptions { WriteIndented = true });
            File.WriteAllText(dialog.FileName, json, Encoding.UTF8);
        }
        catch (Exception)
        {
            ShowError("Impossible d'exporter le rapport des tests.");
        }
    }
}
