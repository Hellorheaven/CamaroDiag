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
    private readonly TextBox doIpExpectedVin = new()
        { Dock = DockStyle.Fill, MaxLength = 17, CharacterCasing = CharacterCasing.Upper };
    private bool doIpPublishingHost;
    private readonly Button doIpConsoleDiscover = Button(DoIpConsolePresentation.Text("Discover"));
    private readonly Button doIpConsoleCancel = Button(DoIpConsolePresentation.Text("Cancel"));
    private readonly Button doIpConsoleStatus = Button(DoIpConsolePresentation.Text("Status"));
    private readonly Button doIpConsolePower = Button(DoIpConsolePresentation.Text("Power"));
    private DoIpVehicleDiscovery.Identity? doIpObservedGateway;
    private readonly TextBox doIpTesterAddress = new() { Dock = DockStyle.Fill };
    private readonly TextBox doIpEcuAddress = new() { Dock = DockStyle.Fill };
    private readonly CheckBox doIpUseTls = new()
        { Text = DoIpConsolePresentation.Text("UseTls"), Checked = true, AutoSize = true };
    private readonly TextBox doIpClientCertificate = new() { Dock = DockStyle.Fill };
    private enum DoIpReadOperation { Vin, Dtcs, DtcCount, DtcSnapshot, DtcExtended, Did, MonitorDid, Backup, Exchange, ObdIdentify, ObdDid }
    private readonly Button doIpCountDtcs = Button(DoIpConsolePresentation.Text("CountDtcs"));
    private readonly Button doIpSnapshot = Button(DoIpConsolePresentation.Text("Snapshot"));
    private readonly Button doIpExtended = Button(DoIpConsolePresentation.Text("Extended"));
    private readonly TextBox doIpDetailDtc = new() { Dock = DockStyle.Fill, MaxLength = 6 };
    private readonly TextBox doIpDetailRecord = new() { Dock = DockStyle.Fill, MaxLength = 2, Text = "01" };
    private readonly TextBox doIpPayload = new() { Dock = DockStyle.Fill, MaxLength = 12288 };
    private readonly Button doIpExchange = Button(DoIpConsolePresentation.Text("Exchange"));
    private readonly Button doIpBackup = Button(DoIpConsolePresentation.Text("Backup"));
    private readonly Button doIpVerifyBackup = Button(DoIpConsolePresentation.Text("BackupVerify"));
    private readonly Button doIpMonitorDid = Button(DoIpConsolePresentation.Text("MonitorDid"));
    private readonly NumericUpDown doIpMonitorInterval = new()
        { Minimum = 250, Maximum = 60000, Increment = 250, Value = 1000, Dock = DockStyle.Fill };
    private readonly NumericUpDown doIpMonitorCount = new()
        { Minimum = 1, Maximum = 3600, Value = 60, Dock = DockStyle.Fill };
    private readonly Button doIpReadDid = Button(DoIpConsolePresentation.Text("ReadDid"));
    private readonly Button doIpObdIdentify = Button(DoIpConsolePresentation.Text("ObdIdentify"));
    private readonly Button doIpObdDid = Button(DoIpConsolePresentation.Text("ObdRead"));
    private readonly TextBox doIpDid = new() { Dock = DockStyle.Fill };
    private readonly Button doIpReadVin = Button(DoIpConsolePresentation.Text("ReadVin"));
    private readonly Button doIpReadDtcs = Button(DoIpConsolePresentation.Text("ReadDtcs"));
    private readonly TextBox doIpDtcMask = new() { Dock = DockStyle.Fill, Text = "FF" };
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
        Row(fields, DoIpConsolePresentation.Text("ExpectedVin"), doIpExpectedVin);
        Row(fields, DoIpConsolePresentation.Text("Host"), doIpConsoleHost, doIpConsoleDiscover);
        doIpConsoleCancel.Enabled = false;
        doIpConsoleDiscover.Click += async (_, _) => await DiscoverDoIpFromConsoleAsync();
        doIpConsoleCancel.Click += (_, _) => doIpConsoleDiscoveryCancellation?.Cancel();
        var doIpOperations = new FlowLayoutPanel { Dock = DockStyle.Fill };
        doIpOperations.Controls.Add(doIpConsoleStatus);
        doIpOperations.Controls.Add(doIpConsolePower);
        doIpOperations.Controls.Add(doIpConsoleCancel);
        doIpConsoleStatus.Enabled = doIpConsolePower.Enabled = doIpReadDtcs.Enabled = doIpCountDtcs.Enabled = doIpSnapshot.Enabled = doIpExtended.Enabled = doIpBackup.Enabled = doIpMonitorDid.Enabled = doIpReadDid.Enabled = doIpObdIdentify.Enabled = doIpObdDid.Enabled = doIpExchange.Enabled = doIpReadVin.Enabled = false;
        doIpConsoleStatus.Click += async (_, _) => await RunDoIpConsoleOperationAsync(false);
        doIpConsolePower.Click += async (_, _) => await RunDoIpConsoleOperationAsync(true);
        doIpConsoleHost.TextChanged += (_, _) =>
        {
            if (doIpPublishingHost) return;
            doIpObservedGateway = null;
            doIpConsoleStatus.Enabled = doIpConsolePower.Enabled = doIpReadDtcs.Enabled = doIpCountDtcs.Enabled = doIpSnapshot.Enabled = doIpExtended.Enabled = doIpBackup.Enabled = doIpMonitorDid.Enabled = doIpReadDid.Enabled = doIpObdIdentify.Enabled = doIpObdDid.Enabled = doIpExchange.Enabled = doIpReadVin.Enabled = false;
            doIpConsoleDiscoveryCancellation?.Cancel();
        };
        WideRow(fields, doIpOperations, 48);
        Row(fields, DoIpConsolePresentation.Text("TesterAddress"), doIpTesterAddress);
        Row(fields, DoIpConsolePresentation.Text("EcuAddress"), doIpEcuAddress, doIpReadVin);
        WideRow(fields, doIpUseTls, 38);
        Row(fields, DoIpConsolePresentation.Text("CertThumbprint"), doIpClientCertificate);
        doIpReadDtcs.Enabled = doIpCountDtcs.Enabled = doIpSnapshot.Enabled = doIpExtended.Enabled = doIpBackup.Enabled = doIpMonitorDid.Enabled = doIpReadDid.Enabled = doIpObdIdentify.Enabled = doIpObdDid.Enabled = doIpExchange.Enabled = doIpReadVin.Enabled = false;
        doIpReadVin.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.Vin);
        Row(fields, DoIpConsolePresentation.Text("DtcMask"), doIpDtcMask, doIpReadDtcs);
        doIpReadDtcs.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.Dtcs);
        WideRow(fields, doIpCountDtcs, 38);
        Row(fields, DoIpConsolePresentation.Text("DetailDtc"), doIpDetailDtc, doIpSnapshot);
        Row(fields, DoIpConsolePresentation.Text("DetailRecord"), doIpDetailRecord, doIpExtended);
        doIpSnapshot.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.DtcSnapshot);
        doIpExtended.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.DtcExtended);
        doIpCountDtcs.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.DtcCount);
        Row(fields, DoIpConsolePresentation.Text("Payload"), doIpPayload, doIpExchange);
        doIpExchange.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.Exchange);
        Row(fields, DoIpConsolePresentation.Text("DidLabel"), doIpDid, doIpReadDid);
        doIpReadDid.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.Did);
        WideRow(fields, doIpObdIdentify, 38);
        WideRow(fields, doIpObdDid, 38);
        doIpObdIdentify.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.ObdIdentify);
        doIpObdDid.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.ObdDid);
        Row(fields, DoIpConsolePresentation.Text("MonitorInterval"), doIpMonitorInterval, doIpMonitorDid);
        Row(fields, DoIpConsolePresentation.Text("MonitorCount"), doIpMonitorCount);
        doIpMonitorDid.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.MonitorDid);
        var backupButtons = new FlowLayoutPanel { Dock = DockStyle.Fill };
        backupButtons.Controls.Add(doIpBackup);
        backupButtons.Controls.Add(doIpVerifyBackup);
        WideRow(fields, backupButtons, 48);
        doIpVerifyBackup.Click += async (_, _) => await VerifyDoIpBackupFromConsoleAsync();
        doIpBackup.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.Backup);
        // A logical endpoint or security-mode change invalidates the
        // previously selected diagnostic context, even with the same IP.
        // Require new discovery rather than retaining stale gateway evidence.
        void InvalidateDoIpContext(object? sender, EventArgs args)
        {
            doIpObservedGateway = null;
            doIpConsoleStatus.Enabled = doIpConsolePower.Enabled = doIpReadDtcs.Enabled = doIpCountDtcs.Enabled = doIpSnapshot.Enabled = doIpExtended.Enabled = doIpBackup.Enabled = doIpMonitorDid.Enabled = doIpReadDid.Enabled = doIpObdIdentify.Enabled = doIpObdDid.Enabled = doIpExchange.Enabled = doIpReadVin.Enabled = false;
            doIpConsoleDiscoveryCancellation?.Cancel();
        }
        doIpExpectedVin.TextChanged += InvalidateDoIpContext;
        doIpTesterAddress.TextChanged += InvalidateDoIpContext;
        doIpEcuAddress.TextChanged += InvalidateDoIpContext;
        doIpUseTls.CheckedChanged += InvalidateDoIpContext;
        doIpClientCertificate.TextChanged += InvalidateDoIpContext;
        // WideRow(fields, new Label
        // {
        // Text = "Console J2534 locale : TX et RX séparés ; commandes ECU transmises selon " +
        // "le protocole et le pilote réellement sélectionnés, sans liste blanche CamaroDiag. " +
        // "Les écritures et déverrouillages restent soumis aux réponses du calculateur. " +
        // "Fermez ce canal avant de démarrer le pont Android.",
        // Dock = DockStyle.Fill, AutoSize = true, ForeColor = Color.DarkSlateGray
        // }, 78);
        outer.Controls.Add(fields, 0, 0);
        outer.Controls.Add(consoleHistory, 0, 1);
        tab.Controls.Add(outer);
        ConsoleLine("SYSTEM", "Console prête. Sélectionnez l'interface dans Connexion Android et ouvrez un canal.", Color.DarkSlateGray);
        return tab;
    }

    private void JournalDoIpUdp(string direction, System.Net.IPEndPoint endpoint, byte[] pdu)
    {
        if (IsDisposed || Disposing) return;
        ConsoleLine(direction, "DoIP UDP " + endpoint + " PDU " + Convert.ToHexString(pdu),
            direction == "TX" ? Color.DarkOrange : Color.DarkGreen);
    }

    private Task DiscoverDoIpFromConsoleAsync() => RunDoIpConsoleOperationAsync(null);

    // null = identification; false = entity status; true = power mode.
    private async Task RunDoIpConsoleOperationAsync(bool? powerMode)
    {
        if (busy || doIpConsoleDiscoveryCancellation is not null) return;
        if (consoleDevice is not null || bridgeTask is { IsCompleted: false })
        {
            ShowError(DoIpConsolePresentation.Text("Busy"));
            return;
        }
        var host = doIpConsoleHost.Text.Trim();
        var vin = doIpExpectedVin.Text.Trim();
        var auto = !powerMode.HasValue && host.Length == 0;
        if ((auto || vin.Length != 0) && !DoIpVehicleDiscovery.IsVehicleVin(vin))
        {
            ShowError(DoIpConsolePresentation.Text("InvalidExpectedVin"));
            return;
        }
        System.Net.IPAddress? address = null;
        if (!auto && (!System.Net.IPAddress.TryParse(host, out address) ||
            address.Equals(System.Net.IPAddress.Any) ||
            address.Equals(System.Net.IPAddress.IPv6Any) ||
            address.Equals(System.Net.IPAddress.Broadcast) ||
            address.IsIPv6Multicast))
        {
            ShowError(DoIpConsolePresentation.Text("InvalidHost"));
            return;
        }
        var observed = doIpObservedGateway;
        if (powerMode.HasValue &&
            (observed is null || !observed.Source.Equals(address)))
        {
            ShowError(DoIpConsolePresentation.Text("IdentifyFirst"));
            return;
        }
        if (!powerMode.HasValue) doIpObservedGateway = null;
        var timeout = TimeSpan.FromMilliseconds((int)consoleTimeout.Value);
        using var stop = new CancellationTokenSource();
        doIpConsoleDiscoveryCancellation = stop;
        doIpConsoleDiscover.Enabled = doIpConsoleStatus.Enabled = doIpConsolePower.Enabled = doIpReadDtcs.Enabled = doIpCountDtcs.Enabled = doIpSnapshot.Enabled = doIpExtended.Enabled = doIpBackup.Enabled = doIpMonitorDid.Enabled = doIpReadDid.Enabled = doIpObdIdentify.Enabled = doIpObdDid.Enabled = doIpExchange.Enabled = doIpReadVin.Enabled = false;
        doIpConsoleHost.Enabled = doIpExpectedVin.Enabled = false;
        doIpConsoleCancel.Enabled = true;
        try
        {
            await RunBusyAsync(async () =>
            {
                try
                {
                    if (powerMode.HasValue)
                    {
                        var version = observed!.ProtocolVersion;
                        var selectedAddress = observed.Source;
                        ConsoleLine("SYSTEM", string.Format(
                            DoIpConsolePresentation.Text("StatusStarted"), selectedAddress, version),
                            Color.DarkSlateGray);
                        var response = await DoIpVehicleDiscovery.QueryStatusAsync(
                            selectedAddress.ToString(), powerMode.Value, timeout, stop.Token,
                            protocolVersion: version, trace: JournalDoIpUdp);
                        stop.Token.ThrowIfCancellationRequested();
                        if (IsDisposed || Disposing) return;
                        ConsoleLine("SYSTEM", DoIpConsolePresentation.FormatStatus(
                            response, powerMode.Value), Color.DarkGreen);
                        // ConsoleLine("SYSTEM", DoIpConsolePresentation.Text("StatusNotice"),
                            // Color.DarkSlateGray);
                    }
                    else
                    {
                        ConsoleLine("SYSTEM", auto
                            ? string.Format(DoIpConsolePresentation.Text("VinDiscoveryStarted"), vin)
                            : string.Format(DoIpConsolePresentation.Text("Started"), address), Color.DarkSlateGray);
                        var identity = auto
                            ? await DoIpVehicleDiscovery.DiscoverUniqueVinAsync(vin, timeout, stop.Token,
                                trace: JournalDoIpUdp)
                            : await DoIpVehicleDiscovery.DiscoverAsync(address!.ToString(), timeout, stop.Token,
                                vin: vin.Length == 0 ? null : vin, trace: JournalDoIpUdp);
                        stop.Token.ThrowIfCancellationRequested();
                        if (IsDisposed || Disposing) return;
                        if (auto)
                        {
                            doIpPublishingHost = true;
                            try { doIpConsoleHost.Text = identity.Source.ToString(); }
                            finally { doIpPublishingHost = false; }
                        }
                        doIpObservedGateway = identity;
                        ConsoleLine("SYSTEM", DoIpConsolePresentation.FormatIdentity(identity),
                            Color.DarkGreen);
                        // ConsoleLine("SYSTEM", DoIpConsolePresentation.Text("Notice"),
                            // Color.DarkSlateGray);
                    }
                }
                catch (OperationCanceledException) when (stop.IsCancellationRequested)
                {
                    doIpObservedGateway = null;
                    if (!IsDisposed && !Disposing)
                        ConsoleLine("SYSTEM", DoIpConsolePresentation.Text("Canceled"),
                            Color.DarkSlateGray);
                }
                catch
                {
                    doIpObservedGateway = null;
                    throw;
                }
            });
        }
        finally
        {
            doIpConsoleDiscoveryCancellation = null;
            if (!IsDisposed && !Disposing)
            {
                doIpConsoleHost.Enabled = doIpExpectedVin.Enabled = true;
                doIpConsoleDiscover.Enabled = true;
                doIpConsoleStatus.Enabled = doIpConsolePower.Enabled = doIpReadDtcs.Enabled = doIpCountDtcs.Enabled = doIpSnapshot.Enabled = doIpExtended.Enabled = doIpBackup.Enabled = doIpMonitorDid.Enabled = doIpReadDid.Enabled = doIpObdIdentify.Enabled = doIpObdDid.Enabled = doIpExchange.Enabled = doIpReadVin.Enabled = doIpObservedGateway is not null;
                doIpConsoleCancel.Enabled = false;
            }
        }
    }

    private async Task ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation operation)
    {
        if (busy || doIpConsoleDiscoveryCancellation is not null) return;
        if (consoleDevice is not null || bridgeTask is { IsCompleted: false })
        {
            ShowError(DoIpConsolePresentation.Text("Busy"));
            return;
        }
        var observed = doIpObservedGateway;
        if (observed is null ||
            !System.Net.IPAddress.TryParse(doIpConsoleHost.Text.Trim(), out var address) ||
            !observed.Source.Equals(address))
        {
            ShowError(DoIpConsolePresentation.Text("IdentifyFirst"));
            return;
        }
        ushort tester;
        ushort ecu;
        try
        {
            tester = DoIpVinReader.ParseLogicalAddress(doIpTesterAddress.Text.Trim());
            ecu = DoIpVinReader.ParseLogicalAddress(doIpEcuAddress.Text.Trim());
            if (tester == ecu) throw new ArgumentException();
        }
        catch (ArgumentException)
        {
            ShowError(DoIpConsolePresentation.Text("InvalidLogical"));
            return;
        }
        byte dtcMask = 0;
        if (operation is DoIpReadOperation.Dtcs or DoIpReadOperation.DtcCount)
        {
            try { dtcMask = DoIpConsolePresentation.ParseDtcMask(doIpDtcMask.Text.Trim()); }
            catch (ArgumentException)
            {
                ShowError(DoIpConsolePresentation.Text("InvalidDtcMask"));
                return;
            }
        }
        ushort did = 0;
        if (operation is DoIpReadOperation.Did or DoIpReadOperation.MonitorDid or DoIpReadOperation.ObdDid)
        {
            try { did = DoIpVinReader.ParseLogicalAddress(doIpDid.Text.Trim()); }
            catch (ArgumentException)
            {
                ShowError(DoIpConsolePresentation.Text("InvalidDid"));
                return;
            }
        }
        if (operation == DoIpReadOperation.ObdDid &&
            ObdOnUdsServices.ClassifyDid(did) == ObdOnUdsServices.DataGroup.NotLegislated)
        {
            ShowError(DoIpConsolePresentation.Text("ObdInvalidDid"));
            return;
        }
        var expectedObdVin = doIpExpectedVin.Text.Trim();
        if ((operation is DoIpReadOperation.ObdIdentify or DoIpReadOperation.ObdDid) &&
            (!DoIpVehicleDiscovery.IsVehicleVin(expectedObdVin) || observed.Vin != expectedObdVin))
        {
            ShowError(DoIpConsolePresentation.Text("ExpectedVinRequired"));
            return;
        }
        var interval = TimeSpan.FromMilliseconds((int)doIpMonitorInterval.Value);
        var sampleCount = (int)doIpMonitorCount.Value;
        var useTls = doIpUseTls.Checked;
        var thumbprint = string.Concat(doIpClientCertificate.Text.Where(c => !char.IsWhiteSpace(c)));
        if (thumbprint.Length != 0 &&
            (!useTls || thumbprint.Length != 40 || !thumbprint.All(Uri.IsHexDigit)))
        {
            ShowError(DoIpConsolePresentation.Text("InvalidCertificate"));
            return;
        }
        uint detailDtc = 0;
        byte detailRecord = 1;
        if (operation is DoIpReadOperation.DtcSnapshot or DoIpReadOperation.DtcExtended)
        {
            try
            {
                (detailDtc, detailRecord) = DoIpConsolePresentation.ParseDetail(doIpDetailDtc.Text.Trim(),
                    doIpDetailRecord.Text.Trim());
            }
            catch (ArgumentException)
            {
                ShowError(DoIpConsolePresentation.Text("InvalidDetail"));
                return;
            }
        }
        byte[][]? commands = null;
        if (operation == DoIpReadOperation.Exchange)
        {
            try { commands = DoIpConsolePresentation.ParseCommands(doIpPayload.Text); }
            catch (ArgumentException)
            {
                ShowError(DoIpConsolePresentation.Text("InvalidPayload"));
                return;
            }
            if (!DoIpVehicleDiscovery.IsVehicleVin(doIpExpectedVin.Text.Trim()) ||
                observed.Vin != doIpExpectedVin.Text.Trim())
            {
                ShowError(DoIpConsolePresentation.Text("ExpectedVinRequired"));
                return;
            }
            var details = string.Format(DoIpConsolePresentation.Text("ExchangeTarget"),
                observed.Vin, address, ecu, tester, useTls ? "TLS" : "TCP",
                string.Join("; ", commands.Select(x => Convert.ToHexString(x))));
            bool SameContext() => !busy && doIpConsoleDiscoveryCancellation is null &&
                ReferenceEquals(observed, doIpObservedGateway) && consoleDevice is null &&
                bridgeTask is not { IsCompleted: false };
            if (MessageBox.Show(this, DoIpConsolePresentation.Text("ExchangeWarning") + "\n\n" + details,
                    DoIpConsolePresentation.Text("ExchangeWarningTitle"), MessageBoxButtons.OKCancel,
                    MessageBoxIcon.Warning, MessageBoxDefaultButton.Button2) != DialogResult.OK || !SameContext())
                return;
            if (MessageBox.Show(this, DoIpConsolePresentation.Text("ExchangeConfirm") + "\n\n" + details,
                    DoIpConsolePresentation.Text("ExchangeWarningTitle"), MessageBoxButtons.YesNo,
                    MessageBoxIcon.Warning, MessageBoxDefaultButton.Button2) != DialogResult.Yes || !SameContext())
                return;
        }
        DoIpBackupScope? backupScope = null;
        string? backupPath = null;
        if (operation == DoIpReadOperation.Backup)
        {
            using var profile = new OpenFileDialog
            {
                Title = DoIpConsolePresentation.Text("BackupProfile"), Filter = "JSON (*.json)|*.json"
            };
            if (profile.ShowDialog(this) != DialogResult.OK) return;
            try
            {
                backupScope = DoIpBackupScope.Load(profile.FileName);
                if (backupScope.Gateway != observed.LogicalAddress || backupScope.Tester != tester ||
                    backupScope.Ecu != ecu || (observed.Vin is not null && observed.Vin != backupScope.Vin))
                    throw new InvalidDataException(DoIpConsolePresentation.Text("BackupMismatch"));
            }
            catch (Exception error) when (error is ArgumentException or IOException or JsonException or
                UnauthorizedAccessException or FormatException or OverflowException or InvalidOperationException)
            {
                ShowError(error.Message);
                return;
            }
            using var destination = new SaveFileDialog
            {
                Title = DoIpConsolePresentation.Text("Backup"),
                Filter = "ZIP (*.zip)|*.zip", DefaultExt = "zip",
                AddExtension = true, FileName = "CamaroDiag_DoIP_" + backupScope.Vin + ".zip"
            };
            if (destination.ShowDialog(this) != DialogResult.OK) return;
            if (File.Exists(destination.FileName))
            {
                ShowError(DoIpConsolePresentation.Text("BackupExists"));
                return;
            }
            backupPath = destination.FileName;
            if (MessageBox.Show(this, string.Format(DoIpConsolePresentation.Text("BackupConfirm"),
                backupScope.Vin, backupScope.Ecu, backupScope.Start, backupScope.Length),
                DoIpConsolePresentation.Text("Backup"), MessageBoxButtons.YesNo,
                MessageBoxIcon.Warning, MessageBoxDefaultButton.Button2) != DialogResult.Yes) return;
            if (!ReferenceEquals(observed, doIpObservedGateway)) return;
        }
        var timeout = TimeSpan.FromMilliseconds((int)consoleTimeout.Value);
        using var stop = new CancellationTokenSource();
        doIpConsoleDiscoveryCancellation = stop;
        doIpConsoleDiscover.Enabled = doIpConsoleStatus.Enabled =
            doIpConsolePower.Enabled = doIpReadDtcs.Enabled = doIpCountDtcs.Enabled = doIpSnapshot.Enabled = doIpExtended.Enabled = doIpBackup.Enabled = doIpMonitorDid.Enabled = doIpReadDid.Enabled = doIpObdIdentify.Enabled = doIpObdDid.Enabled = doIpExchange.Enabled = doIpReadVin.Enabled = false;
        doIpConsoleHost.Enabled = doIpExpectedVin.Enabled = doIpTesterAddress.Enabled =
            doIpEcuAddress.Enabled = doIpPayload.Enabled = doIpDetailDtc.Enabled = doIpDetailRecord.Enabled = doIpDtcMask.Enabled = doIpDid.Enabled = doIpMonitorInterval.Enabled = doIpMonitorCount.Enabled = doIpUseTls.Enabled = doIpClientCertificate.Enabled = false;
        doIpConsoleCancel.Enabled = true;
        // The receive pump runs on a worker thread. Queue physical PDUs
        // without touching WinForms controls, and drain only on the UI thread.
        var pendingTrace = new System.Collections.Concurrent.ConcurrentQueue<DoIpDiagnosticTransport.PduTrace>();
        long pendingBytes = 0;
        int traceOverflow = 0;
        void Trace(DoIpDiagnosticTransport.PduTrace entry)
        {
            if (Interlocked.Add(ref pendingBytes, entry.Bytes.Length) > 16L * 1024 * 1024)
            {
                Interlocked.Add(ref pendingBytes, -entry.Bytes.Length);
                Interlocked.Exchange(ref traceOverflow, 1);
                throw new IOException("DoIP console trace backlog exceeded; capture incomplete.");
            }
            pendingTrace.Enqueue(entry);
        }
        void DrainTrace()
        {
            while (pendingTrace.TryDequeue(out var entry))
            {
                Interlocked.Add(ref pendingBytes, -entry.Bytes.Length);
                if (!IsDisposed && !Disposing)
                    ConsoleLine(entry.Direction, "DoIP " + (useTls ? "TLS" : "TCP") + " " +
                        address + " PDU " + Convert.ToHexString(entry.Bytes),
                        entry.Direction == "TX" ? Color.DarkOrange : Color.DarkGreen,
                        entry.ObservedAtUtc);
            }
        }
        void RequireCompleteTrace()
        {
            DrainTrace();
            if (Volatile.Read(ref traceOverflow) != 0)
                throw new IOException(DoIpConsolePresentation.Text("TraceIncomplete"));
        }
        using var traceTimer = new System.Windows.Forms.Timer { Interval = 100 };
        traceTimer.Tick += (_, _) => DrainTrace();
        traceTimer.Start();
        try
        {
            await RunBusyAsync(async () =>
            {
                try
                {
                    ConsoleLine("SYSTEM", string.Format(
                        DoIpConsolePresentation.Text(operation switch { DoIpReadOperation.Exchange => "ExchangeStarted", DoIpReadOperation.Backup => "BackupStarted", DoIpReadOperation.Dtcs or DoIpReadOperation.DtcCount or DoIpReadOperation.DtcSnapshot or DoIpReadOperation.DtcExtended => "DtcStarted", DoIpReadOperation.Did or DoIpReadOperation.MonitorDid => "DidStarted", _ => "VinStarted" }),
                        useTls ? "TLS" : "TCP", address, ecu, tester, observed.ProtocolVersion),
                        Color.DarkSlateGray);
                    var certificate = thumbprint.Length == 0 ? null : thumbprint;
                    if (operation == DoIpReadOperation.Backup)
                    {
                        var scope = backupScope ?? throw new InvalidOperationException();
                        var destination = backupPath ?? throw new InvalidOperationException();
                        var capture = await DoIpScopedBackup.ReadObservedAsync(observed, address,
                            scope, useTls, timeout, stop.Token, (pass, count) =>
                            {
                                DrainTrace();
                                if (!IsDisposed && !Disposing)
                                    ConsoleLine("SYSTEM", string.Format(
                                        DoIpConsolePresentation.Text("BackupProgress"), pass, count, scope.Length),
                                        Color.DarkSlateGray);
                            }, certificate, trace: Trace);
                        RequireCompleteTrace();
                        stop.Token.ThrowIfCancellationRequested();
                        await Task.Run(() => DoIpScopedBackup.SaveNew(destination, capture, stop.Token),
                            stop.Token);
                        if (!IsDisposed && !Disposing)
                            ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("BackupSaved"),
                                scope.Length, capture.Sha256, destination), Color.DarkGreen);
                    }
                    else if (operation == DoIpReadOperation.Exchange)
                    {
                        var count = await DoIpVinReader.ExchangeSequenceObservedAsync(observed,
                            address, tester, ecu, useTls, commands ?? throw new InvalidOperationException(),
                            timeout, stop.Token, (index, reply) =>
                            {
                                RequireCompleteTrace();
                                if (!IsDisposed && !Disposing)
                                    ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("SequenceReply"),
                                        index, Convert.ToHexString(reply)), Color.DarkGreen);
                            }, certificate, Trace);
                        RequireCompleteTrace();
                        stop.Token.ThrowIfCancellationRequested();
                        if (!IsDisposed && !Disposing)
                            ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("SequenceComplete"),
                                count), Color.DarkGreen);
                    }
                    else if (operation is DoIpReadOperation.DtcSnapshot or DoIpReadOperation.DtcExtended)
                    {
                        var detail = await DoIpVinReader.ReadObservedGatewayDtcDetailAsync(observed,
                            address, tester, ecu, useTls, detailDtc, detailRecord,
                            operation == DoIpReadOperation.DtcExtended, timeout, stop.Token, certificate, Trace);
                        RequireCompleteTrace();
                        stop.Token.ThrowIfCancellationRequested();
                        if (!IsDisposed && !Disposing)
                            ConsoleLine("SYSTEM", DoIpConsolePresentation.FormatDetail(detail.Dtc, detail.Status, detail.Records), Color.DarkGreen);
                    }
                    else if (operation == DoIpReadOperation.DtcCount)
                    {
                        var report = await DoIpVinReader.CountObservedGatewayDtcsAsync(observed,
                            address, tester, ecu, useTls, dtcMask, timeout, stop.Token, certificate, Trace);
                        RequireCompleteTrace();
                        stop.Token.ThrowIfCancellationRequested();
                        if (!IsDisposed && !Disposing)
                            ConsoleLine("SYSTEM", DoIpConsolePresentation.FormatDtcCount(report, dtcMask), Color.DarkGreen);
                    }
                    else if (operation == DoIpReadOperation.Dtcs)
                    {
                        var report = await DoIpVinReader.ReadObservedGatewayDtcsAsync(
                            observed, address, tester, ecu, useTls, dtcMask, timeout, stop.Token,
                            clientCertificateThumbprint: certificate, trace: Trace);
                        RequireCompleteTrace();
                        stop.Token.ThrowIfCancellationRequested();
                        if (IsDisposed || Disposing) return;
                        foreach (var line in DoIpConsolePresentation.FormatDtcs(report, dtcMask))
                            ConsoleLine("SYSTEM", line, Color.DarkSlateGray);
                    }
                    else if (operation == DoIpReadOperation.MonitorDid)
                    {
                        await DoIpVinReader.MonitorObservedGatewayDidAsync(
                            observed, address, tester, ecu, useTls, did, sampleCount, interval,
                            timeout, stop.Token, (index, data) =>
                            {
                                DrainTrace();
                                if (!IsDisposed && !Disposing)
                                    ConsoleLine("SYSTEM", string.Format(
                                        DoIpConsolePresentation.Text("MonitorSample"), index,
                                        did, Convert.ToHexString(data)), Color.DarkGreen);
                            }, clientCertificateThumbprint: certificate, trace: Trace);
                        RequireCompleteTrace();
                        stop.Token.ThrowIfCancellationRequested();
                        if (!IsDisposed && !Disposing)
                            ConsoleLine("SYSTEM", string.Format(
                                DoIpConsolePresentation.Text("MonitorComplete"), sampleCount),
                                Color.DarkGreen);
                    }
                    else if (operation is DoIpReadOperation.ObdIdentify or DoIpReadOperation.ObdDid)
                    {
                        var obd = await DoIpVinReader.ReadObservedObdAsync(observed, address, tester, ecu,
                            useTls, expectedObdVin, operation == DoIpReadOperation.ObdDid ? did : null,
                            timeout, stop.Token, certificate, Trace);
                        RequireCompleteTrace();
                        stop.Token.ThrowIfCancellationRequested();
                        if (IsDisposed || Disposing) return;
                        ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text(
                            obd.ProtocolValue == 1 ? "ObdVerified" : "ObdUnknown"),
                            obd.ProtocolValue, obd.Vin), obd.ProtocolValue == 1 ? Color.DarkGreen : Color.DarkOrange);
                        if (obd.Data is not null)
                            ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("DidResult"),
                                did, Convert.ToHexString(obd.Data)), Color.DarkGreen);
                    }
                    else if (operation == DoIpReadOperation.Did)
                    {
                        var data = await DoIpVinReader.ReadObservedGatewayDidAsync(
                            observed, address, tester, ecu, useTls, did, timeout, stop.Token,
                            clientCertificateThumbprint: certificate, trace: Trace);
                        RequireCompleteTrace();
                        stop.Token.ThrowIfCancellationRequested();
                        if (IsDisposed || Disposing) return;
                        ConsoleLine("SYSTEM", string.Format(
                            DoIpConsolePresentation.Text("DidResult"), did, Convert.ToHexString(data)),
                            Color.DarkGreen);
                    }
                    else
                    {
                        var vin = await DoIpVinReader.ReadObservedGatewayAsync(
                            observed, address, tester, ecu, useTls, timeout, stop.Token,
                            clientCertificateThumbprint: certificate, trace: Trace);
                        RequireCompleteTrace();
                        stop.Token.ThrowIfCancellationRequested();
                        if (IsDisposed || Disposing) return;
                        ConsoleLine("SYSTEM", string.Format(
                            DoIpConsolePresentation.Text("VinResult"), vin), Color.DarkGreen);
                        // ConsoleLine("SYSTEM", DoIpConsolePresentation.Text("VinNotice"), Color.DarkSlateGray);
                    }
                }
                catch (OperationCanceledException) when (stop.IsCancellationRequested)
                {
                    DrainTrace();
                    doIpObservedGateway = null;
                    if (!IsDisposed && !Disposing)
                        ConsoleLine("SYSTEM", DoIpConsolePresentation.Text("Canceled"), Color.DarkSlateGray);
                }
                catch
                {
                    DrainTrace();
                    doIpObservedGateway = null;
                    throw;
                }
            });
        }
        finally
        {
            traceTimer.Stop();
            DrainTrace();
            if (Volatile.Read(ref traceOverflow) != 0 && !IsDisposed && !Disposing)
                ConsoleLine("SYSTEM", DoIpConsolePresentation.Text("TraceIncomplete"), Color.DarkRed);
            doIpConsoleDiscoveryCancellation = null;
            if (!IsDisposed && !Disposing)
            {
                doIpConsoleHost.Enabled = doIpExpectedVin.Enabled = doIpTesterAddress.Enabled =
                    doIpEcuAddress.Enabled = doIpPayload.Enabled = doIpDetailDtc.Enabled = doIpDetailRecord.Enabled = doIpDtcMask.Enabled = doIpDid.Enabled = doIpMonitorInterval.Enabled = doIpMonitorCount.Enabled = doIpUseTls.Enabled = doIpClientCertificate.Enabled = true;
                doIpConsoleDiscover.Enabled = true;
                doIpConsoleStatus.Enabled = doIpConsolePower.Enabled =
                    doIpReadDtcs.Enabled = doIpCountDtcs.Enabled = doIpSnapshot.Enabled = doIpExtended.Enabled = doIpBackup.Enabled = doIpMonitorDid.Enabled = doIpReadDid.Enabled = doIpObdIdentify.Enabled = doIpObdDid.Enabled = doIpExchange.Enabled = doIpReadVin.Enabled = doIpObservedGateway is not null;
                doIpConsoleCancel.Enabled = false;
            }
        }
    }

    private async Task VerifyDoIpBackupFromConsoleAsync()
    {
        if (busy || doIpConsoleDiscoveryCancellation is not null) return;
        using var profile = new OpenFileDialog
        {
            Title = DoIpConsolePresentation.Text("BackupProfile"), Filter = "JSON (*.json)|*.json"
        };
        if (profile.ShowDialog(this) != DialogResult.OK) return;
        using var archive = new OpenFileDialog
        {
            Title = DoIpConsolePresentation.Text("BackupVerify"), Filter = "ZIP (*.zip)|*.zip"
        };
        if (archive.ShowDialog(this) != DialogResult.OK) return;
        using var stop = new CancellationTokenSource();
        doIpConsoleDiscoveryCancellation = stop;
        doIpVerifyBackup.Enabled = false;
        doIpConsoleCancel.Enabled = true;
        try
        {
            await RunBusyAsync(async () =>
            {
                try
                {
                    var result = await Task.Run(() =>
                    {
                        var scope = DoIpBackupScope.Load(profile.FileName);
                        return DoIpScopedBackup.VerifyZip(archive.FileName, scope, stop.Token);
                    }, stop.Token);
                    if (!IsDisposed && !Disposing)
                        ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("BackupVerified"),
                            result.Scope.Length, result.Sha256), Color.DarkGreen);
                }
                catch (OperationCanceledException) when (stop.IsCancellationRequested)
                {
                    if (!IsDisposed && !Disposing)
                        ConsoleLine("SYSTEM", DoIpConsolePresentation.Text("Canceled"), Color.DarkSlateGray);
                }
            });
        }
        finally
        {
            doIpConsoleDiscoveryCancellation = null;
            if (!IsDisposed && !Disposing)
            {
                doIpVerifyBackup.Enabled = true;
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
        if (doIpConsoleDiscoveryCancellation is not null)
        {
            ShowError(DoIpConsolePresentation.Text("Busy"));
            return;
        }
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

    private void ConsoleLine(string direction, string message, Color color,
        DateTimeOffset? observedAtUtc = null)
    {
        consoleHistory.SelectionStart = consoleHistory.TextLength;
        consoleHistory.SelectionLength = 0;
        consoleHistory.SelectionColor = color;
        consoleHistory.AppendText($"{(observedAtUtc?.ToLocalTime() ?? DateTimeOffset.Now):HH:mm:ss.fff} [{BridgeConsoleDirection.Normalize(direction)}] {message}{Environment.NewLine}");
        consoleHistory.SelectionColor = consoleHistory.ForeColor;
        consoleHistory.ScrollToCaret();
    }
}
