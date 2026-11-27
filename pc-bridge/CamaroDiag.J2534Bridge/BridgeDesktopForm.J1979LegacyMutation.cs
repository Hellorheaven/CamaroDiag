using System.Drawing;
using System.Globalization;
using System.Text;
using System.Windows.Forms;

namespace CamaroDiag.J2534Bridge;

internal sealed partial class BridgeDesktopForm
{
    private readonly Button functionalRunLegacyMutation = Button(J1979RecipeText("LegacyMutationRun"));

    private ObdJ1979LegacyMutationCli.Options? SelectLegacyMutation(int adapterIndex)
    {
        using var dialog = new Form
        {
            Text = J1979RecipeText("LegacyMutationRun"), ClientSize = new Size(610, 350),
            FormBorderStyle = FormBorderStyle.FixedDialog, MaximizeBox = false,
            MinimizeBox = false, StartPosition = FormStartPosition.CenterParent
        };
        var variants = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Dock = DockStyle.Fill };
        variants.Items.AddRange(new object[] {
            new LegacyChoice(1,"SAE J1850 PWM"), new LegacyChoice(2,"SAE J1850 VPW"),
            new LegacyChoice(3,"ISO 9141-2"), new LegacyChoice(4,"ISO 14230-4 slow init"),
            new LegacyChoice(5,"ISO 14230-4 fast init")
        });
        var operations = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Dock = DockStyle.Fill };
        operations.Items.AddRange(new object[] { J1979RecipeText("ClearRun"), J1979RecipeText("EvapRun") });
        var vin = new TextBox { MaxLength = 17, CharacterCasing = CharacterCasing.Upper, Dock = DockStyle.Fill };
        var engine = new TextBox { MaxLength = 2, CharacterCasing = CharacterCasing.Upper, Dock = DockStyle.Fill };
        var sources = new TextBox { MaxLength = 127, CharacterCasing = CharacterCasing.Upper, Dock = DockStyle.Fill };
        var confirmed = new CheckBox { Text = J1979RecipeText("LegacyMutationProfile"), Dock = DockStyle.Fill, AutoSize = true };
        var warning = new Label { Text = J1979RecipeText("LegacyMutationScope"), Dock = DockStyle.Fill };
        var run = new Button { Text = J1979RecipeText("LegacyMutationNext"), AutoSize = true, Enabled = false };
        var cancel = new Button { Text = J1979RecipeText("LegacyCancel"), AutoSize = true, DialogResult = DialogResult.Cancel };
        var grid = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, RowCount = 8, Padding = new Padding(12) };
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 38));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 62));
        foreach (int height in new[] { 32,32,32,32,32,55,57,43 }) grid.RowStyles.Add(new RowStyle(SizeType.Absolute,height));
        void Row(string label, Control control, int row)
        {
            grid.Controls.Add(new Label { Text = label, Dock = DockStyle.Fill },0,row);
            grid.Controls.Add(control,1,row);
        }
        Row(J1979RecipeText("LegacyVariant"),variants,0);
        Row(J1979RecipeText("LegacyMutationOperation"),operations,1);
        Row("VIN",vin,2);
        Row(J1979RecipeText("LegacyMutationEngine"),engine,3);
        Row(J1979RecipeText("LegacyMutationSources"),sources,4);
        grid.Controls.Add(confirmed,0,5); grid.SetColumnSpan(confirmed,2);
        grid.Controls.Add(warning,0,6); grid.SetColumnSpan(warning,2);
        var buttons = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.RightToLeft };
        buttons.Controls.Add(cancel); buttons.Controls.Add(run);
        grid.Controls.Add(buttons,0,7); grid.SetColumnSpan(buttons,2);
        dialog.Controls.Add(grid); dialog.AcceptButton=run; dialog.CancelButton=cancel;
        bool Valid()
        {
            if (!confirmed.Checked || variants.SelectedItem is not LegacyChoice || operations.SelectedIndex < 0 ||
                !System.Text.RegularExpressions.Regex.IsMatch(vin.Text,@"\A[A-HJ-NPR-Z0-9]{17}\z")) return false;
            try { return ObdJ1979LegacyMutationCli.ParseSources(sources.Text)
                    .Contains(ObdJ1979LegacyMutationCli.ParseSource(engine.Text)); }
            catch (ArgumentException) { return false; }
        }
        void Refresh() => run.Enabled = Valid();
        variants.SelectedIndexChanged += (_,_)=>Refresh();
        operations.SelectedIndexChanged += (_,_)=>Refresh();
        vin.TextChanged += (_,_)=>Refresh(); engine.TextChanged += (_,_)=>Refresh();
        sources.TextChanged += (_,_)=>Refresh(); confirmed.CheckedChanged += (_,_)=>Refresh();
        run.Click += (_,_)=> { if(Valid()) dialog.DialogResult=DialogResult.OK; };
        if(dialog.ShowDialog(this)!=DialogResult.OK || !Valid() || variants.SelectedItem is not LegacyChoice choice)
            return null;
        var operation=operations.SelectedIndex==0 ? ObdJ1979LegacyMutation.Operation.Clear :
            ObdJ1979LegacyMutation.Operation.EvapPreparation;
        using var save = new SaveFileDialog
        {
            Filter=J1979RecipeText("JsonFilter"), AddExtension=true, DefaultExt="json",
            FileName=$"CamaroDiag_SAE-J1979_legacy_{operation}_{choice.Number}.json",
            OverwritePrompt=true
        };
        if(save.ShowDialog(this)!=DialogResult.OK) return null;
        if(File.Exists(save.FileName) || File.Exists(save.FileName+".baseline.json"))
        { ShowError(J1979RecipeText("LegacyMutationNewFile")); return null; }
        return new(adapterIndex,(ObdLegacyJ2534Transport.Variant)choice.Number,operation,vin.Text,
            ObdJ1979LegacyMutationCli.ParseSource(engine.Text),
            ObdJ1979LegacyMutationCli.ParseSources(sources.Text),save.FileName);
    }

    private async Task RunWindowsLegacyMutationAsync()
    {
        if(functionalCancellation is not null || busy || DateTimeOffset.UtcNow>=functionalAuthorizedUntil) return;
        if(bridgeTask is { IsCompleted:false } || consoleDevice is not null ||
            adapter.SelectedItem is not AdapterChoice selected)
        { ShowError(J1979RecipeText("LegacyPrerequisites")); return; }
        var options=SelectLegacyMutation(adapter.SelectedIndex);
        if(options is null) return;
        var opKey=options.Operation==ObdJ1979LegacyMutation.Operation.Clear ? "Clear" : "Evap";
        var warning=MessageBox.Show(this,J1979RecipeText("LegacyMutationScope")+"\n\n"+J1979RecipeText(opKey+"Warning"),
            J1979RecipeText("LegacyMutationRun"),MessageBoxButtons.OKCancel,MessageBoxIcon.Warning,MessageBoxDefaultButton.Button2);
        if(warning!=DialogResult.OK) return;
        var yes=MessageBox.Show(this,string.Format(CultureInfo.CurrentUICulture,J1979RecipeText("LegacyMutationConfirm"),
            options.Vin,options.Variant,string.Join(",",options.Sources.Select(s=>s.ToString("X2")))),
            J1979RecipeText(opKey+"Run"),MessageBoxButtons.YesNo,MessageBoxIcon.Warning,MessageBoxDefaultButton.Button2);
        if(yes!=DialogResult.Yes || DateTimeOffset.UtcNow>=functionalAuthorizedUntil || busy ||
            bridgeTask is { IsCompleted:false } || consoleDevice is not null ||
            !ReferenceEquals(adapter.SelectedItem,selected)) return;
        var expiry=functionalAuthorizedUntil;
        using var cancellation=new CancellationTokenSource();
        functionalCancellation=cancellation; busy=true;
        completedJ1979 = null; completedNetwork = null; completedEvap=null; completedClear=null; completedLegacy=null;
        completedFunctionalChecks=[];
        functionalRunNetwork.Enabled = functionalRunNativeUsb.Enabled = functionalRunAll.Enabled=functionalRunJ1979.Enabled=functionalRunEvap.Enabled=
            functionalRunClear.Enabled=functionalRunLegacy.Enabled=functionalRunLegacyMutation.Enabled=
            functionalGoogleSignIn.Enabled=functionalExport.Enabled=false;
        functionalCancel.Enabled=true;
        functionalResults.Text=J1979RecipeText("Running");
        void RequireActive()
        {
            cancellation.Token.ThrowIfCancellationRequested();
            if(DateTimeOffset.UtcNow>=expiry) throw new OperationCanceledException();
        }
        try
        {
            var report=await Task.Run(()=>ObdJ1979LegacyMutationCli.Execute(selected.Adapter,options,true,true,RequireActive));
            // A previously approved output file preserves evidence even if the
            // private UI session expires. Do not display it after expiration.
            if(DateTimeOffset.UtcNow>=expiry)
            { functionalResults.Text=J1979RecipeText("LegacyMutationUncertain"); return; }
            var result=report.Result;
            var output=new StringBuilder(result?.Outcome ?? report.Error ?? "NOT_TESTED");
            output.AppendLine().AppendLine(J1979RecipeText("LegacyMutationScope"));
            if(result is not null)
            {
                output.AppendLine(result.Reason);
                foreach(var pair in result.Acknowledgments) output.AppendLine($"{pair.Key}: {pair.Value}");
                foreach(var row in result.Observations)
                {
                    output.AppendLine($"{row.Stage} {row.RequestHex}");
                    foreach(var reply in row.Replies) output.AppendLine($"  {reply.Source}: {reply.RawHex}");
                }
            }
            output.AppendLine(J1979RecipeText("LegacyMutationSaved")).AppendLine(options.Output);
            functionalResults.Text=output.ToString();
        }
        catch(Exception)
        {
            functionalResults.Text=J1979RecipeText("LegacyMutationUncertain");
        }
        finally
        {
            functionalCancellation=null; busy=false; functionalCancel.Enabled=false;
            functionalGoogleSignIn.Enabled=true;
            functionalRunNetwork.Enabled = functionalRunNativeUsb.Enabled = functionalRunAll.Enabled=functionalRunJ1979.Enabled=functionalRunEvap.Enabled=
                functionalRunClear.Enabled=functionalRunLegacy.Enabled=functionalRunLegacyMutation.Enabled=
                DateTimeOffset.UtcNow<functionalAuthorizedUntil;
        }
    }
}
