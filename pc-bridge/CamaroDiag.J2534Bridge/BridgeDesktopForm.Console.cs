using System.Drawing;
using System.Text;
using System.Text.Json;
using System.Windows.Forms;

namespace CamaroDiag.J2534Bridge;

internal sealed partial class BridgeDesktopForm
{
    private J2534Device? consoleDevice;
    private CancellationTokenSource? doIpConsoleDiscoveryCancellation;
    private readonly TextBox doIpConsoleHost = new() { Dock = DockStyle.Fill };
    private readonly Button doIpConsoleDiscover = Button(DoIpConsolePresentation.Text("Discover"));
    private readonly Button doIpConsoleCancel = Button(DoIpConsolePresentation.Text("Cancel"));
    private readonly TextBox consoleRequestId = new()
        { Text = "7E0", Dock = DockStyle.Fill };
    private readonly TextBox consoleResponseId = new()
        { Text = "7E8", Dock = DockStyle.Fill };
    private readonly ComboBox consoleProtocol = new()
        { DropDownStyle = ComboBoxStyle.DropDownList, Dock = DockStyle.Fill };
    private readonly TextBox consolePayload = new()
        { Text = "22 F1 90", Dock = DockStyle.Fill };
    private readonly NumericUpDown consoleTimeout = new()
        { Minimum = 100, Maximum = 10000, Increment = 100, Value = 3000, Dock = DockStyle.Fill };
    private readonly RichTextBox consoleHistory = new()
    {
        ReadOnly = true, Multiline = true, Dock = DockStyle.Fill, WordWrap = false,
        Font = new Font("Consolas", 10f), BackColor = Color.FromArgb(248, 249, 249)
    };
    private readonly Button consoleOpen = Button("Ouvrir canal J2534");
    private readonly Button consoleClose = Button("Fermer canal");
    private readonly Button consoleSend = Button("Envoyer / recevoir");
    private readonly Button consoleSendOnly = Button("Envoyer sans réponse");
    private readonly Button consoleIdentify = Button("Identité OBD Mode 09");
    private readonly Button consoleGmlan = Button("Identité GM GMLAN");
    private readonly Button consoleBattery = Button("Lire tension");
    private readonly Button consoleSave = Button("Enregistrer l'historique");

    private TabPage BuildConsoleTab()
    {
        var tab = new TabPage("Console CAN / OBD");
        var outer = new TableLayoutPanel { Dock = DockStyle.Fill, RowCount = 2, ColumnCount = 1 };
        outer.RowStyles.Add(new RowStyle(SizeType.Percent, 58));
        outer.RowStyles.Add(new RowStyle(SizeType.Percent, 42));
        outer.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        var fields = Grid();
        foreach (var p in new[] { "33", "34", "35", "36", "63", "64" })
            consoleProtocol.Items.Add(p);
        consoleProtocol.SelectedItem = "33";
        Row(fields, "CAN requête (hex)", consoleRequestId);
        Row(fields, "CAN réponse (hex)", consoleResponseId);
        Row(fields, "Profil de communication", consoleProtocol);
        Row(fields, "Délai réponse (ms)", consoleTimeout);
        var connectionButtons = new FlowLayoutPanel { Dock = DockStyle.Fill };
        connectionButtons.Controls.Add(consoleOpen);
        connectionButtons.Controls.Add(consoleClose);
        consoleClose.Enabled = false;
        consoleOpen.Click += async (_, _) => await OpenConsoleChannelAsync();
        consoleClose.Click += async (_, _) => await CloseConsoleChannelAsync();
        WideRow(fields, connectionButtons, 44);
        Row(fields, "Trame hexadécimale", consolePayload, consoleSend);
        consoleSend.Click += async (_, _) => await ExchangeConsoleAsync(noResponse: false);
        consoleSendOnly.Click += async (_, _) => await ExchangeConsoleAsync(noResponse: true);
        WideRow(fields, consoleSendOnly, 43);
        var identityButtons = new FlowLayoutPanel { Dock = DockStyle.Fill };
        foreach (var b in new[] { consoleIdentify, consoleGmlan, consoleBattery, consoleSave })
            identityButtons.Controls.Add(b);
        consoleIdentify.Click += async (_, _) => await ConsoleIdentityAsync(gmlan: false);
        consoleGmlan.Click += async (_, _) => await ConsoleIdentityAsync(gmlan: true);
        consoleBattery.Click += async (_, _) => await ConsoleBatteryAsync();
        consoleSave.Click += (_, _) => SaveConsoleHistory();
        WideRow(fields, identityButtons, 48);
        Row(fields, DoIpConsolePresentation.Text("Host"), doIpConsoleHost, doIpConsoleDiscover);
        doIpConsoleCancel.Enabled = false;
        doIpConsoleDiscover.Click += async (_, _) => await DiscoverDoIpFromConsoleAsync();
        doIpConsoleCancel.Click += (_, _) => doIpConsoleDiscoveryCancellation?.Cancel();
        WideRow(fields, doIpConsoleCancel, 43);
        WideRow(fields, new Label
        {
            Text = "Console J2534 locale : TX et RX séparés ; commandes ECU transmises selon " +
                "le protocole et le pilote réellement sélectionnés, sans liste blanche CamaroDiag. " +
                "Les écritures et déverrouillages restent soumis aux réponses du calculateur. " +
                "Fermez ce canal avant de démarrer le pont Android.",
            Dock = DockStyle.Fill, AutoSize = true, ForeColor = Color.DarkSlateGray
        }, 78);
        outer.Controls.Add(fields, 0, 0);
        outer.Controls.Add(consoleHistory, 0, 1);
        tab.Controls.Add(outer);
        ConsoleLine("SYSTEM", "Console prête. Sélectionnez l'interface dans Connexion Android et ouvrez un canal.", Color.DarkSlateGray);
        return tab;
    }

