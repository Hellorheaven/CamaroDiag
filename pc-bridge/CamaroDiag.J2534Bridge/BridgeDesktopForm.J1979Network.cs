using System.Drawing;
using System.Globalization;
using System.Text;
using System.Windows.Forms;

namespace CamaroDiag.J2534Bridge;

internal sealed partial class BridgeDesktopForm
{
    private ObdJ1979CanNetworkCli.Options? ChooseNetworkOptions()
    {
        using var dialog=new Form{Text=J1979RecipeText("NetworkRun"),StartPosition=FormStartPosition.CenterParent,
            ClientSize=new Size(550,300),FormBorderStyle=FormBorderStyle.FixedDialog,MaximizeBox=false,MinimizeBox=false};
        var profile=new ComboBox{DropDownStyle=ComboBoxStyle.DropDownList,Dock=DockStyle.Fill};
        profile.Items.AddRange(new object[]{"33","34","35","36"});
        var current=protocol.SelectedItem?.ToString();
        profile.SelectedIndex=Math.Max(0,Array.IndexOf(new[]{"33","34","35","36"},current));
        var discover=new CheckBox{Text=J1979RecipeText("NetworkDiscoverOnly"),Checked=true,AutoSize=true};
        var sources=new TextBox{Dock=DockStyle.Fill,MaxLength=287,CharacterCasing=CharacterCasing.Upper};
        var vin=new TextBox{Dock=DockStyle.Fill,MaxLength=17,CharacterCasing=CharacterCasing.Upper};
        var confirm=new CheckBox{Text=J1979RecipeText("NetworkConfirm"),AutoSize=true};
        var run=Button(J1979RecipeText("NetworkRun"));
        var cancel=Button(J1979RecipeText("LegacyCancel"));cancel.DialogResult=DialogResult.Cancel;
        var grid=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=2,RowCount=6,Padding=new Padding(12)};
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,42));grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,58));
        foreach(int h in new[]{35,35,35,35,55,45})grid.RowStyles.Add(new RowStyle(SizeType.Absolute,h));
        void Field(string key,Control input,int row){
            grid.Controls.Add(new Label{Text=J1979RecipeText(key),Dock=DockStyle.Fill},0,row);grid.Controls.Add(input,1,row);
        }
        Field("NetworkProfile",profile,0);Field("NetworkSources",sources,1);Field("LegacyOptionalVin",vin,2);
        grid.Controls.Add(discover,0,3);grid.SetColumnSpan(discover,2);
        grid.Controls.Add(confirm,0,4);grid.SetColumnSpan(confirm,2);
        var buttons=new FlowLayoutPanel{Dock=DockStyle.Fill};buttons.Controls.Add(run);buttons.Controls.Add(cancel);
        grid.Controls.Add(buttons,0,5);grid.SetColumnSpan(buttons,2);
        ObdJ1979CanNetworkCli.Options? Candidate(){
            var args=new List<string>{ObdJ1979CanNetworkCli.Command,"--adapter-index",
                adapter.SelectedIndex.ToString(CultureInfo.InvariantCulture),"--protocol",profile.SelectedItem?.ToString()??"",
                "--output","gui.json","--confirm-functional-pid00"};
            if(discover.Checked)args.Add("--discover-only");
            else {
                foreach(var item in sources.Text.Split(',',StringSplitOptions.TrimEntries|StringSplitOptions.RemoveEmptyEntries)){
                    args.Add("--source");args.Add(item);
                }
                args.Add("--confirm-physical-channels");
                if(vin.Text.Length>0){args.Add("--vin");args.Add(vin.Text);}
            }
            try{return ObdJ1979CanNetworkCli.Parse(args.ToArray());}catch(ArgumentException){return null;}
        }
        void Refresh(){
            sources.Enabled=vin.Enabled=!discover.Checked;
            run.Enabled=confirm.Checked&&Candidate() is not null;
        }
        discover.CheckedChanged+=(_,_)=>{confirm.Checked=false;Refresh();};
        profile.SelectedIndexChanged+=(_,_)=>{confirm.Checked=false;Refresh();};
        sources.TextChanged+=(_,_)=>{confirm.Checked=false;Refresh();};
        vin.TextChanged+=(_,_)=>{confirm.Checked=false;Refresh();};
        confirm.CheckedChanged+=(_,_)=>Refresh();
        ObdJ1979CanNetworkCli.Options? chosen=null;
        run.Click+=(_,_)=>{if(confirm.Checked&&(chosen=Candidate()) is not null)dialog.DialogResult=DialogResult.OK;};
        dialog.Controls.Add(grid);dialog.CancelButton=cancel;Refresh();
        return dialog.ShowDialog(this)==DialogResult.OK?chosen:null;
    }

    private async Task RunWindowsNetworkAsync()
    {
        if(functionalCancellation is not null||DateTimeOffset.UtcNow>=functionalAuthorizedUntil)return;
        if(busy||bridgeTask is {IsCompleted:false}||consoleDevice is not null||adapter.SelectedItem is not AdapterChoice selected){
            ShowError(J1979RecipeText("Prerequisites"));return;
        }
        var options=ChooseNetworkOptions();
        if(options is null||busy||DateTimeOffset.UtcNow>=functionalAuthorizedUntil||adapter.SelectedItem is not AdapterChoice current||
            !ReferenceEquals(selected,current))return;
        var expiry=functionalAuthorizedUntil;
        using var cancellation=new CancellationTokenSource();
        functionalCancellation=cancellation;busy=true;
        completedNetwork = null; completedLive = null;completedJ1979=null;completedEvap=null;completedClear=null;completedLegacy=null;
        completedFunctionalChecks=[];
        functionalRunLive.Enabled = functionalRunNetwork.Enabled =functionalRunNativeUsb.Enabled=functionalRunAll.Enabled=functionalRunJ1979.Enabled=
            functionalRunEvap.Enabled=functionalRunClear.Enabled=functionalRunLegacy.Enabled=functionalRunLegacyMutation.Enabled=
            functionalGoogleSignIn.Enabled=functionalExport.Enabled=false;
        functionalCancel.Enabled=true;functionalResults.Text=J1979RecipeText("Running");
        void RequireActive(){
            cancellation.Token.ThrowIfCancellationRequested();
            if(DateTimeOffset.UtcNow>=expiry||functionalAuthorizedUntil!=expiry)throw new OperationCanceledException();
        }
        try {
            var report=await Task.Run(()=>ObdJ1979CanNetworkCli.ExecuteAsync(selected.Adapter,options,cancellation.Token,RequireActive));
            if(DateTimeOffset.UtcNow>=expiry||functionalAuthorizedUntil!=expiry)return;
            if(cancellation.IsCancellationRequested&&report.Completed)report=report with{Error="CANCELLED"};
            completedNetwork=report;networkReportAuthorization=expiry;
            var output=new StringBuilder();
            output.AppendLine(J1979RecipeText(report.Completed?"Completed":"Partial"));
            if(report.Error is not null)output.AppendLine(report.Error);
            foreach(var row in report.Discovery?.Responders??[]){
                output.AppendLine($"{row.RxCanId} [{row.Status}] {row.Detail}");
                if(row.SupportedPids is not null)output.AppendLine(string.Join(", ",row.SupportedPids.Select(x=>x.ToString("X2"))));
            }
            foreach(var row in report.Network?.EcuReports??[]){
                output.AppendLine($"{row.TxCanId} → {row.RxCanId} [{row.Status}] {row.Error}");
                if(row.Report is not {} result)continue;
                output.AppendLine("VIN: "+(result.ObservedVin??J1979RecipeText("Unavailable")));
                foreach(var mode in result.DtcModes)output.AppendLine(string.Format(CultureInfo.CurrentUICulture,
                    J1979RecipeText("ScanDtcMode"),mode.Mode.ToString("X2"),mode.Codes is null?J1979RecipeText("Unavailable"):
                        mode.Codes.Count==0?J1979RecipeText("NativeUsbZeroDtc"):string.Join(", ",mode.Codes)));
                foreach(var value in result.Measurements)output.AppendLine($"{value.Mode:X2}/{value.Pid:X2}: {value.Number} {value.Unit}");
                foreach(var step in result.Steps)output.AppendLine($"{step.RequestHex} [{step.Status}] {step.ResponseHex}");
            }
            output.AppendLine(J1979RecipeText("NetworkScope"));
            functionalResults.Text=output.ToString();functionalExport.Enabled=true;
        }catch(OperationCanceledException){functionalResults.Text=J1979RecipeText("Cancelled");}
        catch(Exception e){functionalResults.Text=J1979RecipeText("Failed")+" "+e.GetType().Name;}
        finally{
            functionalCancellation=null;busy=false;functionalCancel.Enabled=false;functionalGoogleSignIn.Enabled=true;
            functionalRunLive.Enabled = functionalRunNetwork.Enabled =functionalRunNativeUsb.Enabled=functionalRunAll.Enabled=functionalRunJ1979.Enabled=
                functionalRunEvap.Enabled=functionalRunClear.Enabled=functionalRunLegacy.Enabled=functionalRunLegacyMutation.Enabled=
                    DateTimeOffset.UtcNow<functionalAuthorizedUntil;
        }
    }

    private void ExportWindowsNetwork(ObdJ1979CanNetworkCli.Report report)
    {
        using var dialog=new SaveFileDialog{Filter=J1979RecipeText("JsonFilter"),FileName="CamaroDiag_J1979_Network.json",
            AddExtension=true,DefaultExt="json",OverwritePrompt=true};
        if(dialog.ShowDialog(this)!=DialogResult.OK||DateTimeOffset.UtcNow>=networkReportAuthorization||
            functionalAuthorizedUntil!=networkReportAuthorization||!ReferenceEquals(completedNetwork,report))return;
        try{ObdJ1979CanNetworkCli.WriteNew(dialog.FileName,report);}
        catch(Exception){ShowError(J1979RecipeText("ExportFailed"));}
    }
}
