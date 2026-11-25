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
    private readonly TextBox doIpInventoryAddresses = new() { Dock = DockStyle.Fill, MaxLength = 79,
        CharacterCasing = CharacterCasing.Upper };
    private readonly Button doIpInventory = Button(DoIpConsolePresentation.Text("Inventory"));
    private readonly Button doIpObdInventory = Button(DoIpConsolePresentation.Text("ObdInventory"));
    private readonly CheckBox doIpUseTls = new()
        { Text = DoIpConsolePresentation.Text("UseTls"), Checked = true, AutoSize = true };
    private readonly TextBox doIpClientCertificate = new() { Dock = DockStyle.Fill };
    private readonly TextBox doIpP2 = new() { Dock = DockStyle.Fill, MaxLength = 5 };
    private readonly TextBox doIpP2Star = new() { Dock = DockStyle.Fill, MaxLength = 5 };
    private readonly TextBox doIpRoutingType = new() { Dock = DockStyle.Fill, MaxLength = 2, Text = "00", CharacterCasing = CharacterCasing.Upper };
    private readonly TextBox doIpRoutingOem = new() { Dock = DockStyle.Fill, MaxLength = 8, CharacterCasing = CharacterCasing.Upper };
    private enum DoIpReadOperation { Vin, Dtcs, DtcCount, DtcSnapshot, DtcExtended, Did, MonitorDid, Backup, Exchange, ObdIdentify, ObdDid, ObdMonitor, WwhMask, WwhPermanent, WwhReadiness, MultiDid, ObdMultiDid, ScalingDid, SupportedDtcs, PermanentDtcs, SnapshotIndex, MultiMonitor, ObdMultiMonitor, FirstFailed, FirstConfirmed, RecentFailed, RecentConfirmed, FaultCounters, SeverityCount, SeverityList, SeverityInfo, Inventory, ObdInventory, MemoryDtcs, MemorySnapshot, MemoryExtended, ExtendedRecordSupport, ExtendedRecordData, MirrorDtcs, MirrorCount, MirrorExtended, LegacyEmissionsDtcs, LegacyEmissionsCount, StoredRecord }
    private readonly Button doIpCountDtcs = Button(DoIpConsolePresentation.Text("CountDtcs"));
    private readonly Button doIpSupportedDtcs = Button(DoIpConsolePresentation.Text("SupportedDtcs"));
    private readonly Button doIpPermanentDtcs = Button(DoIpConsolePresentation.Text("PermanentDtcs"));
    private readonly TextBox doIpDtcMemory = new() { Dock = DockStyle.Fill, MaxLength = 2, CharacterCasing = CharacterCasing.Upper };
    private readonly Button doIpMemoryDtcs = Button(DoIpConsolePresentation.Text("MemoryDtcs"));
    private readonly Button doIpMemorySnapshot = Button(DoIpConsolePresentation.Text("MemorySnapshot"));
    private readonly Button doIpMemoryExtended = Button(DoIpConsolePresentation.Text("MemoryExtended"));
    private readonly TextBox doIpExtendedRecordSize = new() { Dock = DockStyle.Fill, MaxLength = 4 };
    private readonly Button doIpStoredRecord = Button(DoIpConsolePresentation.Text("StoredRecord"));
    private readonly Button doIpExtendedRecordSupport = Button(DoIpConsolePresentation.Text("ExtendedRecordSupport"));
    private readonly Button doIpExtendedRecordData = Button(DoIpConsolePresentation.Text("ExtendedRecordData"));
    private readonly Button doIpMirrorDtcs = Button(DoIpConsolePresentation.Text("LegacyMirrorDtcs"));
    private readonly Button doIpMirrorCount = Button(DoIpConsolePresentation.Text("LegacyMirrorCount"));
    private readonly Button doIpMirrorExtended = Button(DoIpConsolePresentation.Text("LegacyMirrorExtended"));
    private readonly Button doIpLegacyEmissionsDtcs = Button(DoIpConsolePresentation.Text("LegacyEmissionsDtcs"));
    private readonly Button doIpLegacyEmissionsCount = Button(DoIpConsolePresentation.Text("LegacyEmissionsCount"));
    private readonly Button doIpFaultCounters = Button(DoIpConsolePresentation.Text("FaultCounters"));
    private readonly TextBox doIpSeverityMask = new() { Dock = DockStyle.Fill, Text = "FF", MaxLength = 2 };
    private readonly Button doIpSeverityCount = Button(DoIpConsolePresentation.Text("SeverityCount"));
    private readonly Button doIpSeverityList = Button(DoIpConsolePresentation.Text("SeverityList"));
    private readonly Button doIpSeverityInfo = Button(DoIpConsolePresentation.Text("SeverityInfo"));
    private readonly Button doIpFirstFailed = Button(DoIpConsolePresentation.Text("FirstFailed"));
    private readonly Button doIpFirstConfirmed = Button(DoIpConsolePresentation.Text("FirstConfirmed"));
    private readonly Button doIpRecentFailed = Button(DoIpConsolePresentation.Text("RecentFailed"));
    private readonly Button doIpRecentConfirmed = Button(DoIpConsolePresentation.Text("RecentConfirmed"));
    private readonly Button doIpSnapshotIndex = Button(DoIpConsolePresentation.Text("SnapshotIndex"));
    private readonly Button doIpObdDtcs = Button(DoIpConsolePresentation.Text("ObdDtcs"));
    private readonly Button doIpObdCount = Button(DoIpConsolePresentation.Text("ObdCount"));
    private readonly Button doIpObdSnapshot = Button(DoIpConsolePresentation.Text("ObdSnapshot"));
    private readonly Button doIpObdExtended = Button(DoIpConsolePresentation.Text("ObdExtended"));
    private readonly Button doIpSnapshot = Button(DoIpConsolePresentation.Text("Snapshot"));
    private readonly Button doIpExtended = Button(DoIpConsolePresentation.Text("Extended"));
    private readonly TextBox doIpDetailDtc = new() { Dock = DockStyle.Fill, MaxLength = 6 };
    private readonly TextBox doIpDetailRecord = new() { Dock = DockStyle.Fill, MaxLength = 2, Text = "01" };
    private readonly TextBox doIpPayload = new() { Dock = DockStyle.Fill, MaxLength = 12288 };
    private readonly Button doIpExchange = Button(DoIpConsolePresentation.Text("Exchange"));
    private readonly Button doIpBackup = Button(DoIpConsolePresentation.Text("Backup"));
    private readonly Button doIpVerifyBackup = Button(DoIpConsolePresentation.Text("BackupVerify"));
    private readonly Button doIpMonitorDid = Button(DoIpConsolePresentation.Text("MonitorDid"));
    private readonly Button doIpObdMonitor = Button(DoIpConsolePresentation.Text("ObdMonitor"));
    private readonly Button doIpMultiMonitor = Button(DoIpConsolePresentation.Text("MultiMonitor"));
    private readonly Button doIpObdMultiMonitor = Button(DoIpConsolePresentation.Text("ObdMultiMonitor"));
    private readonly NumericUpDown doIpMonitorInterval = new()
        { Minimum = 250, Maximum = 60000, Increment = 250, Value = 1000, Dock = DockStyle.Fill };
    private readonly NumericUpDown doIpMonitorCount = new()
        { Minimum = 1, Maximum = 3600, Value = 60, Dock = DockStyle.Fill };
    private readonly Button doIpReadDid = Button(DoIpConsolePresentation.Text("ReadDid"));
    private readonly Button doIpScalingDid = Button(DoIpConsolePresentation.Text("ScalingDid"));
    private readonly Button doIpObdIdentify = Button(DoIpConsolePresentation.Text("ObdIdentify"));
    private readonly Button doIpObdDid = Button(DoIpConsolePresentation.Text("ObdRead"));
    private readonly Button doIpMultiRead = Button(DoIpConsolePresentation.Text("MultiRead"));
    private readonly Button doIpObdMultiRead = Button(DoIpConsolePresentation.Text("ObdMultiRead"));
    private readonly TextBox doIpMultiLayout = new() { Dock = DockStyle.Fill, MaxLength = 256 };
    private readonly Button doIpWwhMask = Button(DoIpConsolePresentation.Text("WwhMask"));
    private readonly Button doIpWwhPermanent = Button(DoIpConsolePresentation.Text("WwhPermanent"));
    private readonly Button doIpWwhReadiness = Button(DoIpConsolePresentation.Text("WwhReadiness"));
    private readonly TextBox doIpWwhSeverity = new() { Dock = DockStyle.Fill, Text = "FF", MaxLength = 2 };
    private readonly TextBox doIpWwhGroup = new() { Dock = DockStyle.Fill, MaxLength = 2 };
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
        doIpConsoleStatus.Enabled = doIpConsolePower.Enabled = doIpObdInventory.Enabled = doIpInventory.Enabled = doIpSeverityCount.Enabled = doIpSeverityList.Enabled = doIpSeverityInfo.Enabled = doIpFaultCounters.Enabled = doIpFirstFailed.Enabled = doIpFirstConfirmed.Enabled = doIpRecentFailed.Enabled = doIpRecentConfirmed.Enabled = doIpSupportedDtcs.Enabled = doIpMirrorDtcs.Enabled = doIpMirrorCount.Enabled = doIpMirrorExtended.Enabled = doIpLegacyEmissionsDtcs.Enabled = doIpLegacyEmissionsCount.Enabled = doIpStoredRecord.Enabled = doIpExtendedRecordSupport.Enabled = doIpExtendedRecordData.Enabled = doIpMemoryDtcs.Enabled = doIpMemorySnapshot.Enabled = doIpMemoryExtended.Enabled = doIpPermanentDtcs.Enabled = doIpSnapshotIndex.Enabled = doIpObdDtcs.Enabled = doIpObdCount.Enabled = doIpObdSnapshot.Enabled = doIpObdExtended.Enabled = doIpReadDtcs.Enabled = doIpCountDtcs.Enabled = doIpSnapshot.Enabled = doIpExtended.Enabled = doIpBackup.Enabled = doIpMultiMonitor.Enabled = doIpObdMultiMonitor.Enabled = doIpMonitorDid.Enabled = doIpObdMonitor.Enabled = doIpScalingDid.Enabled = doIpReadDid.Enabled = doIpObdIdentify.Enabled = doIpObdDid.Enabled = doIpWwhMask.Enabled = doIpWwhPermanent.Enabled = doIpWwhReadiness.Enabled = doIpMultiRead.Enabled = doIpObdMultiRead.Enabled = doIpExchange.Enabled = doIpReadVin.Enabled = false;
        doIpConsoleStatus.Click += async (_, _) => await RunDoIpConsoleOperationAsync(false);
        doIpConsolePower.Click += async (_, _) => await RunDoIpConsoleOperationAsync(true);
        doIpConsoleHost.TextChanged += (_, _) =>
        {
            if (doIpPublishingHost) return;
            doIpObservedGateway = null;
            doIpConsoleStatus.Enabled = doIpConsolePower.Enabled = doIpObdInventory.Enabled = doIpInventory.Enabled = doIpSeverityCount.Enabled = doIpSeverityList.Enabled = doIpSeverityInfo.Enabled = doIpFaultCounters.Enabled = doIpFirstFailed.Enabled = doIpFirstConfirmed.Enabled = doIpRecentFailed.Enabled = doIpRecentConfirmed.Enabled = doIpSupportedDtcs.Enabled = doIpMirrorDtcs.Enabled = doIpMirrorCount.Enabled = doIpMirrorExtended.Enabled = doIpLegacyEmissionsDtcs.Enabled = doIpLegacyEmissionsCount.Enabled = doIpStoredRecord.Enabled = doIpExtendedRecordSupport.Enabled = doIpExtendedRecordData.Enabled = doIpMemoryDtcs.Enabled = doIpMemorySnapshot.Enabled = doIpMemoryExtended.Enabled = doIpPermanentDtcs.Enabled = doIpSnapshotIndex.Enabled = doIpObdDtcs.Enabled = doIpObdCount.Enabled = doIpObdSnapshot.Enabled = doIpObdExtended.Enabled = doIpReadDtcs.Enabled = doIpCountDtcs.Enabled = doIpSnapshot.Enabled = doIpExtended.Enabled = doIpBackup.Enabled = doIpMultiMonitor.Enabled = doIpObdMultiMonitor.Enabled = doIpMonitorDid.Enabled = doIpObdMonitor.Enabled = doIpScalingDid.Enabled = doIpReadDid.Enabled = doIpObdIdentify.Enabled = doIpObdDid.Enabled = doIpWwhMask.Enabled = doIpWwhPermanent.Enabled = doIpWwhReadiness.Enabled = doIpMultiRead.Enabled = doIpObdMultiRead.Enabled = doIpExchange.Enabled = doIpReadVin.Enabled = false;
            doIpConsoleDiscoveryCancellation?.Cancel();
        };
        WideRow(fields, doIpOperations, 48);
        Row(fields, DoIpConsolePresentation.Text("TesterAddress"), doIpTesterAddress);
        Row(fields, DoIpConsolePresentation.Text("EcuAddress"), doIpEcuAddress, doIpReadVin);
        Row(fields, DoIpConsolePresentation.Text("InventoryAddresses"), doIpInventoryAddresses, doIpInventory);
        doIpInventory.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.Inventory);
        WideRow(fields, doIpObdInventory, 38);
        doIpObdInventory.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.ObdInventory);
        WideRow(fields, doIpUseTls, 38);
        Row(fields, DoIpConsolePresentation.Text("CertThumbprint"), doIpClientCertificate);
        Row(fields, DoIpConsolePresentation.Text("SessionP2"), doIpP2);
        Row(fields, DoIpConsolePresentation.Text("SessionP2Star"), doIpP2Star);
        Row(fields, DoIpConsolePresentation.Text("RoutingType"), doIpRoutingType);
        Row(fields, DoIpConsolePresentation.Text("RoutingOem"), doIpRoutingOem);
        doIpObdInventory.Enabled = doIpInventory.Enabled = doIpSeverityCount.Enabled = doIpSeverityList.Enabled = doIpSeverityInfo.Enabled = doIpFaultCounters.Enabled = doIpFirstFailed.Enabled = doIpFirstConfirmed.Enabled = doIpRecentFailed.Enabled = doIpRecentConfirmed.Enabled = doIpSupportedDtcs.Enabled = doIpMirrorDtcs.Enabled = doIpMirrorCount.Enabled = doIpMirrorExtended.Enabled = doIpLegacyEmissionsDtcs.Enabled = doIpLegacyEmissionsCount.Enabled = doIpStoredRecord.Enabled = doIpExtendedRecordSupport.Enabled = doIpExtendedRecordData.Enabled = doIpMemoryDtcs.Enabled = doIpMemorySnapshot.Enabled = doIpMemoryExtended.Enabled = doIpPermanentDtcs.Enabled = doIpSnapshotIndex.Enabled = doIpObdDtcs.Enabled = doIpObdCount.Enabled = doIpObdSnapshot.Enabled = doIpObdExtended.Enabled = doIpReadDtcs.Enabled = doIpCountDtcs.Enabled = doIpSnapshot.Enabled = doIpExtended.Enabled = doIpBackup.Enabled = doIpMultiMonitor.Enabled = doIpObdMultiMonitor.Enabled = doIpMonitorDid.Enabled = doIpObdMonitor.Enabled = doIpScalingDid.Enabled = doIpReadDid.Enabled = doIpObdIdentify.Enabled = doIpObdDid.Enabled = doIpWwhMask.Enabled = doIpWwhPermanent.Enabled = doIpWwhReadiness.Enabled = doIpMultiRead.Enabled = doIpObdMultiRead.Enabled = doIpExchange.Enabled = doIpReadVin.Enabled = false;
        doIpReadVin.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.Vin);
        Row(fields, DoIpConsolePresentation.Text("DtcMask"), doIpDtcMask, doIpReadDtcs);
        doIpReadDtcs.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.Dtcs);
        WideRow(fields, doIpCountDtcs, 38);
        Row(fields, DoIpConsolePresentation.Text("SeverityMask"), doIpSeverityMask, doIpSeverityList);
        WideRow(fields, doIpSeverityCount, 38);
        WideRow(fields, doIpSeverityInfo, 38);
        doIpSeverityCount.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.SeverityCount);
        doIpSeverityList.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.SeverityList);
        doIpSeverityInfo.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.SeverityInfo);
        Row(fields, DoIpConsolePresentation.Text("DetailDtc"), doIpDetailDtc, doIpSnapshot);
        Row(fields, DoIpConsolePresentation.Text("DetailRecord"), doIpDetailRecord, doIpExtended);
        doIpSnapshot.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.DtcSnapshot);
        doIpExtended.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.DtcExtended);
        doIpCountDtcs.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.DtcCount);
        WideRow(fields, doIpSupportedDtcs, 38);
        foreach (var (button, op) in new[] {
            (doIpMirrorDtcs, DoIpReadOperation.MirrorDtcs), (doIpMirrorCount, DoIpReadOperation.MirrorCount),
            (doIpMirrorExtended, DoIpReadOperation.MirrorExtended), (doIpLegacyEmissionsDtcs, DoIpReadOperation.LegacyEmissionsDtcs),
            (doIpLegacyEmissionsCount, DoIpReadOperation.LegacyEmissionsCount) })
        {
            WideRow(fields, button, 38);
            button.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(op);
        }
        WideRow(fields, doIpStoredRecord, 38);
        doIpStoredRecord.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.StoredRecord);
        WideRow(fields, doIpExtendedRecordSupport, 38);
        Row(fields, DoIpConsolePresentation.Text("ExtendedRecordSize"), doIpExtendedRecordSize, doIpExtendedRecordData);
        doIpExtendedRecordSupport.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.ExtendedRecordSupport);
        doIpExtendedRecordData.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.ExtendedRecordData);
        Row(fields, DoIpConsolePresentation.Text("MemorySelection"), doIpDtcMemory, doIpMemoryDtcs);
        WideRow(fields, doIpMemorySnapshot, 38);
        WideRow(fields, doIpMemoryExtended, 38);
        doIpMemoryDtcs.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.MemoryDtcs);
        doIpMemorySnapshot.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.MemorySnapshot);
        doIpMemoryExtended.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.MemoryExtended);
        WideRow(fields, doIpPermanentDtcs, 38);
        doIpPermanentDtcs.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.PermanentDtcs);
        WideRow(fields, doIpFaultCounters, 38);
        doIpFaultCounters.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.FaultCounters);
        WideRow(fields, doIpFirstFailed, 38);
        doIpFirstFailed.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.FirstFailed);
        WideRow(fields, doIpFirstConfirmed, 38);
        doIpFirstConfirmed.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.FirstConfirmed);
        WideRow(fields, doIpRecentFailed, 38);
        doIpRecentFailed.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.RecentFailed);
        WideRow(fields, doIpRecentConfirmed, 38);
        doIpRecentConfirmed.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.RecentConfirmed);
        WideRow(fields, doIpSnapshotIndex, 38);
        doIpSupportedDtcs.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.SupportedDtcs);
        doIpSnapshotIndex.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.SnapshotIndex);
        WideRow(fields, doIpObdDtcs, 38);
        WideRow(fields, doIpObdCount, 38);
        WideRow(fields, doIpObdSnapshot, 38);
        WideRow(fields, doIpObdExtended, 38);
        doIpObdDtcs.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.Dtcs, true);
        doIpObdCount.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.DtcCount, true);
        doIpObdSnapshot.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.DtcSnapshot, true);
        doIpObdExtended.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.DtcExtended, true);
        Row(fields, DoIpConsolePresentation.Text("Payload"), doIpPayload, doIpExchange);
        doIpExchange.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.Exchange);
        Row(fields, DoIpConsolePresentation.Text("DidLabel"), doIpDid, doIpReadDid);
        doIpReadDid.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.Did);
        WideRow(fields, doIpScalingDid, 38);
        doIpScalingDid.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.ScalingDid);
        WideRow(fields, doIpObdIdentify, 38);
        WideRow(fields, doIpObdDid, 38);
        doIpObdIdentify.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.ObdIdentify);
        doIpObdDid.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.ObdDid);
        Row(fields, DoIpConsolePresentation.Text("MultiLayout"), doIpMultiLayout, doIpMultiRead);
        WideRow(fields, doIpObdMultiRead, 38);
        doIpMultiRead.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.MultiDid);
        doIpObdMultiRead.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.ObdMultiDid);
        Row(fields, DoIpConsolePresentation.Text("WwhSeverity"), doIpWwhSeverity, doIpWwhMask);
        WideRow(fields, doIpWwhPermanent, 38);
        Row(fields, DoIpConsolePresentation.Text("WwhGroup"), doIpWwhGroup, doIpWwhReadiness);
        doIpWwhMask.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.WwhMask);
        doIpWwhPermanent.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.WwhPermanent);
        doIpWwhReadiness.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.WwhReadiness);
        Row(fields, DoIpConsolePresentation.Text("MonitorInterval"), doIpMonitorInterval, doIpMonitorDid);
        Row(fields, DoIpConsolePresentation.Text("MonitorCount"), doIpMonitorCount);
        doIpMonitorDid.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.MonitorDid);
        WideRow(fields, doIpObdMonitor, 38);
        doIpObdMonitor.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.ObdMonitor);
        WideRow(fields, doIpMultiMonitor, 38);
        WideRow(fields, doIpObdMultiMonitor, 38);
        doIpMultiMonitor.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.MultiMonitor);
        doIpObdMultiMonitor.Click += async (_, _) => await ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation.ObdMultiMonitor);
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
            doIpConsoleStatus.Enabled = doIpConsolePower.Enabled = doIpObdInventory.Enabled = doIpInventory.Enabled = doIpSeverityCount.Enabled = doIpSeverityList.Enabled = doIpSeverityInfo.Enabled = doIpFaultCounters.Enabled = doIpFirstFailed.Enabled = doIpFirstConfirmed.Enabled = doIpRecentFailed.Enabled = doIpRecentConfirmed.Enabled = doIpSupportedDtcs.Enabled = doIpMirrorDtcs.Enabled = doIpMirrorCount.Enabled = doIpMirrorExtended.Enabled = doIpLegacyEmissionsDtcs.Enabled = doIpLegacyEmissionsCount.Enabled = doIpStoredRecord.Enabled = doIpExtendedRecordSupport.Enabled = doIpExtendedRecordData.Enabled = doIpMemoryDtcs.Enabled = doIpMemorySnapshot.Enabled = doIpMemoryExtended.Enabled = doIpPermanentDtcs.Enabled = doIpSnapshotIndex.Enabled = doIpObdDtcs.Enabled = doIpObdCount.Enabled = doIpObdSnapshot.Enabled = doIpObdExtended.Enabled = doIpReadDtcs.Enabled = doIpCountDtcs.Enabled = doIpSnapshot.Enabled = doIpExtended.Enabled = doIpBackup.Enabled = doIpMultiMonitor.Enabled = doIpObdMultiMonitor.Enabled = doIpMonitorDid.Enabled = doIpObdMonitor.Enabled = doIpScalingDid.Enabled = doIpReadDid.Enabled = doIpObdIdentify.Enabled = doIpObdDid.Enabled = doIpWwhMask.Enabled = doIpWwhPermanent.Enabled = doIpWwhReadiness.Enabled = doIpMultiRead.Enabled = doIpObdMultiRead.Enabled = doIpExchange.Enabled = doIpReadVin.Enabled = false;
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
            !DoIpVehicleDiscovery.IsExplicitUnicast(address)))
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
        doIpConsoleDiscover.Enabled = doIpConsoleStatus.Enabled = doIpConsolePower.Enabled = doIpObdInventory.Enabled = doIpInventory.Enabled = doIpSeverityCount.Enabled = doIpSeverityList.Enabled = doIpSeverityInfo.Enabled = doIpFaultCounters.Enabled = doIpFirstFailed.Enabled = doIpFirstConfirmed.Enabled = doIpRecentFailed.Enabled = doIpRecentConfirmed.Enabled = doIpSupportedDtcs.Enabled = doIpMirrorDtcs.Enabled = doIpMirrorCount.Enabled = doIpMirrorExtended.Enabled = doIpLegacyEmissionsDtcs.Enabled = doIpLegacyEmissionsCount.Enabled = doIpStoredRecord.Enabled = doIpExtendedRecordSupport.Enabled = doIpExtendedRecordData.Enabled = doIpMemoryDtcs.Enabled = doIpMemorySnapshot.Enabled = doIpMemoryExtended.Enabled = doIpPermanentDtcs.Enabled = doIpSnapshotIndex.Enabled = doIpObdDtcs.Enabled = doIpObdCount.Enabled = doIpObdSnapshot.Enabled = doIpObdExtended.Enabled = doIpReadDtcs.Enabled = doIpCountDtcs.Enabled = doIpSnapshot.Enabled = doIpExtended.Enabled = doIpBackup.Enabled = doIpMultiMonitor.Enabled = doIpObdMultiMonitor.Enabled = doIpMonitorDid.Enabled = doIpObdMonitor.Enabled = doIpScalingDid.Enabled = doIpReadDid.Enabled = doIpObdIdentify.Enabled = doIpObdDid.Enabled = doIpWwhMask.Enabled = doIpWwhPermanent.Enabled = doIpWwhReadiness.Enabled = doIpMultiRead.Enabled = doIpObdMultiRead.Enabled = doIpExchange.Enabled = doIpReadVin.Enabled = false;
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
                        await DoIpVehicleDiscovery.RequireCurrentIdentityAsync(observed, timeout,
                            stop.Token, JournalDoIpUdp);
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
                    if (!powerMode.HasValue) doIpObservedGateway = null;
                    if (!IsDisposed && !Disposing)
                        ConsoleLine("SYSTEM", DoIpConsolePresentation.Text("Canceled"),
                            Color.DarkSlateGray);
                }
                catch
                {
                    if (!powerMode.HasValue) doIpObservedGateway = null;
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
                doIpConsoleStatus.Enabled = doIpConsolePower.Enabled = doIpObdInventory.Enabled = doIpInventory.Enabled = doIpSeverityCount.Enabled = doIpSeverityList.Enabled = doIpSeverityInfo.Enabled = doIpFaultCounters.Enabled = doIpFirstFailed.Enabled = doIpFirstConfirmed.Enabled = doIpRecentFailed.Enabled = doIpRecentConfirmed.Enabled = doIpSupportedDtcs.Enabled = doIpMirrorDtcs.Enabled = doIpMirrorCount.Enabled = doIpMirrorExtended.Enabled = doIpLegacyEmissionsDtcs.Enabled = doIpLegacyEmissionsCount.Enabled = doIpStoredRecord.Enabled = doIpExtendedRecordSupport.Enabled = doIpExtendedRecordData.Enabled = doIpMemoryDtcs.Enabled = doIpMemorySnapshot.Enabled = doIpMemoryExtended.Enabled = doIpPermanentDtcs.Enabled = doIpSnapshotIndex.Enabled = doIpObdDtcs.Enabled = doIpObdCount.Enabled = doIpObdSnapshot.Enabled = doIpObdExtended.Enabled = doIpReadDtcs.Enabled = doIpCountDtcs.Enabled = doIpSnapshot.Enabled = doIpExtended.Enabled = doIpBackup.Enabled = doIpMultiMonitor.Enabled = doIpObdMultiMonitor.Enabled = doIpMonitorDid.Enabled = doIpObdMonitor.Enabled = doIpScalingDid.Enabled = doIpReadDid.Enabled = doIpObdIdentify.Enabled = doIpObdDid.Enabled = doIpWwhMask.Enabled = doIpWwhPermanent.Enabled = doIpWwhReadiness.Enabled = doIpMultiRead.Enabled = doIpObdMultiRead.Enabled = doIpExchange.Enabled = doIpReadVin.Enabled = doIpObservedGateway is not null;
                doIpConsoleCancel.Enabled = false;
            }
        }
    }

    private async Task ReadDoIpDiagnosticFromConsoleAsync(DoIpReadOperation operation, bool obdDtc = false)
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
        ushort[]? inventoryTargets = null;
        try
        {
            tester = DoIpVinReader.ParseLogicalAddress(doIpTesterAddress.Text.Trim());
            if (operation is DoIpReadOperation.Inventory or DoIpReadOperation.ObdInventory)
            {
                inventoryTargets = DoIpVinReader.ParseInventoryAddresses(doIpInventoryAddresses.Text.Trim());
                if (inventoryTargets.Contains(tester)) throw new ArgumentException();
                ecu = inventoryTargets[0]; // Only for common context; inventory uses each explicit address.
            }
            else ecu = DoIpVinReader.ParseLogicalAddress(doIpEcuAddress.Text.Trim());
            if (tester == ecu || tester == observed.LogicalAddress) throw new ArgumentException();
        }
        catch (ArgumentException)
        {
            ShowError(DoIpConsolePresentation.Text("InvalidLogical"));
            return;
        }
        byte legacySub = operation switch
        {
            DoIpReadOperation.MirrorDtcs => 0x0F, DoIpReadOperation.MirrorExtended => 0x10,
            DoIpReadOperation.MirrorCount => 0x11, DoIpReadOperation.LegacyEmissionsCount => 0x12,
            DoIpReadOperation.LegacyEmissionsDtcs => 0x13, _ => 0
        };
        byte storedRecord = 0;
        if (operation == DoIpReadOperation.StoredRecord)
        {
            try
            {
                storedRecord = DoIpConsolePresentation.ParseDtcMask(doIpDetailRecord.Text.Trim());
                _ = ObdOnUdsServices.ReadStoredRecord(storedRecord);
            }
            catch (ArgumentException)
            {
                ShowError(DoIpConsolePresentation.Text("InvalidStoredRecord"));
                return;
            }
        }
        bool extendedRecordRead = operation is DoIpReadOperation.ExtendedRecordSupport or DoIpReadOperation.ExtendedRecordData;
        byte extendedRecord = 0;
        int? extendedRecordSize = null;
        if (extendedRecordRead)
        {
            try
            {
                extendedRecord = DoIpConsolePresentation.ParseDtcMask(doIpDetailRecord.Text.Trim());
                if (operation == DoIpReadOperation.ExtendedRecordData)
                {
                    if (!int.TryParse(doIpExtendedRecordSize.Text.Trim(), System.Globalization.NumberStyles.None,
                        System.Globalization.CultureInfo.InvariantCulture, out var size)) throw new ArgumentException();
                    extendedRecordSize = size;
                }
                _ = ObdOnUdsServices.ReadExtendedRecord(extendedRecord, extendedRecordSize);
            }
            catch (ArgumentException) { ShowError(DoIpConsolePresentation.Text("InvalidExtendedRecord")); return; }
        }
        byte memory = 0;
        bool memoryRead = operation is DoIpReadOperation.MemoryDtcs or DoIpReadOperation.MemorySnapshot or DoIpReadOperation.MemoryExtended;
        if (memoryRead)
        {
            try { memory = DoIpConsolePresentation.ParseDtcMask(doIpDtcMemory.Text.Trim()); }
            catch (ArgumentException) { ShowError(DoIpConsolePresentation.Text("InvalidMemorySelection")); return; }
        }
        byte dtcMask = 0;
        bool severityFilter = operation is DoIpReadOperation.SeverityCount or DoIpReadOperation.SeverityList;
        if ((legacySub != 0 && legacySub != 0x10) || severityFilter || operation is DoIpReadOperation.Dtcs or DoIpReadOperation.DtcCount or DoIpReadOperation.WwhMask or DoIpReadOperation.MemoryDtcs)
        {
            try { dtcMask = DoIpConsolePresentation.ParseDtcMask(doIpDtcMask.Text.Trim()); }
            catch (ArgumentException)
            {
                ShowError(DoIpConsolePresentation.Text("InvalidDtcMask"));
                return;
            }
        }
        byte severityMask = 255;
        if (severityFilter)
        {
            try { severityMask = DoIpConsolePresentation.ParseDtcMask(doIpSeverityMask.Text.Trim()); }
            catch (ArgumentException) { ShowError(DoIpConsolePresentation.Text("InvalidDtcMask")); return; }
        }
        byte wwhSeverity = 255, wwhGroup = 0;
        bool wwh = operation is DoIpReadOperation.WwhMask or DoIpReadOperation.WwhPermanent or DoIpReadOperation.WwhReadiness;
        try
        {
            if (operation == DoIpReadOperation.WwhMask)
                wwhSeverity = DoIpConsolePresentation.ParseDtcMask(doIpWwhSeverity.Text.Trim());
            if (operation == DoIpReadOperation.WwhReadiness)
                wwhGroup = DoIpConsolePresentation.ParseDtcMask(doIpWwhGroup.Text.Trim());
        }
        catch (ArgumentException)
        {
            ShowError(DoIpConsolePresentation.Text("InvalidDtcMask"));
            return;
        }
        ushort did = 0;
        if (operation is DoIpReadOperation.Did or DoIpReadOperation.ScalingDid or DoIpReadOperation.MonitorDid or DoIpReadOperation.ObdDid or DoIpReadOperation.ObdMonitor)
        {
            try { did = DoIpVinReader.ParseLogicalAddress(doIpDid.Text.Trim()); }
            catch (ArgumentException)
            {
                ShowError(DoIpConsolePresentation.Text("InvalidDid"));
                return;
            }
        }
        if ((operation is DoIpReadOperation.ObdDid or DoIpReadOperation.ObdMonitor) &&
            ObdOnUdsServices.ClassifyDid(did) == ObdOnUdsServices.DataGroup.NotLegislated)
        {
            ShowError(DoIpConsolePresentation.Text("ObdInvalidDid"));
            return;
        }
        IReadOnlyList<UdsMultiDidCli.Entry>? multiLayout = null;
        if (operation is DoIpReadOperation.MultiDid or DoIpReadOperation.ObdMultiDid or
            DoIpReadOperation.MultiMonitor or DoIpReadOperation.ObdMultiMonitor)
        {
            try
            {
                multiLayout = UdsMultiDidCli.Parse(doIpMultiLayout.Text.Trim());
                if (operation is DoIpReadOperation.MultiMonitor or DoIpReadOperation.ObdMultiMonitor)
                    multiLayout = UdsMultiDidCli.ValidateMonitor(multiLayout);
                if ((operation is DoIpReadOperation.ObdMultiDid or DoIpReadOperation.ObdMultiMonitor) && multiLayout.Any(e =>
                    ObdOnUdsServices.ClassifyDid(e.Did) == ObdOnUdsServices.DataGroup.NotLegislated))
                    throw new ArgumentException("Expected legislated DIDs.");
            }
            catch (ArgumentException)
            {
                ShowError(DoIpConsolePresentation.Text("InvalidMultiLayout"));
                return;
            }
        }
        var expectedObdVin = doIpExpectedVin.Text.Trim();
        if ((operation == DoIpReadOperation.StoredRecord || legacySub != 0 || extendedRecordRead || memoryRead || inventoryTargets is not null || multiLayout is not null || wwh || obdDtc || operation is DoIpReadOperation.ObdIdentify or DoIpReadOperation.ObdDid or DoIpReadOperation.ObdMonitor) &&
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
        if (operation is DoIpReadOperation.DtcSnapshot or DoIpReadOperation.DtcExtended or DoIpReadOperation.MemorySnapshot or DoIpReadOperation.MemoryExtended or DoIpReadOperation.MirrorExtended)
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
        if ((operation is DoIpReadOperation.MemoryExtended or DoIpReadOperation.MirrorExtended) && detailRecord is >= 0xF0 and <= 0xFD)
        {
            ShowError(DoIpConsolePresentation.Text("InvalidDetail"));
            return;
        }
        if (operation == DoIpReadOperation.SeverityInfo)
        {
            try { (detailDtc, _) = DoIpConsolePresentation.ParseDetail(doIpDetailDtc.Text.Trim(), "01"); }
            catch (ArgumentException) { ShowError(DoIpConsolePresentation.Text("InvalidDetail")); return; }
        }
        var timeout = TimeSpan.FromMilliseconds((int)consoleTimeout.Value);
        DoIpDiagnosticTransport.SessionOptions CurrentOptions() => DoIpDiagnosticTransport.SessionOptions.Parse(
            (int)consoleTimeout.Value, doIpP2.Text.Trim(), doIpP2Star.Text.Trim(),
            doIpRoutingType.Text.Trim(), doIpRoutingOem.Text.Trim());
        DoIpDiagnosticTransport.SessionOptions options;
        try { options = CurrentOptions(); }
        catch (ArgumentException)
        {
            ShowError(DoIpConsolePresentation.Text("InvalidSessionOptions"));
            return;
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
                string.Join("; ", commands.Select(x => Convert.ToHexString(x)))) + "\n" +
                string.Format(DoIpConsolePresentation.Text("SessionSummary"), timeout.TotalMilliseconds,
                    options.P2Ms, options.P2StarMs, options.RoutingType,
                    options.OemHex.Length == 0 ? "—" : options.OemHex);
            bool SameOptions()
            {
                try { return CurrentOptions() == options && consoleTimeout.Value == (decimal)timeout.TotalMilliseconds; }
                catch (ArgumentException) { return false; }
            }
            bool SameContext() => SameOptions() && !busy && doIpConsoleDiscoveryCancellation is null &&
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
        using var stop = new CancellationTokenSource();
        doIpConsoleDiscoveryCancellation = stop;
        doIpConsoleDiscover.Enabled = doIpConsoleStatus.Enabled =
            doIpConsolePower.Enabled = doIpObdInventory.Enabled = doIpInventory.Enabled = doIpSeverityCount.Enabled = doIpSeverityList.Enabled = doIpSeverityInfo.Enabled = doIpFaultCounters.Enabled = doIpFirstFailed.Enabled = doIpFirstConfirmed.Enabled = doIpRecentFailed.Enabled = doIpRecentConfirmed.Enabled = doIpSupportedDtcs.Enabled = doIpMirrorDtcs.Enabled = doIpMirrorCount.Enabled = doIpMirrorExtended.Enabled = doIpLegacyEmissionsDtcs.Enabled = doIpLegacyEmissionsCount.Enabled = doIpStoredRecord.Enabled = doIpExtendedRecordSupport.Enabled = doIpExtendedRecordData.Enabled = doIpMemoryDtcs.Enabled = doIpMemorySnapshot.Enabled = doIpMemoryExtended.Enabled = doIpPermanentDtcs.Enabled = doIpSnapshotIndex.Enabled = doIpObdDtcs.Enabled = doIpObdCount.Enabled = doIpObdSnapshot.Enabled = doIpObdExtended.Enabled = doIpReadDtcs.Enabled = doIpCountDtcs.Enabled = doIpSnapshot.Enabled = doIpExtended.Enabled = doIpBackup.Enabled = doIpMultiMonitor.Enabled = doIpObdMultiMonitor.Enabled = doIpMonitorDid.Enabled = doIpObdMonitor.Enabled = doIpScalingDid.Enabled = doIpReadDid.Enabled = doIpObdIdentify.Enabled = doIpObdDid.Enabled = doIpWwhMask.Enabled = doIpWwhPermanent.Enabled = doIpWwhReadiness.Enabled = doIpMultiRead.Enabled = doIpObdMultiRead.Enabled = doIpExchange.Enabled = doIpReadVin.Enabled = false;
        doIpConsoleHost.Enabled = doIpExpectedVin.Enabled = doIpTesterAddress.Enabled =
            doIpInventoryAddresses.Enabled = doIpEcuAddress.Enabled = doIpPayload.Enabled = doIpDetailDtc.Enabled = doIpExtendedRecordSize.Enabled = doIpDtcMemory.Enabled = doIpDetailRecord.Enabled = doIpDtcMask.Enabled = doIpWwhSeverity.Enabled = doIpWwhGroup.Enabled = doIpMultiLayout.Enabled = doIpDid.Enabled = doIpMonitorInterval.Enabled = doIpMonitorCount.Enabled = doIpUseTls.Enabled = doIpClientCertificate.Enabled =
            doIpP2.Enabled = doIpP2Star.Enabled = doIpRoutingType.Enabled = doIpRoutingOem.Enabled = false;
        doIpConsoleCancel.Enabled = true;
        // The receive pump runs on a worker thread. Queue physical PDUs
        // without touching WinForms controls, and drain only on the UI thread.
        var pendingTrace = new System.Collections.Concurrent.ConcurrentQueue<(ushort Ecu, DoIpDiagnosticTransport.PduTrace Entry)>();
        long pendingBytes = 0;
        int traceOverflow = 0;
        void Trace(DoIpDiagnosticTransport.PduTrace entry) => TraceForEcu(ecu, entry);
        void TraceForEcu(ushort targetEcu, DoIpDiagnosticTransport.PduTrace entry)
        {
            if (Interlocked.Add(ref pendingBytes, entry.Bytes.Length) > 16L * 1024 * 1024)
            {
                Interlocked.Add(ref pendingBytes, -entry.Bytes.Length);
                Interlocked.Exchange(ref traceOverflow, 1);
                throw new IOException("DoIP console trace backlog exceeded; capture incomplete.");
            }
            pendingTrace.Enqueue((targetEcu, entry));
        }
        void DrainTrace()
        {
            while (pendingTrace.TryDequeue(out var item))
            {
                var entry = item.Entry;
                Interlocked.Add(ref pendingBytes, -entry.Bytes.Length);
                if (!IsDisposed && !Disposing)
                    ConsoleLine(entry.Direction, "DoIP " + (useTls ? "TLS" : "TCP") + " " +
                        address + $" ECU 0x{item.Ecu:X4} PDU " + Convert.ToHexString(entry.Bytes),
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
                    if (inventoryTargets is not null)
                        ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("InventoryStarted"),
                            string.Join(", ", inventoryTargets.Select(x => $"0x{x:X4}"))), Color.DarkSlateGray);
                    else ConsoleLine("SYSTEM", string.Format(
                        DoIpConsolePresentation.Text(operation switch { DoIpReadOperation.Exchange => "ExchangeStarted", DoIpReadOperation.Backup => "BackupStarted", DoIpReadOperation.Dtcs or DoIpReadOperation.DtcCount or DoIpReadOperation.DtcSnapshot or DoIpReadOperation.DtcExtended => "DtcStarted", DoIpReadOperation.Did or DoIpReadOperation.MonitorDid or DoIpReadOperation.ObdMonitor => "DidStarted", _ => "VinStarted" }),
                        useTls ? "TLS" : "TCP", address, ecu, tester, observed.ProtocolVersion),
                        Color.DarkSlateGray);
                    await DoIpVehicleDiscovery.RequireCurrentIdentityAsync(observed, timeout,
                        stop.Token, JournalDoIpUdp);
                    if (!ReferenceEquals(observed, doIpObservedGateway))
                        throw new InvalidOperationException("DoIP target changed.");
                    var certificate = thumbprint.Length == 0 ? null : thumbprint;
                    if (inventoryTargets is not null)
                    {
                        var rows = await DoIpVinReader.InventoryObservedAsync(observed, expectedObdVin, tester,
                            inventoryTargets, useTls, timeout, stop.Token, certificate, options, JournalDoIpUdp, TraceForEcu,
                            operation == DoIpReadOperation.ObdInventory);
                        RequireCompleteTrace();
                        stop.Token.ThrowIfCancellationRequested();
                        if (IsDisposed || Disposing) return;
                        foreach (var row in rows)
                        {
                            ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text(row.Vin is null ?
                                "InventoryUnverified" : "InventoryVerified"), row.Ecu, row.Vin ?? ""),
                                row.Vin is null ? Color.DarkOrange : Color.DarkGreen);
                            if (row.ProtocolValue is byte protocol)
                                ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text(protocol == 1 ?
                                    "InventoryObdSupported" : "InventoryObdUnrecognized"), row.Ecu, protocol),
                                    protocol == 1 ? Color.DarkGreen : Color.DarkOrange);
                        }
                        ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("InventoryComplete"),
                            rows.Count(row => row.Vin is not null), rows.Count), Color.DarkGreen);
                    }
                    else if (operation == DoIpReadOperation.Backup)
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
                            }, certificate, trace: Trace, options: options);
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
                            }, certificate, Trace, options);
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
                            operation == DoIpReadOperation.DtcExtended, timeout, stop.Token, certificate, Trace, options,
                            expectedObdVin: obdDtc ? expectedObdVin : null);
                        RequireCompleteTrace();
                        stop.Token.ThrowIfCancellationRequested();
                        if (!IsDisposed && !Disposing)
                            ConsoleLine("SYSTEM", DoIpConsolePresentation.FormatDetail(detail.Dtc, detail.Status, detail.Records), Color.DarkGreen);
                    }
                    else if (operation == DoIpReadOperation.DtcCount)
                    {
                        var report = await DoIpVinReader.CountObservedGatewayDtcsAsync(observed,
                            address, tester, ecu, useTls, dtcMask, timeout, stop.Token, certificate, Trace, options,
                            expectedObdVin: obdDtc ? expectedObdVin : null);
                        RequireCompleteTrace();
                        stop.Token.ThrowIfCancellationRequested();
                        if (!IsDisposed && !Disposing)
                            ConsoleLine("SYSTEM", DoIpConsolePresentation.FormatDtcCount(report, dtcMask), Color.DarkGreen);
                    }
                    else if (operation == DoIpReadOperation.Dtcs)
                    {
                        var report = await DoIpVinReader.ReadObservedGatewayDtcsAsync(
                            observed, address, tester, ecu, useTls, dtcMask, timeout, stop.Token,
                            clientCertificateThumbprint: certificate, trace: Trace, options: options,
                            expectedObdVin: obdDtc ? expectedObdVin : null);
                        RequireCompleteTrace();
                        stop.Token.ThrowIfCancellationRequested();
                        if (IsDisposed || Disposing) return;
                        foreach (var line in DoIpConsolePresentation.FormatDtcs(report, dtcMask))
                            ConsoleLine("SYSTEM", line, Color.DarkSlateGray);
                    }
                    else if (severityFilter || operation == DoIpReadOperation.SeverityInfo)
                    {
                        byte sub = operation == DoIpReadOperation.SeverityCount ? (byte)7 :
                            operation == DoIpReadOperation.SeverityList ? (byte)8 : (byte)9;
                        var request = ObdOnUdsServices.ReadSeverity(sub, severityMask, dtcMask, detailDtc);
                        var report = await DoIpVinReader.ReadObservedSeverityAsync(observed,
                            address, tester, ecu, useTls, request, timeout, stop.Token, certificate, Trace, options);
                        RequireCompleteTrace();
                        stop.Token.ThrowIfCancellationRequested();
                        if (IsDisposed || Disposing) return;
                        if (report.Count.HasValue)
                            ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("SeverityCountResult"),
                                report.Count.Value, report.AvailabilityMask, report.FormatIdentifier), Color.DarkGreen);
                        else
                        {
                            ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("SeverityListResult"),
                                report.Records.Count, report.AvailabilityMask), Color.DarkGreen);
                            foreach (var row in report.Records)
                                ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("SeverityRecord"),
                                    row.Dtc, row.Severity, row.FunctionalUnit, row.Status), Color.DarkSlateGray);
                        }
                    }
                    else if (operation == DoIpReadOperation.FaultCounters)
                    {
                        var rows = await DoIpVinReader.ReadObservedFaultCountersAsync(observed,
                            address, tester, ecu, useTls, timeout, stop.Token, certificate, Trace, options);
                        RequireCompleteTrace();
                        stop.Token.ThrowIfCancellationRequested();
                        if (IsDisposed || Disposing) return;
                        ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("FaultCountersResult"), rows.Count), Color.DarkGreen);
                        foreach (var row in rows)
                            ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("FaultCounterEntry"),
                                row.Dtc, row.Counter), Color.DarkSlateGray);
                    }
                    else if (operation is DoIpReadOperation.FirstFailed or DoIpReadOperation.FirstConfirmed or DoIpReadOperation.RecentFailed or DoIpReadOperation.RecentConfirmed)
                    {
                        byte subfunction = operation switch
                        {
                            DoIpReadOperation.FirstFailed => 0x0B,
                            DoIpReadOperation.FirstConfirmed => 0x0C,
                            DoIpReadOperation.RecentFailed => 0x0D,
                            _ => 0x0E
                        };
                        var report = await DoIpVinReader.ReadObservedHistoricalDtcAsync(observed,
                            address, tester, ecu, useTls, subfunction, timeout, stop.Token, certificate, Trace, options);
                        RequireCompleteTrace();
                        stop.Token.ThrowIfCancellationRequested();
                        if (IsDisposed || Disposing) return;
                        var label = DoIpConsolePresentation.Text(operation.ToString());
                        var value = report.Codes.Count == 0 ? DoIpConsolePresentation.Text("NoHistoricalDtc") :
                            string.Format(DoIpConsolePresentation.Text("DtcEntry"), report.Codes[0].Code, report.Codes[0].Status);
                        ConsoleLine("SYSTEM", label + ": " + value, Color.DarkGreen);
                    }
                    else if (operation == DoIpReadOperation.SupportedDtcs)
                    {
                        var report = await DoIpVinReader.ReadObservedSupportedDtcsAsync(observed,
                            address, tester, ecu, useTls, timeout, stop.Token, certificate, Trace, options);
                        RequireCompleteTrace();
                        stop.Token.ThrowIfCancellationRequested();
                        if (IsDisposed || Disposing) return;
                        ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("SupportedResult"),
                            report.Codes.Count, report.AvailabilityMask), Color.DarkGreen);
                        foreach (var code in report.Codes)
                            ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("DtcEntry"),
                                code.Code, code.Status), Color.DarkSlateGray);
                    }
                    else if (legacySub != 0)
                    {
                        var report = await DoIpVinReader.ReadObservedLegacyDtcAsync(observed, address, tester, ecu, useTls,
                            legacySub, dtcMask, detailDtc, detailRecord, timeout, stop.Token, certificate, Trace, options);
                        RequireCompleteTrace();
                        stop.Token.ThrowIfCancellationRequested();
                        if (IsDisposed || Disposing) return;
                        ConsoleLine("SYSTEM", DoIpConsolePresentation.Text(legacySub <= 0x11 ? "LegacyMirrorResult" : "LegacyEmissionsResult"), Color.DarkGreen);
                        if (report.List is not null)
                            foreach (var line in DoIpConsolePresentation.FormatDtcs(report.List, dtcMask))
                                ConsoleLine("SYSTEM", line, Color.DarkSlateGray);
                        else if (report.Count is not null)
                            ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("DtcCountResult"),
                                report.Count.Count, dtcMask, report.Count.StatusAvailabilityMask, report.Count.DtcFormatIdentifier), Color.DarkSlateGray);
                        else if (report.Detail is not null)
                            ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("MemoryDetailResult"), report.Detail.Dtc,
                                report.Detail.Status, Convert.ToHexString(report.Detail.RawExtendedDataRecords)), Color.DarkSlateGray);
                    }
                    else if (operation == DoIpReadOperation.StoredRecord)
                    {
                        var report = await DoIpVinReader.ReadObservedStoredRecordAsync(observed, address, tester, ecu,
                            useTls, storedRecord, timeout, stop.Token, certificate, Trace, options);
                        RequireCompleteTrace();
                        stop.Token.ThrowIfCancellationRequested();
                        if (IsDisposed || Disposing) return;
                        ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("StoredRecordResult"), report.Record), Color.DarkGreen);
                        if (report.Dtc.HasValue)
                            ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("StoredRecordCount"),
                                report.RawData[0]), Color.DarkSlateGray);
                        ConsoleLine("SYSTEM", report.Dtc.HasValue
                            ? string.Format(DoIpConsolePresentation.Text("MemoryDetailResult"), report.Dtc.Value,
                                report.Status!.Value, Convert.ToHexString(report.RawData))
                            : DoIpConsolePresentation.Text("StoredRecordEmpty"), Color.DarkSlateGray);
                    }
                    else if (extendedRecordRead)
                    {
                        var report = await DoIpVinReader.ReadObservedExtendedRecordAsync(observed, address, tester, ecu,
                            useTls, extendedRecord, extendedRecordSize, timeout, stop.Token, certificate, Trace, options);
                        RequireCompleteTrace();
                        stop.Token.ThrowIfCancellationRequested();
                        if (IsDisposed || Disposing) return;
                        ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("ExtendedRecordResult"), report.Record, report.Entries.Count), Color.DarkGreen);
                        if (report.Availability.HasValue)
                            ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("ExtendedRecordMask"), report.Availability.Value), Color.DarkSlateGray);
                        foreach (var entry in report.Entries)
                            ConsoleLine("SYSTEM", extendedRecordSize.HasValue
                                ? string.Format(DoIpConsolePresentation.Text("MemoryDetailResult"), entry.Dtc, entry.Status, Convert.ToHexString(entry.Data))
                                : string.Format(DoIpConsolePresentation.Text("DtcEntry"), entry.Dtc, entry.Status), Color.DarkSlateGray);
                    }
                    else if (memoryRead)
                    {
                        byte sub = operation == DoIpReadOperation.MemoryDtcs ? (byte)0x17 :
                            operation == DoIpReadOperation.MemorySnapshot ? (byte)0x18 : (byte)0x19;
                        var report = await DoIpVinReader.ReadObservedUserMemoryAsync(observed, address,
                            tester, ecu, useTls, sub, memory, dtcMask, detailDtc, detailRecord,
                            timeout, stop.Token, certificate, Trace, options);
                        RequireCompleteTrace();
                        stop.Token.ThrowIfCancellationRequested();
                        if (IsDisposed || Disposing) return;
                        ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("MemoryResult"), report.Memory), Color.DarkGreen);
                        if (report.List is not null)
                            foreach (var line in DoIpConsolePresentation.FormatDtcs(report.List, dtcMask))
                                ConsoleLine("SYSTEM", line, Color.DarkSlateGray);
                        else
                            ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("MemoryDetailResult"),
                                report.Dtc, report.Status, Convert.ToHexString(report.RawRecords!)), Color.DarkSlateGray);
                    }
                    else if (operation == DoIpReadOperation.PermanentDtcs)
                    {
                        var report = await DoIpVinReader.ReadObservedPermanentDtcsAsync(observed,
                            address, tester, ecu, useTls, timeout, stop.Token, certificate, Trace, options);
                        RequireCompleteTrace();
                        stop.Token.ThrowIfCancellationRequested();
                        if (IsDisposed || Disposing) return;
                        ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("PermanentResult"),
                            report.Codes.Count, report.AvailabilityMask), Color.DarkGreen);
                        foreach (var code in report.Codes)
                            ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("DtcEntry"),
                                code.Code, code.Status), Color.DarkSlateGray);
                    }
                    else if (operation == DoIpReadOperation.SnapshotIndex)
                    {
                        var rows = await DoIpVinReader.ReadObservedSnapshotIndexAsync(observed,
                            address, tester, ecu, useTls, timeout, stop.Token, certificate, Trace, options);
                        RequireCompleteTrace();
                        stop.Token.ThrowIfCancellationRequested();
                        if (IsDisposed || Disposing) return;
                        ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("SnapshotIndexResult"), rows.Count), Color.DarkGreen);
                        foreach (var row in rows)
                            ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("SnapshotIndexEntry"),
                                row.Dtc, row.Record), Color.DarkSlateGray);
                    }
                    else if (operation == DoIpReadOperation.ObdMonitor)
                    {
                        await DoIpVinReader.MonitorObservedObdAsync(observed, address, tester,
                            ecu, useTls, expectedObdVin, did, sampleCount, interval, timeout, stop.Token,
                            (index, data) =>
                            {
                                DrainTrace();
                                if (!IsDisposed && !Disposing)
                                    ConsoleLine("SYSTEM", string.Format(
                                        DoIpConsolePresentation.Text("ObdMonitorSample"), index,
                                        did, Convert.ToHexString(data)), Color.DarkGreen);
                            }, certificate, Trace, options);
                        RequireCompleteTrace();
                        stop.Token.ThrowIfCancellationRequested();
                        if (!IsDisposed && !Disposing)
                            ConsoleLine("SYSTEM", string.Format(
                                DoIpConsolePresentation.Text("ObdMonitorComplete"), sampleCount),
                                Color.DarkGreen);
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
                            }, clientCertificateThumbprint: certificate, trace: Trace, options: options);
                        RequireCompleteTrace();
                        stop.Token.ThrowIfCancellationRequested();
                        if (!IsDisposed && !Disposing)
                            ConsoleLine("SYSTEM", string.Format(
                                DoIpConsolePresentation.Text("MonitorComplete"), sampleCount),
                                Color.DarkGreen);
                    }
                    else if (operation is DoIpReadOperation.MultiMonitor or DoIpReadOperation.ObdMultiMonitor)
                    {
                        await DoIpVinReader.MonitorObservedMultiDidAsync(observed, address, tester,
                            ecu, useTls, expectedObdVin, multiLayout!, operation == DoIpReadOperation.ObdMultiMonitor,
                            sampleCount, interval, timeout, stop.Token, (index, values) =>
                            {
                                DrainTrace();
                                if (!IsDisposed && !Disposing)
                                    ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("MultiMonitorSample"),
                                        index, string.Join("  ", values.Select(v => $"{v.Did:X4}={Convert.ToHexString(v.Data)}"))),
                                        Color.DarkSlateGray);
                            }, certificate, Trace, options);
                        RequireCompleteTrace();
                        stop.Token.ThrowIfCancellationRequested();
                        if (!IsDisposed && !Disposing)
                            ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("MultiMonitorComplete"),
                                sampleCount), Color.DarkGreen);
                    }
                    else if (multiLayout is not null)
                    {
                        var values = await DoIpVinReader.ReadObservedMultiDidAsync(observed, address, tester, ecu,
                            useTls, expectedObdVin, multiLayout, operation == DoIpReadOperation.ObdMultiDid,
                            timeout, stop.Token, certificate, Trace, options);
                        RequireCompleteTrace();
                        stop.Token.ThrowIfCancellationRequested();
                        if (IsDisposed || Disposing) return;
                        foreach (var item in values)
                            ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("MultiResult"),
                                item.Did, item.Data.Length, Convert.ToHexString(item.Data)), Color.DarkGreen);
                    }
                    else if (wwh)
                    {
                        byte subfunction = operation switch { DoIpReadOperation.WwhMask => 0x42,
                            DoIpReadOperation.WwhPermanent => 0x55, _ => 0x56 };
                        var report = await DoIpVinReader.ReadObservedWwhAsync(observed, address, tester, ecu,
                            useTls, expectedObdVin, subfunction, dtcMask, wwhSeverity, wwhGroup,
                            timeout, stop.Token, certificate, Trace, options);
                        RequireCompleteTrace();
                        stop.Token.ThrowIfCancellationRequested();
                        if (IsDisposed || Disposing) return;
                        foreach (var line in DoIpConsolePresentation.FormatWwh(report, subfunction,
                            subfunction == 0x56 ? wwhGroup : null))
                            ConsoleLine("SYSTEM", line, Color.DarkGreen);
                    }
                    else if (operation is DoIpReadOperation.ObdIdentify or DoIpReadOperation.ObdDid)
                    {
                        var obd = await DoIpVinReader.ReadObservedObdAsync(observed, address, tester, ecu,
                            useTls, expectedObdVin, operation == DoIpReadOperation.ObdDid ? did : null,
                            timeout, stop.Token, certificate, Trace, options);
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
                    else if (operation == DoIpReadOperation.ScalingDid)
                    {
                        var data = await DoIpVinReader.ReadObservedScalingDidAsync(observed, address, tester,
                            ecu, useTls, did, timeout, stop.Token, certificate, Trace, options);
                        RequireCompleteTrace();
                        stop.Token.ThrowIfCancellationRequested();
                        if (IsDisposed || Disposing) return;
                        ConsoleLine("SYSTEM", string.Format(DoIpConsolePresentation.Text("ScalingResult"),
                            did, Convert.ToHexString(data)), Color.DarkGreen);
                    }
                    else if (operation == DoIpReadOperation.Did)
                    {
                        var data = await DoIpVinReader.ReadObservedGatewayDidAsync(
                            observed, address, tester, ecu, useTls, did, timeout, stop.Token,
                            clientCertificateThumbprint: certificate, trace: Trace, options: options);
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
                            clientCertificateThumbprint: certificate, trace: Trace, options: options);
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
                    doIpInventoryAddresses.Enabled = doIpEcuAddress.Enabled = doIpPayload.Enabled = doIpDetailDtc.Enabled = doIpExtendedRecordSize.Enabled = doIpDtcMemory.Enabled = doIpDetailRecord.Enabled = doIpDtcMask.Enabled = doIpWwhSeverity.Enabled = doIpWwhGroup.Enabled = doIpMultiLayout.Enabled = doIpDid.Enabled = doIpMonitorInterval.Enabled = doIpMonitorCount.Enabled = doIpUseTls.Enabled = doIpClientCertificate.Enabled =
                    doIpP2.Enabled = doIpP2Star.Enabled = doIpRoutingType.Enabled = doIpRoutingOem.Enabled = true;
                doIpConsoleDiscover.Enabled = true;
                doIpConsoleStatus.Enabled = doIpConsolePower.Enabled =
                    doIpObdInventory.Enabled = doIpInventory.Enabled = doIpSeverityCount.Enabled = doIpSeverityList.Enabled = doIpSeverityInfo.Enabled = doIpFaultCounters.Enabled = doIpFirstFailed.Enabled = doIpFirstConfirmed.Enabled = doIpRecentFailed.Enabled = doIpRecentConfirmed.Enabled = doIpSupportedDtcs.Enabled = doIpMirrorDtcs.Enabled = doIpMirrorCount.Enabled = doIpMirrorExtended.Enabled = doIpLegacyEmissionsDtcs.Enabled = doIpLegacyEmissionsCount.Enabled = doIpStoredRecord.Enabled = doIpExtendedRecordSupport.Enabled = doIpExtendedRecordData.Enabled = doIpMemoryDtcs.Enabled = doIpMemorySnapshot.Enabled = doIpMemoryExtended.Enabled = doIpPermanentDtcs.Enabled = doIpSnapshotIndex.Enabled = doIpObdDtcs.Enabled = doIpObdCount.Enabled = doIpObdSnapshot.Enabled = doIpObdExtended.Enabled = doIpReadDtcs.Enabled = doIpCountDtcs.Enabled = doIpSnapshot.Enabled = doIpExtended.Enabled = doIpBackup.Enabled = doIpMultiMonitor.Enabled = doIpObdMultiMonitor.Enabled = doIpMonitorDid.Enabled = doIpObdMonitor.Enabled = doIpScalingDid.Enabled = doIpReadDid.Enabled = doIpObdIdentify.Enabled = doIpObdDid.Enabled = doIpWwhMask.Enabled = doIpWwhPermanent.Enabled = doIpWwhReadiness.Enabled = doIpMultiRead.Enabled = doIpObdMultiRead.Enabled = doIpExchange.Enabled = doIpReadVin.Enabled = doIpObservedGateway is not null;
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
