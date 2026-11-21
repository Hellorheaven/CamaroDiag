using System.Drawing;
using System.Net;
using System.Security.Cryptography;
using System.Windows.Forms;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// First-party Windows control center. The existing CLI and Windows CI remain
/// available, but ordinary users never have to open a terminal.
///
/// No third-party ECU software, WebView, extra installer or service is used.
/// All destructive programming operations remain disconnected.
/// </summary>
internal sealed class BridgeDesktopForm : Form
{
    private sealed record AdapterChoice(J2534Adapter Adapter)
    {
        public override string ToString() => Adapter.DisplayName;
    }

    private readonly ComboBox adapter = new() { DropDownStyle = ComboBoxStyle.DropDownList, Dock = DockStyle.Fill };
    private readonly ComboBox lanAddress = new() { DropDownStyle = ComboBoxStyle.DropDownList, Dock = DockStyle.Fill };
    private readonly NumericUpDown port = new()
    {
        Minimum = 1024, Maximum = 65535, Value = 35001, Dock = DockStyle.Fill
    };
    private readonly TextBox pairingCode = new()
    {
        ReadOnly = true, Dock = DockStyle.Fill, UseSystemPasswordChar = true
    };
    private readonly TextBox memoryProfile = new() { ReadOnly = true, Dock = DockStyle.Fill };
    private readonly Label bridgeStatus = new()
    {
        Text = "Arrêté — aucune connexion Android possible",
        Dock = DockStyle.Fill, AutoSize = true, ForeColor = Color.DarkSlateGray
    };
    private readonly Button startBridge = Button("Démarrer le pont");
    private readonly Button stopBridge = Button("Arrêter");
    private readonly Button copyAll = Button("Copier pour Android");
    private readonly Button copyCode = Button("Copier le code");
    private readonly Button copyIp = Button("Copier l'IP");
    private readonly Button copyPort = Button("Copier le port");
    private readonly Button probe = Button("Lire l'identité ECU (sans écriture)");
    private readonly TextBox originalPath = new() { Dock = DockStyle.Fill, ReadOnly = true };
    private readonly TextBox editedPath = new() { Dock = DockStyle.Fill, ReadOnly = true };
    private readonly TextBox outputPath = new() { Dock = DockStyle.Fill, ReadOnly = true };
    private readonly TextBox requestId = new() { Text = "7E0", Dock = DockStyle.Fill };
    private readonly TextBox responseId = new() { Text = "7E8", Dock = DockStyle.Fill };
    private readonly ComboBox protocol = new()
    {
        DropDownStyle = ComboBoxStyle.DropDownList, Dock = DockStyle.Fill
    };
    private readonly TextBox ecuResults = new()
    {
        Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical,
        Dock = DockStyle.Fill, Font = new Font("Consolas", 9.5f)
    };
    private readonly Button inspect = Button("Vérifier l'image");
    private readonly Button repair = Button("Corriger les checksums");
    private CancellationTokenSource? bridgeCancellation;
    private Task? bridgeTask;
    private bool bridgeListening;
    private bool busy;
    private string? currentPairingCode;

    internal BridgeDesktopForm()
    {
        Text = "CamaroDiag — Pont Windows J2534";
        Width = 960;
        Height = 735;
        MinimumSize = new Size(820, 610);
        StartPosition = FormStartPosition.CenterScreen;
        Font = new Font("Segoe UI", 10);
        BackColor = Color.FromArgb(249, 250, 249);

        var tabs = new TabControl { Dock = DockStyle.Fill, Padding = new Point(12, 7) };
        tabs.TabPages.Add(BuildConnectionTab());
        tabs.TabPages.Add(BuildEcuTab());
        tabs.TabPages.Add(BuildHelpTab());
        Controls.Add(tabs);

        foreach (var p in new[] { "33", "34", "35", "36", "63", "64" })
            protocol.Items.Add(p);
        protocol.SelectedItem = "33";
        stopBridge.Enabled = false;
        copyAll.Enabled = copyCode.Enabled = copyIp.Enabled = copyPort.Enabled = false;
        pairingCode.UseSystemPasswordChar = true;
        RefreshDetected();
        FormClosing += (_, _) => bridgeCancellation?.Cancel();
    }

