using System.Drawing;
using System.Globalization;
using System.Text;
using System.Windows.Forms;

namespace CamaroDiag.J2534Bridge;

internal sealed partial class BridgeDesktopForm
{
    private readonly Button functionalRunLegacy = Button(J1979RecipeText("LegacyRun"));
    private ObdJ1979LegacyCli.Report? completedLegacy;

    private sealed record LegacyChoice(int Number, string Text)
    {
        public override string ToString() => Text;
    }

    private (ObdLegacyJ2534Transport.Variant Variant, byte? Source,string? ExpectedVin)? SelectLegacyRecipe()
    {
        using var dialog = new Form
        {
            Text = J1979RecipeText("LegacyRun"), ClientSize = new Size(510, 282),
            FormBorderStyle = FormBorderStyle.FixedDialog, MaximizeBox = false,
            MinimizeBox = false, StartPosition = FormStartPosition.CenterParent
        };
        var variants = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Dock = DockStyle.Fill };
        variants.Items.AddRange(new object[]
        {
            new LegacyChoice(1, "SAE J1850 PWM (41.6 kbit/s)"),
            new LegacyChoice(2, "SAE J1850 VPW (10.4 kbit/s)"),
            new LegacyChoice(3, "ISO 9141-2 (5 baud init)"),
            new LegacyChoice(4, "ISO 14230-4 (slow init)"),
            new LegacyChoice(5, "ISO 14230-4 (fast init)")
        });
        var source = new TextBox { MaxLength = 2, CharacterCasing = CharacterCasing.Upper, Width = 54 };
        var vin=new TextBox {MaxLength=17,CharacterCasing=CharacterCasing.Upper,Dock=DockStyle.Fill};
        var discover = new CheckBox { Text = J1979RecipeText("LegacyDiscover"), AutoSize = true };
        var sourceControls = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.LeftToRight };
        sourceControls.Controls.Add(source);
        sourceControls.Controls.Add(discover);
        var documented = new CheckBox { Text = J1979RecipeText("LegacyConfirmProfile"),
            Dock = DockStyle.Fill, AutoSize = true };
        var run = new Button { Text = J1979RecipeText("LegacyRun"), AutoSize = true, Enabled = false };
        var cancel = new Button { Text = J1979RecipeText("LegacyCancel"), AutoSize = true, DialogResult = DialogResult.Cancel };
        var grid = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, RowCount = 6, Padding = new Padding(12) };
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 42));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 58));
        foreach (int height in new[] { 36, 36, 36, 45, 47, 42 })
            grid.RowStyles.Add(new RowStyle(SizeType.Absolute, height));
        grid.Controls.Add(new Label { Text = J1979RecipeText("LegacyVariant"), Dock = DockStyle.Fill }, 0, 0);
        grid.Controls.Add(variants, 1, 0);
        grid.Controls.Add(new Label { Text = J1979RecipeText("LegacySource"), Dock = DockStyle.Fill }, 0, 1);
        grid.Controls.Add(sourceControls, 1, 1);
        grid.Controls.Add(new Label {Text=J1979RecipeText("LegacyOptionalVin"),Dock=DockStyle.Fill},0,2);
        grid.Controls.Add(vin,1,2);
        grid.Controls.Add(documented, 0, 3);
        grid.SetColumnSpan(documented, 2);
        var scope = new Label { Text = J1979RecipeText("LegacyScope"), Dock = DockStyle.Fill };
        grid.Controls.Add(scope, 0, 4);
        grid.SetColumnSpan(scope, 2);
        var buttons = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.RightToLeft };
        buttons.Controls.Add(cancel);
        buttons.Controls.Add(run);
        grid.Controls.Add(buttons, 0, 5);
        grid.SetColumnSpan(buttons, 2);
        dialog.Controls.Add(grid);
        dialog.AcceptButton = run;
        dialog.CancelButton = cancel;
        bool ValidSource(out byte result) =>
            byte.TryParse(source.Text, NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture, out result)
            && source.Text.Length == 2 && result is not (0 or 0xF1 or 0xFF);
        void RefreshAdmission()
        {
            source.Enabled = vin.Enabled = !discover.Checked;
            bool vinValid=vin.Text.Length==0||System.Text.RegularExpressions.Regex.IsMatch(vin.Text,@"\A[A-HJ-NPR-Z0-9]{17}\z");
            run.Enabled = documented.Checked && variants.SelectedItem is LegacyChoice &&
                (discover.Checked || ValidSource(out _) && vinValid);
        }
        variants.SelectedIndexChanged += (_, _) => RefreshAdmission();
        source.TextChanged += (_, _) => RefreshAdmission();
        vin.TextChanged += (_, _) => RefreshAdmission();
        discover.CheckedChanged += (_, _) => RefreshAdmission();
        documented.CheckedChanged += (_, _) => RefreshAdmission();
        run.Click += (_, _) =>
        {
            if (run.Enabled) dialog.DialogResult = DialogResult.OK;
        };
        if (dialog.ShowDialog(this) != DialogResult.OK || !documented.Checked ||
            variants.SelectedItem is not LegacyChoice choice)
            return null;
        if (discover.Checked) return ((ObdLegacyJ2534Transport.Variant)choice.Number, null,null);
        if (!ValidSource(out var ecu)) return null;
        return ((ObdLegacyJ2534Transport.Variant)choice.Number, ecu,vin.Text.Length==0?null:vin.Text);
    }

    private async Task RunWindowsLegacyAsync()
    {
        if (functionalCancellation is not null || busy || DateTimeOffset.UtcNow >= functionalAuthorizedUntil)
            return;
        if (bridgeTask is { IsCompleted: false } || consoleDevice is not null ||
            adapter.SelectedItem is not AdapterChoice selected)
        {
            ShowError(J1979RecipeText("LegacyPrerequisites"));
            return;
        }
        var choice = SelectLegacyRecipe();
        if (choice is null || DateTimeOffset.UtcNow >= functionalAuthorizedUntil) return;
        if (busy || bridgeTask is { IsCompleted: false } || consoleDevice is not null ||
            !ReferenceEquals(adapter.SelectedItem, selected)) return;
        var expires = functionalAuthorizedUntil;
        using var cancellation = new CancellationTokenSource();
        functionalCancellation = cancellation;
        busy = true;
        completedJ1979 = null;
        completedEvap = null;
        completedClear = null;
        completedLegacy = null;
        completedFunctionalChecks = [];
        functionalRunNativeUsb.Enabled = functionalRunAll.Enabled = functionalRunJ1979.Enabled = functionalRunEvap.Enabled =
            functionalRunClear.Enabled = functionalRunLegacy.Enabled = functionalRunLegacyMutation.Enabled =
            functionalGoogleSignIn.Enabled = functionalExport.Enabled = false;
        functionalCancel.Enabled = true;
        functionalResults.Text = J1979RecipeText("Running");
        void RequireActive()
        {
            cancellation.Token.ThrowIfCancellationRequested();
            if (DateTimeOffset.UtcNow >= expires) throw new OperationCanceledException();
        }
        try
        {
            var (variant, source,expectedVin) = choice.Value;
            var report = await Task.Run(() => ObdJ1979LegacyCli.Execute(selected.Adapter,
                variant, source, TimeSpan.FromMinutes(3), RequireActive,expectedVin));
            if (DateTimeOffset.UtcNow >= expires || functionalAuthorizedUntil!=expires)
            {
                functionalResults.Text = J1979RecipeText("Cancelled");
                return;
            }
            var output = new StringBuilder();
            output.AppendLine(J1979RecipeText(report.Result?.Completed == true || report.Discovery?.HasVerifiedSource == true
                ? "Completed" : "Partial"));
            output.AppendLine(report.Variant + " / " + (report.SelectedResponseSource ?? J1979RecipeText("LegacyDiscover")));
            if (report.InitializationEvidence is not null)
                output.AppendLine(report.InitializationEvidence);
            output.AppendLine("VIN: " + (report.Result?.ObservedVin ?? J1979RecipeText("Unavailable")));
            if (report.Error is not null) output.AppendLine(report.Error);
            if (report.Result is { } result)
            {
                output.AppendLine(J1979RecipeText(result.VinMatch switch{
                    "MATCH"=>"ScanVinMatch","MISMATCH"=>"ScanVinMismatch",
                    "UNVERIFIED"=>"ScanVinUnverified",_=>"ScanVinNotRequested"}));
                foreach(var inventory in result.Inventory){
                    var state=J1979RecipeText(inventory.State switch{
                        "COMPLETE"=>"ScanInventoryComplete","PARTIAL"=>"ScanInventoryPartial",
                        "NOT_APPLICABLE"=>"ScanInventoryNotApplicable",_=>"ScanInventoryUnavailable"});
                    output.AppendLine(string.Format(CultureInfo.CurrentUICulture,J1979RecipeText("ScanInventoryRow"),
                        inventory.Mode.ToString("X2")+(inventory.SensorMask is byte mask?"/"+mask.ToString("X2"):""),
                        inventory.AdvertisedIds.Count,state));
                }
                foreach(var dtc in result.DtcModes)
                    output.AppendLine(string.Format(CultureInfo.CurrentUICulture,J1979RecipeText("ScanDtcMode"),
                        dtc.Mode.ToString("X2"),dtc.Codes is null?J1979RecipeText("Unavailable"):
                        dtc.Codes.Count==0?J1979RecipeText("NativeUsbZeroDtc"):string.Join(", ",dtc.Codes)));
                foreach(var measurement in result.Measurements)
                    output.AppendLine($"{measurement.Mode:X2}/{measurement.Pid:X2}: {measurement.Number.ToString(CultureInfo.CurrentCulture)} {measurement.Unit}");
                foreach(var item in result.IdentificationRecords)
                    output.AppendLine($"09/{item.InfoType:X2}: {string.Join(", ",item.Values)}");
                foreach(var monitor in result.MonitorRecords)
                    output.AppendLine($"{monitor.Mode:X2}/{monitor.TestId:X2}: {monitor.EncodedDataHex}");
                foreach (var step in result.Steps)
                {
                    output.AppendLine($"{step.RequestHex} [{step.Status}] {step.Detail}");
                    foreach (var reply in step.Responses)
                        output.AppendLine($"  {reply.Source}: {reply.RawHex}");
                }
            }
            if (report.Discovery is { } discoveryResult)
                foreach (var row in discoveryResult.Sources)
                {
                    output.AppendLine($"{row.ResponseSource} [{row.Status}] {row.Detail}");
                    foreach (var reply in row.Records) output.AppendLine($"  {reply.RawHex}");
                }
            output.AppendLine(J1979RecipeText(report.Discovery is null ? "LegacyScope" : "LegacyDiscoveryScope"));
            functionalResults.Text = output.ToString();
            completedLegacy = report;
            functionalExport.Enabled = true;
        }
        catch (Exception error)
        {
            functionalResults.Text = J1979RecipeText("Failed") + " " + error.GetType().Name;
        }
        finally
        {
            functionalCancellation = null;
            busy = false;
            functionalCancel.Enabled = false;
            functionalGoogleSignIn.Enabled = true;
            functionalRunNativeUsb.Enabled = functionalRunAll.Enabled = functionalRunJ1979.Enabled = functionalRunEvap.Enabled =
                functionalRunClear.Enabled = functionalRunLegacy.Enabled = functionalRunLegacyMutation.Enabled =
                DateTimeOffset.UtcNow < functionalAuthorizedUntil;
        }
    }

    private void ExportWindowsLegacy(ObdJ1979LegacyCli.Report report)
    {
        using var dialog = new SaveFileDialog
        {
            Filter = J1979RecipeText("JsonFilter"), AddExtension = true, DefaultExt = "json",
            FileName = $"CamaroDiag_SAE-J1979_{report.Variant}{(report.Discovery is null ? "" : "_discovery")}.json",
            OverwritePrompt = true
        };
        if (dialog.ShowDialog(this) != DialogResult.OK ||
            DateTimeOffset.UtcNow >= functionalAuthorizedUntil ||
            !ReferenceEquals(completedLegacy, report)) return;
        try
        {
            using var stream = new FileStream(dialog.FileName, FileMode.CreateNew, FileAccess.Write, FileShare.None);
            using var writer = new StreamWriter(stream, Encoding.UTF8);
            writer.WriteLine(ObdJ1979LegacyCli.Encode(report));
            writer.Flush();
            stream.Flush(true);
        }
        catch (Exception)
        {
            ShowError(J1979RecipeText("ExportFailed"));
        }
    }
}