    private async Task DiscoverDoIpFromConsoleAsync()
    {
        if (busy || doIpConsoleDiscoveryCancellation is not null) return;
        if (consoleDevice is not null || bridgeTask is { IsCompleted: false })
        {
            ShowError(DoIpConsolePresentation.Text("Busy"));
            return;
        }
        var host = doIpConsoleHost.Text.Trim();
        if (!System.Net.IPAddress.TryParse(host, out var address) ||
            address.Equals(System.Net.IPAddress.Any) ||
            address.Equals(System.Net.IPAddress.IPv6Any) ||
            address.Equals(System.Net.IPAddress.Broadcast) ||
            address.IsIPv6Multicast)
        {
            ShowError(DoIpConsolePresentation.Text("InvalidHost"));
            return;
        }
        var timeout = TimeSpan.FromMilliseconds((int)consoleTimeout.Value);
        using var stop = new CancellationTokenSource();
        doIpConsoleDiscoveryCancellation = stop;
        doIpConsoleDiscover.Enabled = false;
        doIpConsoleCancel.Enabled = true;
        try
        {
            await RunBusyAsync(async () =>
            {
                ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("Started"),
                    address), Color.DarkSlateGray);
                try
                {
                    var identity = await DoIpVehicleDiscovery.DiscoverAsync(
                        address.ToString(), timeout, stop.Token);
                    stop.Token.ThrowIfCancellationRequested();
                    if (IsDisposed || Disposing) return;
                    ConsoleLine("SYSTEM", DoIpConsolePresentation.FormatIdentity(identity),
                        Color.DarkGreen);
                    ConsoleLine("SYSTEM", DoIpConsolePresentation.Text("Notice"),
                        Color.DarkSlateGray);
                }
                catch (OperationCanceledException) when (stop.IsCancellationRequested)
                {
                    if (!IsDisposed && !Disposing)
                        ConsoleLine("SYSTEM", DoIpConsolePresentation.Text("Canceled"),
                            Color.DarkSlateGray);
                }
            });
        }
        finally
        {
            doIpConsoleDiscoveryCancellation = null;
            if (!IsDisposed && !Disposing)
            {
                doIpConsoleDiscover.Enabled = true;
                doIpConsoleCancel.Enabled = false;
            }
        }
    }

    private async Task OpenConsoleChannelAsync()
    {
        if (busy || consoleDevice != null) return;
        if (bridgeTask is { IsCompleted: false })
        {
            ShowError("Arrêtez le pont Android avant d'ouvrir la console locale.");
            return;
        }
        if (adapter.SelectedItem is not AdapterChoice selected)
        {
            ShowError("Aucun pilote J2534 sélectionné.");
            return;
        }
        NativeGmlanBenchProbe.Options chosen;
        try
        {
            chosen = NativeGmlanBenchProbe.Parse([
                NativeGmlanBenchProbe.Command, "--adapter-index", adapter.SelectedIndex.ToString(),
                "--request-can-id", consoleRequestId.Text.Trim(),
                "--response-can-id", consoleResponseId.Text.Trim(),
                "--protocol", consoleProtocol.SelectedItem?.ToString() ?? ""
            ]);
        }
        catch (ArgumentException ex) { ShowError(ex.Message); return; }

        await RunBusyAsync(async () =>
        {
            var device = new J2534Device(selected.Adapter);
            try
            {
                await Task.Run(() => device.Open(chosen.RequestCanId, chosen.ResponseCanId,
                    chosen.Can29Bit, chosen.ProtocolNumber));
                consoleDevice = device;
                consoleOpen.Enabled = false;
                consoleClose.Enabled = true;
                ConsoleLine("SYSTEM", $"Canal ouvert : {selected.Adapter.DisplayName}, " +
                    $"TX 0x{chosen.RequestCanId:X}, RX 0x{chosen.ResponseCanId:X}, " +
                    $"profil {chosen.ProtocolNumber}.", Color.DarkSlateGray);
            }
            catch { device.Dispose(); throw; }
        });
    }

    private async Task CloseConsoleChannelAsync()
    {
        if (busy || consoleDevice is null) return;
        await RunBusyAsync(async () =>
        {
            var active = consoleDevice;
            await Task.Run(() => active.Close());
            active.Dispose();
            consoleDevice = null;
            consoleOpen.Enabled = true;
            consoleClose.Enabled = false;
            ConsoleLine("SYSTEM", "Canal J2534 fermé.", Color.DarkSlateGray);
        });
    }

    /// <summary>
    /// Two distinct user actions before an arbitrary CAN/UDS transmission that
    /// could modify an ECU. The warning OK does NOT transmit. No/cancel/close
    /// returns before any J2534 driver call. A changed device invalidates it.
    /// </summary>
    private bool ConfirmPotentialEcuWrite(J2534Device device, string payloadHex)
    {
        var warning = MessageBox.Show(this,
            "Une commande va être transmise à un calculateur ou à un élément connecté. " +
            "Elle peut entraîner une écriture, une modification ou un effacement, " +
            "et rendre cet élément inutilisable.\n\n" +
            "CamaroDiag décline toute responsabilité pour les conséquences de " +
            "cette opération, dans les limites prévues par la loi.\n\n" +
            "Cliquez sur OK pour accéder à la confirmation.\nCommande : " + payloadHex,
            "Avertissement avant écriture ECU",
            MessageBoxButtons.OKCancel, MessageBoxIcon.Warning,
            MessageBoxDefaultButton.Button2);
        if (warning != DialogResult.OK || !ReferenceEquals(consoleDevice, device))
            return false;

        var confirmation = MessageBox.Show(this,
            "Confirmez-vous l'envoi de cette commande pouvant écrire sur " +
            "l'élément ciblé, en connaissance des risques ?\n\n" +
            "Commande : " + payloadHex + "\n\n" +
            "Non = aucune commande ne sera envoyée.",
            "Confirmer l'écriture ECU",
            MessageBoxButtons.YesNo, MessageBoxIcon.Question,
            MessageBoxDefaultButton.Button2);
        return confirmation == DialogResult.Yes &&
            ReferenceEquals(consoleDevice, device);
    }

    private async Task ExchangeConsoleAsync(bool noResponse)
    {
        if (busy) return;
        var device = consoleDevice;
        if (device is null) { ShowError("Ouvrez d'abord le canal J2534."); return; }
        byte[] payload;
        try
        {
            var compact = string.Concat(consolePayload.Text.Where(c => !char.IsWhiteSpace(c)));
            if (compact.Length is < 2 or > 8182 || compact.Length % 2 != 0 ||
                compact.Any(c => !Uri.IsHexDigit(c)))
                throw new ArgumentException("Saisissez une trame hexadécimale valide.");
            payload = Convert.FromHexString(compact);
        }
        catch (ArgumentException ex) { ShowError(ex.Message); return; }

        // This console deliberately accepts OEM-specific services. Because
        // an unknown payload can mutate an ECU, obtain two-step consent
        // before ANY raw emission rather than guessing a safe SID list.
        var confirmedPayload = Convert.ToHexString(payload);
        if (!ConfirmPotentialEcuWrite(device, confirmedPayload))
        {
            ConsoleLine("SYSTEM", "Envoi annulé : aucune commande transmise.",
                Color.DarkSlateGray);
            return;
        }

        await RunBusyAsync(async () =>
        {
            var frameHex = Convert.ToHexString(payload);
            // A TX row records the operator's attempted send, never an
            // unconditional claim that the J2534 device queued it on the bus.
            ConsoleLine("TX", frameHex + " [" +
                BridgeConsoleTxEvidence.Label(BridgeConsoleTxState.Requested) + "]",
                Color.DarkOrange);
            try
            {
                if (noResponse)
                {
                    await Task.Run(() => device.SendConsoleOnly(payload));
                    // PassThruWriteMsgs only proves acceptance by the driver:
                    // without an ECU reply, physical bus delivery is unknown.
                    ConsoleLine("SYSTEM", frameHex + " [" +
                        BridgeConsoleTxEvidence.Label(BridgeConsoleTxState.DriverAccepted) + "]",
                        Color.DarkSlateGray);
                }
                else
                {
                    var reply = await Task.Run(() => device.ExchangeConsole(payload, (int)consoleTimeout.Value));
                    ConsoleLine("SYSTEM", frameHex + " [" +
                        BridgeConsoleTxEvidence.Label(BridgeConsoleTxState.EcuReplied) + "]",
                        Color.DarkGreen);
                    ConsoleLine("RX", Convert.ToHexString(reply), Color.DarkGreen);
                }
            }
            catch (Exception)
            {
                // A timeout does not prove whether a frame was transmitted;
                // retain the attempted TX and append its explicit final status.
                ConsoleLine("SYSTEM", frameHex + " [" +
                    BridgeConsoleTxEvidence.Label(BridgeConsoleTxState.Unconfirmed) + "]",
                    Color.DarkRed);
                throw;
            }
        });
    }

    private async Task ConsoleIdentityAsync(bool gmlan)
    {
        if (busy) return;
        var device = consoleDevice;
        if (device is null) { ShowError("Ouvrez d'abord le canal J2534."); return; }
        await RunBusyAsync(async () =>
        {
            object value;
            if (gmlan)
            {
                ConsoleLine("SYSTEM", "Demande de lecture d'identité GM GMLAN en cours (1A 90 / 1A C1 / 1A C9).", Color.DarkSlateGray);
                value = await Task.Run(() => device.ReadGmlanIdentification());
            }
            else
            {
                ConsoleLine("SYSTEM", "Demande de lecture d'identité OBD Mode 09 en cours (09 02 / 09 04 / 09 06 / 09 0A).", Color.DarkSlateGray);
                value = await Task.Run(() => device.ReadCalibrationIdentification());
            }
            // The returned identity is a decoded aggregate of several ECU
            // responses, not one byte-for-byte physical RX frame.
            ConsoleLine("SYSTEM", "Identité ECU lue et décodée : " +
                JsonSerializer.Serialize(value,
                    new JsonSerializerOptions { WriteIndented = true }), Color.DarkGreen);
        });
    }

    private async Task ConsoleBatteryAsync()
    {
        if (busy) return;
        var device = consoleDevice;
        if (device is null) { ShowError("Ouvrez d'abord le canal J2534."); return; }
        await RunBusyAsync(async () =>
        {
            var mv = await Task.Run(() => device.ReadBatteryMillivolts());
            ConsoleLine("SYSTEM", mv.HasValue
                ? $"Tension rapportée par le pilote J2534 : {mv.Value / 1000.0:F2} V."
                : "Le pilote J2534 ne fournit pas la tension.", Color.DarkSlateGray);
        });
    }

    private void SaveConsoleHistory()
    {
        using var picker = new SaveFileDialog
        {
            Title = "Exporter la console CAN/OBD",
            Filter = "Texte (*.txt)|*.txt", AddExtension = true,
            FileName = "CamaroDiag-Console.txt"
        };
        if (!PrepareExportDialog(picker, UserExportStorage.Category.Console, "CamaroDiag-Console.txt")) return;
        if (picker.ShowDialog(this) != DialogResult.OK) return;
        try
        {
            using var stream = new FileStream(picker.FileName,
                FileMode.CreateNew, FileAccess.Write, FileShare.None);
            var bytes = Encoding.UTF8.GetBytes(consoleHistory.Text);
            stream.Write(bytes);
            stream.Flush(flushToDisk: true);
            ConsoleLine("SYSTEM", "Historique enregistré : " + picker.FileName, Color.DarkSlateGray);
        }
        catch (IOException ex)
        {
            ShowError("Impossible d'enregistrer la console (fichier existant ?) : " + ex.Message);
        }
        catch (UnauthorizedAccessException ex) { ShowError(ex.Message); }
    }

    private void ConsoleLine(string direction, string message, Color color)
    {
        consoleHistory.SelectionStart = consoleHistory.TextLength;
        consoleHistory.SelectionLength = 0;
        consoleHistory.SelectionColor = color;
        consoleHistory.AppendText($"{DateTime.Now:HH:mm:ss} [{BridgeConsoleDirection.Normalize(direction)}] {message}{Environment.NewLine}");
        consoleHistory.SelectionColor = consoleHistory.ForeColor;
        consoleHistory.ScrollToCaret();
    }
}