    private static Button Button(string label) =>
        new() { Text = label, Height = 34, AutoSize = true, Margin = new Padding(3) };

    private static TableLayoutPanel Grid()
    {
        var panel = new TableLayoutPanel
        {
            Dock = DockStyle.Fill, ColumnCount = 3, AutoScroll = true,
            Padding = new Padding(18, 14, 18, 12), BackColor = Color.White
        };
        panel.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 183));
        panel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        panel.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 194));
        return panel;
    }

    private static void Row(TableLayoutPanel panel, string label,
        Control main, Control? action = null, int minimumHeight = 43)
    {
        var index = panel.RowCount++;
        panel.RowStyles.Add(new RowStyle(SizeType.Absolute, minimumHeight));
        panel.Controls.Add(new Label
        {
            Text = label, AutoSize = true, Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.MiddleLeft
        }, 0, index);
        main.Margin = new Padding(4, 4, 8, 5);
        panel.Controls.Add(main, 1, index);
        if (action != null)
        {
            action.Dock = DockStyle.Fill;
            panel.Controls.Add(action, 2, index);
        }
    }

    private static void WideRow(TableLayoutPanel panel, Control control, int height)
    {
        var index = panel.RowCount++;
        panel.RowStyles.Add(new RowStyle(SizeType.Absolute, height));
        control.Dock = DockStyle.Fill;
        panel.Controls.Add(control, 0, index);
        panel.SetColumnSpan(control, 3);
    }

    private TabPage BuildConnectionTab()
    {
        var tab = new TabPage("Connexion Android");
        var panel = Grid();
        var refresh = Button("Actualiser interfaces");
        refresh.Click += (_, _) => RefreshDetected();
        Row(panel, "Interface J2534", adapter, refresh);
        var refreshIp = Button("Actualiser les IP");
        refreshIp.Click += (_, _) => RefreshDetected();
        Row(panel, "Adresse IP du PC", lanAddress, refreshIp);
        Row(panel, "Port TCP sécurisé", port);
        var chooseProfile = Button("Profil mémoire (facultatif)");
        chooseProfile.Click += (_, _) => ChooseFile(memoryProfile, save: false,
            filter: "Profil de lecture (*.json)|*.json|Tous les fichiers (*.*)|*.*");
        Row(panel, "Lecture mémoire avancée", memoryProfile, chooseProfile);
        var commands = new FlowLayoutPanel
        {
            FlowDirection = FlowDirection.LeftToRight, Dock = DockStyle.Fill,
            AutoSize = false
        };
        commands.Controls.Add(startBridge);
        commands.Controls.Add(stopBridge);
        startBridge.Click += (_, _) => Start();
        stopBridge.Click += (_, _) => Stop();
        WideRow(panel, commands, 46);
        WideRow(panel, bridgeStatus, 42);

        copyIp.Click += (_, _) => CopyToClipboard(lanAddress.SelectedItem?.ToString());
        copyPort.Click += (_, _) => CopyToClipboard(((int)port.Value).ToString());
        copyCode.Click += (_, _) => CopyToClipboard(currentPairingCode);
        copyAll.Click += (_, _) =>
        {
            try
            {
                CopyToClipboard(BridgePairingDetails.FormatForAndroid(
                    lanAddress.SelectedItem?.ToString() ?? "",
                    (int)port.Value, currentPairingCode ?? ""));
            }
            catch (Exception ex) when (ex is ArgumentException or ArgumentOutOfRangeException)
            {
                ShowError(ex.Message);
            }
        };

        Row(panel, "IP à saisir dans Android", new Label
        {
            Text = "Sélectionner ci-dessus l'adresse du réseau commun au téléphone.",
            Dock = DockStyle.Fill, AutoSize = true
        }, copyIp);
        Row(panel, "Port à saisir", new Label
        {
            Text = "Identique au port choisi avant le démarrage.", Dock = DockStyle.Fill,
            AutoSize = true
        }, copyPort);
        Row(panel, "Code TLS d'appairage", pairingCode, copyCode);
        var reveal = new CheckBox
        {
            Text = "Afficher le code (à garder privé)",
            AutoSize = true, Dock = DockStyle.Left
        };
        reveal.CheckedChanged += (_, _) => pairingCode.UseSystemPasswordChar = !reveal.Checked;
        WideRow(panel, reveal, 38);
        WideRow(panel, copyAll, 44);

        WideRow(panel, new Label
        {
            Text = "Sécurité : utiliser le même réseau privé sur Windows et Android. " +
                   "Ne pas ouvrir le port sur Internet. Le code change à chaque démarrage. " +
                   "Écriture ECU et fonctions dangereuses non autorisées.",
            AutoSize = true, Dock = DockStyle.Fill,
            ForeColor = Color.DarkSlateGray
        }, 66);
        tab.Controls.Add(panel);
        return tab;
    }

    private TabPage BuildEcuTab()
    {
        var tab = new TabPage("Outils ECU E39/E39A");
        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill, RowCount = 2, ColumnCount = 1
        };
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 65));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 35));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        var fields = Grid();
        var chooseOriginal = Button("Choisir l'original");
        chooseOriginal.Click += (_, _) => ChooseFile(originalPath, false);
        Row(fields, "Image d'origine (3 Mio)", originalPath, chooseOriginal);
        var chooseEdited = Button("Choisir l'image modifiée");
        chooseEdited.Click += (_, _) => ChooseFile(editedPath, false);
        Row(fields, "Image modifiée", editedPath, chooseEdited);
        var chooseOutput = Button("Choisir la sortie");
        chooseOutput.Click += (_, _) => ChooseFile(outputPath, true);
        Row(fields, "Nouveau fichier corrigé", outputPath, chooseOutput);

        var actions = new FlowLayoutPanel { Dock = DockStyle.Fill };
        actions.Controls.Add(inspect);
        actions.Controls.Add(repair);
        inspect.Click += async (_, _) => await InspectImageAsync();
        repair.Click += async (_, _) => await RepairImageAsync();
        WideRow(fields, actions, 47);

        WideRow(fields, new Label
        {
            Text = "Test ECU en lecture seule. Choisir l'interface dans l'onglet Connexion Android. " +
                   "Ces CAN IDs sont des exemples : vérifier le calculateur avant de les utiliser.",
            AutoSize = true, ForeColor = Color.DarkSlateGray
        }, 50);
        Row(fields, "CAN ID requête (hex)", requestId);
        Row(fields, "CAN ID réponse (hex)", responseId);
        Row(fields, "Profil de liaison", protocol, probe);
        probe.Click += async (_, _) => await ProbeEcuAsync();

        layout.Controls.Add(fields, 0, 0);
        layout.Controls.Add(ecuResults, 0, 1);
        tab.Controls.Add(layout);
        AppendLog("Outils natifs disponibles : analyse E39/A, corrections SUM/CVN, " +
            "identification physique non destructive. Flash ECU non disponible.");
        return tab;
    }

    private TabPage BuildHelpTab()
    {
        var tab = new TabPage("Aide et utilisation");
        var help = new RichTextBox
        {
            Dock = DockStyle.Fill, ReadOnly = true,
            BackColor = Color.White, BorderStyle = BorderStyle.None,
            Font = new Font("Segoe UI", 10.5f),
            Text =
@"CAMARODIAG — PONT WINDOWS ET APPLICATION ANDROID

1. INSTALLER LE PILOTE J2534
Installez le pilote Windows du constructeur (VX Manager pour les interfaces VXDIAG compatibles).
Raccordez l'interface USB au PC, et assurez-vous que le calculateur ou le véhicule est alimenté.

2. CONNECTER LE TÉLÉPHONE
Connectez le PC et le téléphone Android au même réseau privé (Wi-Fi ou LAN).
Dans « Connexion Android », choisissez votre interface J2534 et l'adresse IP du PC.
Conservez le port 35001 sauf nécessité particulière, puis cliquez « Démarrer le pont ».
Attendez l'indication « En écoute ». Les champs IP, port et code sécurisé sont alors actifs.

Dans CamaroDiag sur Android, sélectionnez le connecteur Pont Windows J2534.
Recopiez l'IP et le port dans les champs correspondants.
Copiez le CODE COMPLET : jeton ET empreinte SHA-256 séparés par un point.
Le bouton « Copier pour Android » fournit les trois valeurs.
Lors de chaque redémarrage du pont, un nouveau code est créé.

3. IDENTIFIER UN CALCULATEUR
Dans « Outils ECU E39/E39A », sélectionnez les identifiants CAN requis par le
calculateur, puis « Lire l'identité ECU ». Le logiciel lit deux fois le VIN
et l'OSID avec le protocole GM GMLAN. Aucune écriture n'est effectuée.
Ne supposez pas que les valeurs CAN proposées conviennent à tous les ECU.

4. INSPECTER ET CORRIGER UNE IMAGE E39/E39A
Conservez précieusement une sauvegarde originale complète et vérifiée de 3 Mio.
« Vérifier l'image » analyse une image E39/E39A sans la modifier.
Pour une calibration déjà modifiée, choisissez l'original, le fichier modifié,
puis un NOUVEAU nom de sortie et cliquez « Corriger les checksums ».
La correction est limitée aux segments de calibration connus. Le système
d'exploitation, le bootloader et les zones inconnues sont protégés.
Un fichier de sortie existant ne sera jamais écrasé.

IMPORTANT
Les sommes de contrôle correctes ne prouvent pas qu'un flash est sûr.
L'écriture ECU E39/E39A n'est pas disponible tant que le kernel natif,
l'alimentation, le modèle exact et la récupération ne sont pas validés sur banc.
Aucun logiciel PCM Hammer ni autre programmeur tiers n'est utilisé.
Le pilote J2534 du fabricant reste nécessaire.

DÉPANNAGE
- Aucune interface : vérifier le pilote du fabricant et l'architecture x86/x64.
- Aucune IP : vérifier Wi-Fi/LAN puis actualiser les IP.
- Android ne se connecte pas : PC/téléphone sur le même réseau, vérifier le
  pare-feu Windows (réseaux privés uniquement), le port et le code complet.
- Code invalide : redémarrer l'appairage et copier le nouveau code.
- Absence de réponse ECU : vérifier CAN IDs, protocole, câblage et alimentation.
- Pour une image incorrecte : ne jamais écrire sur le calculateur.

SÉCURITÉ
Le pont utilise TLS 1.2/1.3 avec épinglage du certificat.
Ne transmettez jamais le code d'appairage à un tiers, et ne redirigez
pas le port du pont sur Internet.",
        };
        tab.Controls.Add(help);
        return tab;
    }

    private void RefreshDetected()
    {
        if (bridgeTask is { IsCompleted: false }) return;
        try
        {
            var previousIndex = adapter.SelectedIndex;
            adapter.Items.Clear();
            foreach (var item in J2534Registry.Discover())
                adapter.Items.Add(new AdapterChoice(item));
            if (adapter.Items.Count > 0)
                adapter.SelectedIndex = previousIndex >= 0 && previousIndex < adapter.Items.Count
                    ? previousIndex : 0;
            lanAddress.Items.Clear();
            foreach (var ip in BridgePairingDetails.PrivateIpv4Addresses())
                lanAddress.Items.Add(ip);
            if (lanAddress.Items.Count > 0) lanAddress.SelectedIndex = 0;
            startBridge.Enabled = adapter.Items.Count > 0 && lanAddress.Items.Count > 0;
            if (adapter.Items.Count == 0)
                bridgeStatus.Text = "Aucun pilote J2534 détecté. Installez le pilote constructeur.";
            else if (lanAddress.Items.Count == 0)
                bridgeStatus.Text = "Aucune adresse réseau privée : reliez ce PC au réseau Android.";
            else
                bridgeStatus.Text = "Prêt. Sélectionnez le pilote et démarrez le pont.";
        }
        catch (Exception error)
        {
            startBridge.Enabled = false;
            bridgeStatus.Text = "Recherche impossible : " + error.Message;
        }
    }

    private void Start()
    {
        if (bridgeTask is { IsCompleted: false }) return;
        BridgeTlsIdentity? identity = null;
        CancellationTokenSource? stop = null;
        try
        {
            if (adapter.SelectedItem is not AdapterChoice choice ||
                lanAddress.SelectedItem is not string ip ||
                !IPAddress.TryParse(ip, out var ipAddress) ||
                !BridgeServer.IsPrivateNetworkAddress(ipAddress) ||
                IPAddress.IsLoopback(ipAddress))
                throw new InvalidOperationException("Choisissez une interface et une IP privée valides.");

            J2534MemoryReadScope? scope = null;
            if (!string.IsNullOrWhiteSpace(memoryProfile.Text))
                scope = J2534MemoryReadScope.Load(memoryProfile.Text);
            identity = BridgeTlsIdentity.Create();
            stop = new CancellationTokenSource();
            var token = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32))
                .TrimEnd('=').Replace('+', '-').Replace('/', '_');
            var code = token + "." + identity.Fingerprint;
            // Fully constructed before any state is published.
            var server = new BridgeServer(choice.Adapter, token, (int)port.Value,
                identity, scope, () =>
                {
                    if (!IsDisposed && IsHandleCreated)
                        BeginInvoke((Action)(() =>
                        {
                            if (bridgeCancellation?.IsCancellationRequested != false) return;
                            bridgeListening = true;
                            bridgeStatus.Text = "En écoute — connexion Android chiffrée TLS active";
                            copyAll.Enabled = copyCode.Enabled = copyIp.Enabled = copyPort.Enabled = true;
                        }));
                });
            bridgeCancellation = stop;
            currentPairingCode = code;
            pairingCode.Text = code;
            bridgeListening = false;
            startBridge.Enabled = false;
            stopBridge.Enabled = true;
            adapter.Enabled = lanAddress.Enabled = port.Enabled = memoryProfile.Enabled = false;
            bridgeStatus.Text = "Démarrage du pont sécurisé...";
            var task = Task.Run(() => server.RunAsync(stop.Token));
            bridgeTask = task;
            _ = ObserveServerAsync(task, stop, identity);
        }
        catch (Exception error)
        {
            stop?.Dispose();
            identity?.Dispose();
            ShowError("Impossible de démarrer : " + error.Message);
        }
    }

    private async Task ObserveServerAsync(
        Task task, CancellationTokenSource source, BridgeTlsIdentity identity)
    {
        string status;
        try
        {
            await task;
            status = "Pont arrêté.";
        }
        catch (OperationCanceledException) when (source.IsCancellationRequested)
        {
            status = "Pont arrêté à la demande.";
        }
        catch (Exception error)
        {
            status = "Échec du pont : " + error.Message;
        }
        finally
        {
            identity.Dispose();
            source.Dispose();
        }

        if (IsDisposed || Disposing) return;
        bridgeListening = false;
        bridgeCancellation = null;
        bridgeTask = null;
        currentPairingCode = null;
        pairingCode.Clear();
        copyAll.Enabled = copyCode.Enabled = copyIp.Enabled = copyPort.Enabled = false;
        stopBridge.Enabled = false;
        adapter.Enabled = lanAddress.Enabled = port.Enabled = memoryProfile.Enabled = true;
        bridgeStatus.Text = status;
        startBridge.Enabled = adapter.Items.Count > 0 && lanAddress.Items.Count > 0;
    }

    private void Stop()
    {
        stopBridge.Enabled = false;
        bridgeStatus.Text = "Arrêt sécurisé en cours...";
        bridgeCancellation?.Cancel();
    }

    private void ChooseFile(TextBox target, bool save, string? filter = null)
    {
        filter ??= "Image ECU (*.bin)|*.bin|Tous les fichiers (*.*)|*.*";
        if (save)
        {
            using var dialog = new SaveFileDialog
            {
                Filter = filter, OverwritePrompt = true, AddExtension = true,
                DefaultExt = "bin", Title = "Choisir un NOUVEAU fichier de sortie"
            };
            if (dialog.ShowDialog(this) == DialogResult.OK)
                target.Text = dialog.FileName;
        }
        else
        {
            using var dialog = new OpenFileDialog { Filter = filter, CheckFileExists = true };
            if (dialog.ShowDialog(this) == DialogResult.OK)
                target.Text = dialog.FileName;
        }
    }

    private async Task InspectImageAsync()
    {
        if (busy) return;
        if (!File.Exists(originalPath.Text))
        {
            ShowError("Sélectionnez un fichier original E39/E39A de 3 Mio.");
            return;
        }
        await RunBusyAsync(async () =>
        {
            var report = await Task.Run(() =>
                NativeE39ImageInspector.Inspect(File.ReadAllBytes(originalPath.Text)));
            AppendLog($"Inspection E39/E39A : {report.Status} | OSID : {report.OsIdentifier?.ToString() ?? "indisponible"}");
            foreach (var segment in report.Segments)
                AppendLog($"  {segment.Name}: 0x{segment.Start:X6}-0x{segment.EndInclusive:X6}");
            foreach (var check in report.Checks)
                AppendLog($"  {check.Name} {check.Kind}: {(check.Matches ? "OK" : "NON CONFORME")}");
            if (report.Reason is not null) AppendLog("Détail : " + report.Reason);
        });
    }

    private async Task RepairImageAsync()
    {
        if (busy) return;
        if (!File.Exists(originalPath.Text) || !File.Exists(editedPath.Text) ||
            string.IsNullOrWhiteSpace(outputPath.Text))
        {
            ShowError("Choisissez l'original, l'image modifiée et un nouveau fichier de sortie.");
            return;
        }
        var before = Path.GetFullPath(originalPath.Text);
        var edited = Path.GetFullPath(editedPath.Text);
        var output = Path.GetFullPath(outputPath.Text);
        if (string.Equals(before, edited, StringComparison.OrdinalIgnoreCase) ||
            string.Equals(before, output, StringComparison.OrdinalIgnoreCase) ||
            string.Equals(edited, output, StringComparison.OrdinalIgnoreCase))
        {
            ShowError("L'original, la modification et la sortie doivent être trois fichiers différents.");
            return;
        }

        if (MessageBox.Show(this, "La correction est hors ligne et ne programme pas l'ECU. " +
            "Elle créera un nouveau fichier, sans écraser les originaux. Continuer ?",
            "Corriger les sommes et CVN", MessageBoxButtons.YesNo,
            MessageBoxIcon.Question) != DialogResult.Yes) return;

        await RunBusyAsync(async () =>
        {
            var result = await Task.Run(() =>
            {
                var original = File.ReadAllBytes(before);
                var modified = File.ReadAllBytes(edited);
                var fixedImage = NativeE39CalibrationRepair.Repair(original, modified);
                var created = false;
                try
                {
                    using (var destination = new FileStream(output, FileMode.CreateNew,
                        FileAccess.Write, FileShare.None, 65536, FileOptions.WriteThrough))
                    {
                        created = true;
                        destination.Write(fixedImage.RepairedImage);
                        destination.Flush(flushToDisk: true);
                    }
                    var saved = File.ReadAllBytes(output);
                    if (!saved.SequenceEqual(fixedImage.RepairedImage) ||
                        NativeE39ImageInspector.Inspect(saved).Status !=
                        NativeE39ImageInspector.Verdict.Valid)
                        throw new InvalidDataException("Le fichier corrigé n'a pas passé la relecture.");
                    return fixedImage;
                }
                catch
                {
                    if (created)
                    {
                        try { File.Delete(output); }
                        catch (IOException) { }
                        catch (UnauthorizedAccessException) { }
                    }
                    throw;
                }
            });
            AppendLog($"Checksum/CVN corrigés : {result.ChecksumFieldsUpdated} champs, " +
                $"{result.EditedBytesChanged} octets modifiés. OSID {result.OperatingSystemId}.");
            foreach (var item in result.ChangedSegments)
                AppendLog($"  {item.Name}: {item.BytesChanged} octets modifiés");
            AppendLog("SHA-256 : " + result.RepairedSha256);
            AppendLog("Enregistré : " + output);
            AppendLog("Écriture physique ECU : NON AUTORISÉE.");
        });
    }

    private async Task ProbeEcuAsync()
    {
        if (busy) return;
        if (bridgeTask is { IsCompleted: false })
        {
            ShowError("Arrêtez d'abord le pont : le test doit utiliser seul le canal J2534.");
            return;
        }
        if (adapter.SelectedItem is not AdapterChoice choice)
        {
            ShowError("Sélectionnez un pilote J2534 installé dans l'onglet Connexion Android.");
            return;
        }
        NativeGmlanBenchProbe.Options options;
        try
        {
            options = NativeGmlanBenchProbe.Parse([
                NativeGmlanBenchProbe.Command,
                "--adapter-index", adapter.SelectedIndex.ToString(),
                "--request-can-id", requestId.Text.Trim(),
                "--response-can-id", responseId.Text.Trim(),
                "--protocol", protocol.SelectedItem?.ToString() ?? "33"
            ]);
        }
        catch (ArgumentException ex)
        {
            ShowError("Profil de connexion invalide : " + ex.Message);
            return;
        }

        if (MessageBox.Show(this, "Confirmez que l'interface et le calculateur sont " +
            "alimentés et correctement raccordés. Lecture VIN/OSID uniquement.",
            "Lecture physique ECU", MessageBoxButtons.OKCancel, MessageBoxIcon.Information)
            != DialogResult.OK) return;

        await RunBusyAsync(async () =>
        {
            var result = await Task.Run(() =>
            {
                using var device = new J2534Device(choice.Adapter);
                device.Open(options.RequestCanId, options.ResponseCanId,
                    options.Can29Bit, options.ProtocolNumber);
                var voltage1 = device.ReadBatteryMillivolts();
                var first = device.ReadGmlanIdentification();
                var second = device.ReadGmlanIdentification();
                var stable = NativeGmlanBenchProbe.RequireStableIdentity(first, second);
                var voltage2 = device.ReadBatteryMillivolts();
                return (stable, voltage1, voltage2);
            });
            AppendLog($"VIN confirmé deux fois : {result.stable.Vin}");
            AppendLog($"OSID confirmé : {result.stable.OperatingSystemId}, DID 0x{result.stable.OsDid:X2}");
            AppendLog(result.voltage1.HasValue && result.voltage2.HasValue
                ? $"Tension pilote J2534 : {result.voltage1.Value / 1000.0:F2} / {result.voltage2.Value / 1000.0:F2} V"
                : "Tension : non fournie par le pilote J2534.");
            AppendLog("Flash ECU non qualifié — lecture seule.");
        });
    }

    private async Task RunBusyAsync(Func<Task> work)
    {
        if (busy) return;
        busy = true;
        inspect.Enabled = repair.Enabled = probe.Enabled = false;
        try { await work(); }
        catch (Exception error)
        {
            AppendLog("ERREUR : " + error.Message);
            ShowError(error.Message);
        }
        finally
        {
            busy = false;
            inspect.Enabled = repair.Enabled = probe.Enabled = true;
        }
    }

    private void CopyToClipboard(string? value)
    {
        if (string.IsNullOrWhiteSpace(value) || !bridgeListening)
        {
            ShowError("Démarrez le pont et attendez le statut « En écoute ».");
            return;
        }
        try
        {
            Clipboard.SetText(value);
            bridgeStatus.Text = "Copié dans le presse-papiers — ne partagez pas le code sécurisé.";
        }
        catch (System.Runtime.InteropServices.ExternalException ex)
        {
            ShowError("Presse-papiers Windows indisponible : " + ex.Message);
        }
    }

    private void AppendLog(string line) =>
        ecuResults.AppendText($"{DateTime.Now:HH:mm:ss}  {line}{Environment.NewLine}");

    private void ShowError(string message) =>
        MessageBox.Show(this, message, "CamaroDiag — contrôle", MessageBoxButtons.OK,
            MessageBoxIcon.Warning);
}
