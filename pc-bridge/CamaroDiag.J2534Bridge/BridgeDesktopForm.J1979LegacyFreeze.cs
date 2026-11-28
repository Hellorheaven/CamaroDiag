using System.Drawing;
using System.Globalization;
using System.Text;
using System.Windows.Forms;

namespace CamaroDiag.J2534Bridge;

internal sealed partial class BridgeDesktopForm
{
    private readonly Button functionalRunLegacyFreeze=Button(J1979RecipeText("FreezeLegacyRun"));
    private ObdJ1979LegacyFreezeCli.Report? completedLegacyFreeze;
    private DateTimeOffset legacyFreezeAuthorization;

    private ObdJ1979LegacyFreezeCli.Options? ChooseLegacyFreezeOptions()
    {
        using var dialog=new Form
        {
            Text=J1979RecipeText("FreezeLegacyRun"),StartPosition=FormStartPosition.CenterParent,
            ClientSize=new Size(630,420),FormBorderStyle=FormBorderStyle.FixedDialog,
            MaximizeBox=false,MinimizeBox=false
        };
        var variant=new ComboBox{Dock=DockStyle.Fill,DropDownStyle=ComboBoxStyle.DropDownList};
        variant.Items.AddRange(new object[]{
            new LegacyChoice(1,"SAE J1850 PWM"),new LegacyChoice(2,"SAE J1850 VPW"),
            new LegacyChoice(3,"ISO 9141-2"),new LegacyChoice(4,"ISO 14230-4 (slow init)"),
            new LegacyChoice(5,"ISO 14230-4 (fast init)")});
        var source=new TextBox{Text="10",MaxLength=2,CharacterCasing=CharacterCasing.Upper,Dock=DockStyle.Fill};
        var vin=new TextBox{Text=functionalEvapVin.Text.Trim(),MaxLength=17,CharacterCasing=CharacterCasing.Upper,Dock=DockStyle.Fill};
        var frames=new TextBox{Text="00",MaxLength=23,CharacterCasing=CharacterCasing.Upper,Dock=DockStyle.Fill};
        var pids=new TextBox{MaxLength=191,CharacterCasing=CharacterCasing.Upper,Dock=DockStyle.Fill};
        var budget=new NumericUpDown{Minimum=1,Maximum=600,Value=180,Dock=DockStyle.Fill};
        var profile=new CheckBox{Text=J1979RecipeText("LegacyConfirmProfile"),Dock=DockStyle.Fill,AutoSize=true};
        var scope=new CheckBox{Text=J1979RecipeText("FreezeLegacyConfirm"),Dock=DockStyle.Fill,AutoSize=true};
        var start=Button(J1979RecipeText("FreezeLegacyRun"));
        var cancel=Button(J1979RecipeText("LegacyCancel"));cancel.DialogResult=DialogResult.Cancel;
        var grid=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=2,RowCount=9,Padding=new Padding(12)};
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,42));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,58));
        foreach(int height in new[]{32,32,32,32,32,32,50,65,45})
            grid.RowStyles.Add(new RowStyle(SizeType.Absolute,height));
        void Field(string key,Control input,int row)
        {
            grid.Controls.Add(new Label{Text=J1979RecipeText(key),Dock=DockStyle.Fill},0,row);
            grid.Controls.Add(input,1,row);
        }
        Field("LegacyVariant",variant,0);Field("LegacySource",source,1);Field("LiveVin",vin,2);
        Field("FreezeFrames",frames,3);Field("FreezePids",pids,4);Field("LiveBudget",budget,5);
        grid.Controls.Add(profile,0,6);grid.SetColumnSpan(profile,2);
        grid.Controls.Add(scope,0,7);grid.SetColumnSpan(scope,2);
        var buttons=new FlowLayoutPanel{Dock=DockStyle.Fill};
        buttons.Controls.Add(start);buttons.Controls.Add(cancel);grid.Controls.Add(buttons,0,8);grid.SetColumnSpan(buttons,2);
        ObdJ1979LegacyFreezeCli.Options? Candidate()
        {
            if(variant.SelectedItem is not LegacyChoice choice)return null;
            var args=new List<string>{
                ObdJ1979LegacyFreezeCli.Command,"--adapter-index",adapter.SelectedIndex.ToString(CultureInfo.InvariantCulture),
                "--variant",choice.Number.ToString(CultureInfo.InvariantCulture),"--source",source.Text,
                "--vin",vin.Text,"--budget-ms",((int)budget.Value*1000).ToString(CultureInfo.InvariantCulture),
                "--confirm-legacy-profile","--confirm-functional-scope","--output","unused.json"};
            foreach(var item in frames.Text.Split(',',StringSplitOptions.TrimEntries))args.AddRange(["--frame",item]);
            if(!string.IsNullOrWhiteSpace(pids.Text))
                foreach(var item in pids.Text.Split(',',StringSplitOptions.TrimEntries))args.AddRange(["--pid",item]);
            try{return ObdJ1979LegacyFreezeCli.Parse(args.ToArray());}
            catch(ArgumentException){return null;}
        }
        void Refresh()=>start.Enabled=profile.Checked&&scope.Checked&&Candidate() is not null;
        void Changed(){profile.Checked=false;scope.Checked=false;Refresh();}
        variant.SelectedIndexChanged+=(_,_)=>Changed();source.TextChanged+=(_,_)=>Changed();
        vin.TextChanged+=(_,_)=>Changed();frames.TextChanged+=(_,_)=>Changed();pids.TextChanged+=(_,_)=>Changed();
        budget.ValueChanged+=(_,_)=>Changed();profile.CheckedChanged+=(_,_)=>Refresh();scope.CheckedChanged+=(_,_)=>Refresh();
        ObdJ1979LegacyFreezeCli.Options? chosen=null;
        start.Click+=(_,_)=>{
            if(profile.Checked&&scope.Checked&&(chosen=Candidate()) is not null)
                dialog.DialogResult=DialogResult.OK;
        };
        dialog.Controls.Add(grid);dialog.CancelButton=cancel;Refresh();
        return dialog.ShowDialog(this)==DialogResult.OK?chosen:null;
    }

    private async Task RunWindowsLegacyFreezeAsync()
    {
        if(busy||functionalCancellation is not null||DateTimeOffset.UtcNow>=functionalAuthorizedUntil)return;
        if(bridgeTask is {IsCompleted:false}||consoleDevice is not null||adapter.SelectedItem is not AdapterChoice selected)
        {ShowError(J1979RecipeText("LegacyPrerequisites"));return;}
        var options=ChooseLegacyFreezeOptions();
        if(options is null||busy||functionalCancellation is not null||DateTimeOffset.UtcNow>=functionalAuthorizedUntil||
           bridgeTask is {IsCompleted:false}||consoleDevice is not null||!ReferenceEquals(adapter.SelectedItem,selected))return;
        var expiry=functionalAuthorizedUntil;
        using var cancel=new CancellationTokenSource();
        functionalCancellation=cancel;busy=true;
        completedLegacyFreeze=null;completedFreeze=null;completedJ1979=null;completedNetwork=null;completedLive=null;
        completedClear=null;completedEvap=null;completedLegacy=null;completedFunctionalChecks=[];
        functionalRunLive.Enabled=functionalRunNetwork.Enabled=functionalRunNativeUsb.Enabled=
            functionalRunAll.Enabled=functionalRunJ1979.Enabled=functionalRunEvap.Enabled=
            functionalRunClear.Enabled=functionalRunLegacy.Enabled=functionalRunLegacyMutation.Enabled=
            functionalGoogleSignIn.Enabled=functionalExport.Enabled=false;
        functionalCancel.Enabled=true;functionalResults.Text=J1979RecipeText("Running");
        void Current()
        {
            cancel.Token.ThrowIfCancellationRequested();
            if(DateTimeOffset.UtcNow>=expiry||functionalAuthorizedUntil!=expiry)throw new OperationCanceledException();
        }
        try
        {
            var report=await Task.Run(()=>ObdJ1979LegacyFreezeCli.Execute(selected.Adapter,options,Current));
            if(IsDisposed||Disposing||DateTimeOffset.UtcNow>=expiry||functionalAuthorizedUntil!=expiry)return;
            if(cancel.IsCancellationRequested&&report.ExecutionCompleted)report=report with {TransportProblem="CANCELLED"};
            var presentation=new ObdJ1979FreezeCli.Report("","SAE-J1979","","",report.Adapter,0,"","",false,
                report.StartedAtUtc,report.FinishedAtUtc,report.TransportProblem,report.Result);
            var output=new StringBuilder(FormatFreeze(presentation));
            output.AppendLine(J1979RecipeText("LegacyScope"));
            output.AppendLine($"{options.Variant} / {report.SelectedResponseSource}");
            if(report.InitializationEvidence is not null)output.AppendLine(report.InitializationEvidence);
            functionalResults.Text=output.ToString();
            completedLegacyFreeze=report;legacyFreezeAuthorization=expiry;functionalExport.Enabled=true;
        }
        catch(OperationCanceledException){if(!IsDisposed&&!Disposing)functionalResults.Text=J1979RecipeText("Cancelled");}
        catch(Exception){if(!IsDisposed&&!Disposing)functionalResults.Text=J1979RecipeText("Failed");}
        finally
        {
            functionalCancellation=null;busy=false;
            if(!IsDisposed&&!Disposing)
            {
                functionalCancel.Enabled=false;functionalGoogleSignIn.Enabled=true;
                functionalRunLive.Enabled=functionalRunNetwork.Enabled=functionalRunNativeUsb.Enabled=
                    functionalRunAll.Enabled=functionalRunJ1979.Enabled=functionalRunEvap.Enabled=
                    functionalRunClear.Enabled=functionalRunLegacy.Enabled=functionalRunLegacyMutation.Enabled=
                    DateTimeOffset.UtcNow<functionalAuthorizedUntil;
            }
        }
    }

    private void ExportWindowsLegacyFreeze(ObdJ1979LegacyFreezeCli.Report report)
    {
        using var dialog=new SaveFileDialog{Filter=J1979RecipeText("JsonFilter"),DefaultExt="json",
            AddExtension=true,OverwritePrompt=false,FileName="CamaroDiag_SAE-J1979_Legacy_Freeze.json"};
        if(dialog.ShowDialog(this)!=DialogResult.OK||DateTimeOffset.UtcNow>=functionalAuthorizedUntil||
           functionalAuthorizedUntil!=legacyFreezeAuthorization||!ReferenceEquals(completedLegacyFreeze,report))return;
        try{ObdJ1979LegacyFreezeCli.WriteNew(dialog.FileName,report);}
        catch(Exception){ShowError(J1979RecipeText("ExportFailed"));}
    }
}
