using System.Drawing;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Forms;

namespace CamaroDiag.J2534Bridge;

internal sealed partial class BridgeDesktopForm
{
    private readonly TextBox hardwareReport = new()
    {
        Dock = DockStyle.Fill, ReadOnly = true, Multiline = true,
        ScrollBars = ScrollBars.Both, WordWrap = false,
        Font = new Font("Consolas", 10f)
    };
    private readonly Button hardwareProbe = Button("Tester l'interface sélectionnée");
    private readonly Button allDrivers = Button("Tester tous les pilotes");
    private readonly Button testSoftware = Button("Autotests du pont");
    private readonly Button copyHardware = Button("Copier le diagnostic");
    private readonly Label updateVersion = new()
    {
        Dock = DockStyle.Fill, AutoSize = true,
        ForeColor = Color.DarkSlateGray
    };
    private readonly Label updateState = new()
    {
        Dock = DockStyle.Fill, AutoSize = true, Text = "Aucune vérification effectuée."
    };
    private readonly Button checkUpdate = Button("Rechercher une mise à jour");
    private readonly Button installUpdate = Button("Installer la mise à jour");
    private BridgeUpdateManager.Candidate? availableUpdate;

    private TabPage BuildDiagnosticsTab()
    {
        var tab = new TabPage("Pilotes et diagnostics");
        var layout = new TableLayoutPanel
            { Dock = DockStyle.Fill, RowCount = 2, ColumnCount = 1 };
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 220));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        var fields = Grid();
        WideRow(fields, new Label
        {
            Text = "Les tests J2534 ouvrent brièvement l'interface pour vérifier le pilote " +
                "et lire les versions de firmware, DLL et API. Aucun trafic ECU ni écriture.",
            Dock = DockStyle.Fill, ForeColor = Color.DarkSlateGray, AutoSize = true
        }, 65);
        hardwareProbe.Click += async (_, _) => await DiagnoseHardwareAsync(all: false);
        allDrivers.Click += async (_, _) => await DiagnoseHardwareAsync(all: true);
        testSoftware.Click += async (_, _) => await DiagnoseSoftwareAsync();
        copyHardware.Click += (_, _) =>
        {
            try
            {
                if (hardwareReport.TextLength > 0) Clipboard.SetText(hardwareReport.Text);
            }
            catch (ExternalException ex) { ShowError(ex.Message); }
        };
        var actions = new FlowLayoutPanel { Dock = DockStyle.Fill };
        foreach (var b in new[] { hardwareProbe, allDrivers })
            actions.Controls.Add(b);
        WideRow(fields, actions, 48);
        var more = new FlowLayoutPanel { Dock = DockStyle.Fill };
        more.Controls.Add(testSoftware);
        more.Controls.Add(copyHardware);
        WideRow(fields, more, 50);
        layout.Controls.Add(fields, 0, 0);
        layout.Controls.Add(hardwareReport, 0, 1);
        tab.Controls.Add(layout);
        hardwareReport.Text = "Sélectionnez une interface J2534 dans Connexion Android.\r\n" +
            "Architecture attendue : même x86/x64 pour le pont et la DLL du fabricant.\r\n" +
            "Un pilote fonctionnel ne signifie pas qu'une écriture ECU est validée.";
        return tab;
    }

    private async Task DiagnoseHardwareAsync(bool all)
    {
        if (busy) return;
        if (bridgeTask is { IsCompleted: false } || consoleDevice is not null)
        {
            ShowError("Fermez le pont Android et la console avant de tester le pilote.");
            return;
        }
        var candidates = all
            ? J2534Registry.Discover()
            : adapter.SelectedItem is AdapterChoice selected
                ? new[] { selected.Adapter }
                : Array.Empty<J2534Adapter>();
        if (candidates.Count == 0)
        {
            ShowError("Aucune interface J2534 enregistrée pour le test.");
            return;
        }
        await RunBusyAsync(async () =>
        {
            var results = await Task.Run(() =>
                candidates.Select(J2534DriverProbe.Probe).ToArray());
            var report = new StringBuilder();
            foreach (var item in results)
            {
                report.AppendLine("Interface : " + item.Adapter);
                report.AppendLine("ABI J2534 : " + (item.AbiComplete ? "OK" : "NON"));
                report.AppendLine("PassThruOpen : " + (item.DeviceOpened ? "OK" : "NON"));
                report.AppendLine("Version DLL : " + (item.Dll ?? "indisponible"));
                report.AppendLine("Firmware : " + (item.Firmware ?? "indisponible"));
                report.AppendLine("Version API : " + (item.Api ?? "indisponible"));
                report.AppendLine("Pilote opérationnel : " + (item.DriverOperational ? "OUI" : "NON"));
                report.AppendLine("Programmation ECU validée : NON");
                report.AppendLine(item.Detail);
                report.AppendLine(new string('-', 50));
            }
            hardwareReport.Text = report.ToString();
        });
    }

    private async Task DiagnoseSoftwareAsync()
    {
        if (busy) return;
        await RunBusyAsync(async () =>
        {
            var result = await Task.Run(BridgeSelfTests.Run);
            hardwareReport.Text = result == 0
                ? "Autotests du pont J2534 terminés sans erreur.\r\n" +
                  "Les validations ne remplacent pas un essai de programmation sur ECU de banc."
                : "Autotests du pont : code " + result;
        });
    }

    private TabPage BuildUpdatesTab()
    {
        var tab = new TabPage("Mises à jour / À propos");
        var fields = Grid();
        updateVersion.Text = "CamaroDiag J2534 Bridge " +
            BridgeUpdateManager.InstalledVersion + " (" +
            RuntimeInformation.ProcessArchitecture + ")";
        Row(fields, "Version installée", updateVersion, minimumHeight: 52);
        Row(fields, "Dernière vérification", updateState, minimumHeight: 65);
        checkUpdate.Click += async (_, _) => await CheckDesktopUpdateAsync();
        installUpdate.Click += async (_, _) => await InstallDesktopUpdateAsync();
        installUpdate.Enabled = false;
        WideRow(fields, checkUpdate, 44);
        WideRow(fields, installUpdate, 44);
        WideRow(fields, new Label
        {
            Text = "Les mises à jour sont recherchées uniquement dans les releases " +
                "officielles Hellorheaven/CamaroDiag de GitHub. " +
                "L'installateur de l'architecture x86 ou x64 est téléchargé et lancé " +
                "automatiquement après fermeture du pont. " +
                "Aucun logiciel de programmation ECU tiers n'est requis.",
            Dock = DockStyle.Fill, AutoSize = true,
            ForeColor = Color.DarkSlateGray
        }, 100);
        var showUpdateLog = Button("Voir le dernier résultat d'installation");
        showUpdateLog.Click += (_, _) =>
        {
            var path = Path.Combine(Environment.GetFolderPath(
                Environment.SpecialFolder.LocalApplicationData), "CamaroDiag",
                "Bridge-update.log");
            updateState.Text = File.Exists(path)
                ? File.ReadAllText(path) : "Pas encore de journal de mise à jour.";
        };
        WideRow(fields, showUpdateLog, 46);
        tab.Controls.Add(fields);
        return tab;
    }

    private async Task CheckDesktopUpdateAsync()
    {
        if (busy) return;
        checkUpdate.Enabled = false;
        updateState.Text = "Vérification de la dernière version officielle…";
        await RunBusyAsync(async () =>
        {
            var result = await BridgeUpdateManager.CheckLatestAsync();
            availableUpdate = result;
            updateState.Text = result is null
                ? "Aucune mise à jour officielle plus récente avec installateur compatible."
                : $"Version disponible : {result.Latest} ({result.Architecture}).";
            installUpdate.Enabled = result != null;
        });
        checkUpdate.Enabled = true;
    }

    private async Task InstallDesktopUpdateAsync()
    {
        var update = availableUpdate;
        if (busy || update is null) return;
        if (bridgeTask is { IsCompleted: false } || consoleDevice is not null)
        {
            ShowError("Arrêtez le pont et fermez la console avant la mise à jour.");
            return;
        }
        if (MessageBox.Show(this, $"Installer CamaroDiag Windows {update.Latest} " +
            "depuis la release GitHub officielle ? " +
            "L'application se fermera pour laisser l'installation se terminer.",
            "Mise à jour du pont Windows", MessageBoxButtons.YesNo,
            MessageBoxIcon.Question) != DialogResult.Yes) return;

        installUpdate.Enabled = false;
        await RunBusyAsync(async () =>
        {
            updateState.Text = "Téléchargement sécurisé et préparation de l'installateur…";
            var log = await BridgeUpdateManager.DownloadAndScheduleInstallAsync(update);
            updateState.Text = "L'installateur est prêt. Fermeture de CamaroDiag…";
            MessageBox.Show(this,
                "L'installation se poursuivra automatiquement après la fermeture de " +
                "CamaroDiag. Son résultat sera visible ici au prochain démarrage.\n" +
                "Journal : " + log, "Mise à jour prête", MessageBoxButtons.OK,
                MessageBoxIcon.Information);
            Application.Exit();
        });
    }
}
