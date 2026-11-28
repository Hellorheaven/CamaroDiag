using System.Drawing;
using System.Globalization;
using System.Text;
using System.Windows.Forms;

namespace CamaroDiag.J2534Bridge;

internal sealed partial class BridgeDesktopForm
{
    private readonly Button functionalRunFreeze = Button(J1979RecipeText("FreezeRun"));
    private ObdJ1979FreezeCli.Report? completedFreeze;
    private DateTimeOffset freezeAuthorization;

    private ObdJ1979FreezeCli.Options? ChooseFreezeOptions()
    {
        using var dialog = new Form
        {
            Text = J1979RecipeText("FreezeRun"), StartPosition = FormStartPosition.CenterParent,
            ClientSize = new Size(590,330), FormBorderStyle = FormBorderStyle.FixedDialog,
            MaximizeBox = false, MinimizeBox = false
        };
        var frames = new TextBox { Text = "00", Dock = DockStyle.Fill,
            CharacterCasing = CharacterCasing.Upper, MaxLength = 23 };
        var pids = new TextBox { Dock = DockStyle.Fill, CharacterCasing = CharacterCasing.Upper, MaxLength = 191 };
        var vin = new TextBox { Text = functionalEvapVin.Text.Trim(), Dock = DockStyle.Fill,
            CharacterCasing = CharacterCasing.Upper, MaxLength = 17 };
        var budget = new NumericUpDown { Minimum = 1, Maximum = 600, Value = 180, Dock = DockStyle.Fill };
        var confirm = new CheckBox { Text = J1979RecipeText("FreezeConfirm"), AutoSize = true };
        var run = Button(J1979RecipeText("FreezeRun"));
        var cancel = Button(J1979RecipeText("LegacyCancel"));
        cancel.DialogResult = DialogResult.Cancel;
        var grid = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, RowCount = 7, Padding = new Padding(12) };
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,42));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,58));
        foreach (var height in new[] { 32,32,32,32,32,58,45 })
            grid.RowStyles.Add(new RowStyle(SizeType.Absolute,height));
        void Field(string key,Control control,int row)
        {
            grid.Controls.Add(new Label { Text = J1979RecipeText(key), Dock = DockStyle.Fill },0,row);
            grid.Controls.Add(control,1,row);
        }
        Field("LiveChannel",new Label { Text = $"{requestId.Text.Trim()} → {responseId.Text.Trim()} · {protocol.SelectedItem}",
            Dock = DockStyle.Fill },0);
        Field("LiveVin",vin,1);
        Field("FreezeFrames",frames,2);
        Field("FreezePids",pids,3);
        Field("LiveBudget",budget,4);
        grid.Controls.Add(confirm,0,5); grid.SetColumnSpan(confirm,2);
        var buttons = new FlowLayoutPanel { Dock = DockStyle.Fill };
        buttons.Controls.Add(run); buttons.Controls.Add(cancel);
        grid.Controls.Add(buttons,0,6); grid.SetColumnSpan(buttons,2);
        ObdJ1979FreezeCli.Options? Candidate()
        {
            var args = new List<string> { ObdJ1979FreezeCli.Command,
                "--adapter-index",adapter.SelectedIndex.ToString(CultureInfo.InvariantCulture),
                "--protocol",protocol.SelectedItem?.ToString() ?? "",
                "--tx",requestId.Text.Trim(),"--rx",responseId.Text.Trim(),
                "--vin",vin.Text,"--budget-ms",((int)budget.Value * 1000).ToString(CultureInfo.InvariantCulture),
                "--confirm-physical-channel","--output","gui.json" };
            foreach (var frame in frames.Text.Split(',',StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries))
                args.AddRange(["--frame",frame]);
            foreach (var pid in pids.Text.Split(',',StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries))
                args.AddRange(["--pid",pid]);
            try { return ObdJ1979FreezeCli.Parse(args.ToArray()); }
            catch (ArgumentException) { return null; }
        }
        void Refresh() => run.Enabled = confirm.Checked && Candidate() is not null;
        void Changed() { confirm.Checked = false; Refresh(); }
        frames.TextChanged += (_,_) => Changed();
        pids.TextChanged += (_,_) => Changed();
        vin.TextChanged += (_,_) => Changed();
        budget.ValueChanged += (_,_) => Changed();
        confirm.CheckedChanged += (_,_) => Refresh();
        ObdJ1979FreezeCli.Options? chosen = null;
        run.Click += (_,_) =>
        {
            if (confirm.Checked && (chosen = Candidate()) is not null) dialog.DialogResult = DialogResult.OK;
        };
        dialog.Controls.Add(grid); dialog.CancelButton = cancel; Refresh();
        return dialog.ShowDialog(this) == DialogResult.OK ? chosen : null;
    }

    private async Task RunWindowsFreezeAsync()
    {
        if (functionalCancellation is not null || DateTimeOffset.UtcNow >= functionalAuthorizedUntil) return;
        if (busy || bridgeTask is { IsCompleted:false } || consoleDevice is not null ||
            adapter.SelectedItem is not AdapterChoice selected)
        {
            ShowError(J1979RecipeText("Prerequisites"));
            return;
        }
        var options = ChooseFreezeOptions();
        if (options is null || busy || functionalCancellation is not null ||
            DateTimeOffset.UtcNow >= functionalAuthorizedUntil ||
            !ReferenceEquals(adapter.SelectedItem,selected)) return;
        var expiry = functionalAuthorizedUntil;
        using var cancel = new CancellationTokenSource();
        functionalCancellation = cancel; busy = true;
        completedFreeze = null; completedLive = null; completedNetwork = null; completedJ1979 = null;
        completedEvap = null; completedClear = null; completedLegacy = null; completedFunctionalChecks = [];
        functionalRunLive.Enabled = functionalRunNetwork.Enabled = functionalRunNativeUsb.Enabled =
            functionalRunAll.Enabled = functionalRunJ1979.Enabled = functionalRunEvap.Enabled =
            functionalRunClear.Enabled = functionalRunLegacy.Enabled = functionalRunLegacyMutation.Enabled =
            functionalGoogleSignIn.Enabled = functionalExport.Enabled = false;
        functionalCancel.Enabled = true;
        functionalResults.Text = J1979RecipeText("Running");
        void RequireActive()
        {
            cancel.Token.ThrowIfCancellationRequested();
            if (DateTimeOffset.UtcNow >= expiry || functionalAuthorizedUntil != expiry)
                throw new OperationCanceledException();
        }
        try
        {
            var report = await Task.Run(() => ObdJ1979FreezeCli.Execute(selected.Adapter,options,RequireActive));
            if (DateTimeOffset.UtcNow >= expiry || functionalAuthorizedUntil != expiry) return;
            if (cancel.IsCancellationRequested && report.ExecutionCompleted)
                report = report with { TransportProblem = "CANCELLED" };
            functionalResults.Text = FormatFreeze(report);
            completedFreeze = report; freezeAuthorization = expiry;
            functionalExport.Enabled = true;
        }
        catch (OperationCanceledException) { functionalResults.Text = J1979RecipeText("Cancelled"); }
        catch (Exception e) { functionalResults.Text = J1979RecipeText("Failed") + " " + e.GetType().Name; }
        finally
        {
            functionalCancellation = null; busy = false; functionalCancel.Enabled = false;
            functionalGoogleSignIn.Enabled = true;
            functionalRunLive.Enabled = functionalRunNetwork.Enabled = functionalRunNativeUsb.Enabled =
                functionalRunAll.Enabled = functionalRunJ1979.Enabled = functionalRunEvap.Enabled =
                functionalRunClear.Enabled = functionalRunLegacy.Enabled = functionalRunLegacyMutation.Enabled =
                DateTimeOffset.UtcNow < functionalAuthorizedUntil;
        }
    }

    private static string FormatFreeze(ObdJ1979FreezeCli.Report report)
    {
        var result = report.Result;
        var culture = CultureInfo.CurrentUICulture;
        var output = new StringBuilder();
        output.AppendLine(J1979RecipeText(report.ExecutionCompleted && result.AllFramesStable ? "Completed" : "Partial"));
        output.AppendLine("VIN: " + (result.InitialVin ?? J1979RecipeText("Unavailable")));
        output.AppendLine(string.Format(culture,J1979RecipeText("FreezeIdentityRecheck"),
            J1979RecipeText(result.IdentityRechecked ? "FreezeVerified" : "Unavailable")));
        if (report.TransportProblem is not null) output.AppendLine(report.TransportProblem);
        if (result.StopReason is not null) output.AppendLine(result.StopReason);
        foreach (var frame in result.Frames)
        {
            output.AppendLine(string.Format(culture,J1979RecipeText("FreezeFrameSummary"),
                frame.Frame.ToString("X2"),J1979RecipeText("FreezeStatus_" + frame.Status),
                frame.TriggerDtc ?? "—",frame.ReadPids.Length,frame.AdvertisedPids.Length));
            if (frame.Detail is not null) output.AppendLine(frame.Detail);
            if (frame.UnadvertisedSelectedPids.Length > 0)
                output.AppendLine(string.Format(culture,J1979RecipeText("FreezeNotAdvertised"),
                    string.Join(", ",frame.UnadvertisedSelectedPids.Select(x => x.ToString("X2")))));
            if (frame.NotReadSelectedPids.Length > 0)
                output.AppendLine(string.Format(culture,J1979RecipeText("FreezeNotRead"),
                    string.Join(", ",frame.NotReadSelectedPids.Select(x => x.ToString("X2")))));
            foreach (int index in frame.MeasurementSteps)
            {
                var step = result.Steps[index];
                if (step.Evidence?.GetValueOrDefault("measurement") is ObdJ1979FreezeCampaign.Measurement value)
                    output.AppendLine($"  {value.Pid:X2} {value.Number.ToString("0.###",culture)} {value.Unit}");
                else output.AppendLine($"  {step.RequestHex} [{step.Status}] {step.ResponseHex} {step.Detail}");
            }
        }
        if (result.UnvisitedFrames.Length > 0)
            output.AppendLine(string.Format(culture,J1979RecipeText("FreezeUnvisited"),
                string.Join(", ",result.UnvisitedFrames.Select(x => x.ToString("X2")))));
        output.AppendLine(J1979RecipeText("FreezeScope"));
        return output.ToString();
    }

    private void ExportWindowsFreeze(ObdJ1979FreezeCli.Report report)
    {
        using var dialog = new SaveFileDialog
        {
            Filter = J1979RecipeText("JsonFilter"), AddExtension = true, DefaultExt = "json",
            FileName = "CamaroDiag_J1979_Freeze.json", OverwritePrompt = false
        };
        if (dialog.ShowDialog(this) != DialogResult.OK || DateTimeOffset.UtcNow >= functionalAuthorizedUntil ||
            functionalAuthorizedUntil != freezeAuthorization || !ReferenceEquals(completedFreeze,report)) return;
        try { ObdJ1979FreezeCli.WriteNew(dialog.FileName,report); }
        catch (Exception) { ShowError(J1979RecipeText("ExportFailed")); }
    }
}
