using System.Drawing;
using System.Globalization;
using System.Text;
using System.Windows.Forms;

namespace CamaroDiag.J2534Bridge;

internal sealed partial class BridgeDesktopForm
{
    private ObdJ1979LiveCli.Options? ChooseLiveOptions(){
        using var dialog=new Form{Text=J1979RecipeText("LiveRun"),StartPosition=FormStartPosition.CenterParent,
            ClientSize=new Size(560,370),FormBorderStyle=FormBorderStyle.FixedDialog,MaximizeBox=false,MinimizeBox=false};
        var pids=new TextBox{Text="05,0C",Dock=DockStyle.Fill,CharacterCasing=CharacterCasing.Upper,MaxLength=95};
        var vin=new TextBox{Text=functionalEvapVin.Text.Trim(),MaxLength=17,Dock=DockStyle.Fill,CharacterCasing=CharacterCasing.Upper};
        var cycles=new NumericUpDown{Minimum=1,Maximum=1000,Value=30,Dock=DockStyle.Fill};
        var interval=new NumericUpDown{Minimum=100,Maximum=60000,Value=1000,Increment=100,Dock=DockStyle.Fill};
        var budget=new NumericUpDown{Minimum=1,Maximum=600,Value=180,Dock=DockStyle.Fill};
        var confirm=new CheckBox{Text=J1979RecipeText("LiveConfirm"),AutoSize=true};
        var run=Button(J1979RecipeText("LiveRun"));
        var cancel=Button(J1979RecipeText("LegacyCancel"));cancel.DialogResult=DialogResult.Cancel;
        var grid=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=2,RowCount=8,Padding=new Padding(12)};
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,42));grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,58));
        foreach(var height in new[]{32,32,32,32,32,32,58,45})grid.RowStyles.Add(new RowStyle(SizeType.Absolute,height));
        void Field(string key,Control control,int row){
            grid.Controls.Add(new Label{Text=J1979RecipeText(key),Dock=DockStyle.Fill},0,row);grid.Controls.Add(control,1,row);
        }
        var channel=new Label{Text=$"{requestId.Text.Trim()} → {responseId.Text.Trim()} · {protocol.SelectedItem}",Dock=DockStyle.Fill};
        Field("LiveChannel",channel,0);Field("LivePids",pids,1);Field("LiveVin",vin,2);
        Field("LiveCycles",cycles,3);Field("LiveInterval",interval,4);Field("LiveBudget",budget,5);
        grid.Controls.Add(confirm,0,6);grid.SetColumnSpan(confirm,2);
        var buttons=new FlowLayoutPanel{Dock=DockStyle.Fill};buttons.Controls.Add(run);buttons.Controls.Add(cancel);
        grid.Controls.Add(buttons,0,7);grid.SetColumnSpan(buttons,2);
        ObdJ1979LiveCli.Options? Candidate(){
            var args=new List<string>{ObdJ1979LiveCli.Command,"--adapter-index",adapter.SelectedIndex.ToString(CultureInfo.InvariantCulture),
                "--protocol",protocol.SelectedItem?.ToString()??"","--tx",requestId.Text.Trim(),"--rx",responseId.Text.Trim(),
                "--vin",vin.Text,"--cycles",cycles.Value.ToString(CultureInfo.InvariantCulture),
                "--interval-ms",interval.Value.ToString(CultureInfo.InvariantCulture),
                "--budget-seconds",budget.Value.ToString(CultureInfo.InvariantCulture),"--confirm-physical-channel","--output","gui.json"};
            foreach(var pid in pids.Text.Split(',',StringSplitOptions.TrimEntries|StringSplitOptions.RemoveEmptyEntries)){
                args.Add("--pid");args.Add(pid);
            }
            try{return ObdJ1979LiveCli.Parse(args.ToArray());}catch(ArgumentException){return null;}
        }
        void Refresh()=>run.Enabled=confirm.Checked&&Candidate() is not null;
        void Changed(){confirm.Checked=false;Refresh();}
        pids.TextChanged+=(_,_)=>Changed();vin.TextChanged+=(_,_)=>Changed();
        cycles.ValueChanged+=(_,_)=>Changed();interval.ValueChanged+=(_,_)=>Changed();budget.ValueChanged+=(_,_)=>Changed();
        confirm.CheckedChanged+=(_,_)=>Refresh();
        ObdJ1979LiveCli.Options? chosen=null;
        run.Click+=(_,_)=>{if(confirm.Checked&&(chosen=Candidate()) is not null)dialog.DialogResult=DialogResult.OK;};
        dialog.Controls.Add(grid);dialog.CancelButton=cancel;Refresh();
        return dialog.ShowDialog(this)==DialogResult.OK?chosen:null;
    }
    private async Task RunWindowsLiveAsync(){
        if(functionalCancellation is not null||DateTimeOffset.UtcNow>=functionalAuthorizedUntil)return;
        if(busy||bridgeTask is {IsCompleted:false}||consoleDevice is not null||
            adapter.SelectedItem is not AdapterChoice selected){
            ShowError(J1979RecipeText("Prerequisites"));return;
        }
        var options=ChooseLiveOptions();
        if(options is null||busy||DateTimeOffset.UtcNow>=functionalAuthorizedUntil||
            adapter.SelectedItem is not AdapterChoice current||!ReferenceEquals(selected,current))return;
        var expiry=functionalAuthorizedUntil;
        using var cancel=new CancellationTokenSource();
        functionalCancellation=cancel;busy=true;
        completedLive=null;completedNetwork=null;completedJ1979=null;completedEvap=null;completedClear=null;completedLegacy=null;
        completedFunctionalChecks=[];
        functionalRunLive.Enabled=functionalRunNetwork.Enabled=functionalRunNativeUsb.Enabled=functionalRunAll.Enabled=
            functionalRunJ1979.Enabled=functionalRunEvap.Enabled=functionalRunClear.Enabled=functionalRunLegacy.Enabled=
            functionalRunLegacyMutation.Enabled=functionalGoogleSignIn.Enabled=functionalExport.Enabled=false;
        functionalCancel.Enabled=true;functionalResults.Text=J1979RecipeText("Running");
        void RequireActive(){
            cancel.Token.ThrowIfCancellationRequested();
            if(DateTimeOffset.UtcNow>=expiry||functionalAuthorizedUntil!=expiry)throw new OperationCanceledException();
        }
        // Throttle UI publication. The worker retains all observations; the
        // progress area shows only the latest 40 rows, not a second log buffer.
        var progress=new Progress<ObdJ1979LiveRecorder.Observation>(row=>{
            if(functionalCancellation!=cancel||functionalAuthorizedUntil!=expiry||DateTimeOffset.UtcNow>=expiry)return;
            var line=$"{row.ElapsedMs} ms · {row.Pid:X2} · "+
                (row.Measurement is {} v?$"{v.Number.ToString(CultureInfo.CurrentCulture)} {v.Unit}":row.Status);
            var lines=functionalResults.Lines.TakeLast(39).Append(line).ToArray();
            functionalResults.Lines=lines;
        });
        long lastUpdate=-1000;
        try{
            var report=await Task.Run(()=>ObdJ1979LiveCli.Execute(selected.Adapter,options,RequireActive,row=>{
                if(row.ElapsedMs-lastUpdate>=150){lastUpdate=row.ElapsedMs;((IProgress<ObdJ1979LiveRecorder.Observation>)progress).Report(row);}
            }));
            if(DateTimeOffset.UtcNow>=expiry||functionalAuthorizedUntil!=expiry)return;
            if(cancel.IsCancellationRequested&&report.Completed)report=report with{Error="CANCELLED"};
            completedLive=report;liveReportAuthorization=expiry;
            var output=new StringBuilder();
            output.AppendLine(J1979RecipeText(report.Completed?"Completed":"Partial"));
            if(report.Error is not null)output.AppendLine(report.Error);
            if(report.Result is {} result){
                output.AppendLine(string.Format(CultureInfo.CurrentUICulture,J1979RecipeText("LiveCycleCount"),result.CompletedCycles,result.RequestedCycles));
                output.AppendLine("VIN: "+(result.ObservedVin??J1979RecipeText("Unavailable")));
                if(result.StopReason is not null)output.AppendLine(result.StopReason);
                foreach(var item in result.Summary)
                    output.AppendLine($"{item.Pid:X2} {item.Name} · n={item.StatusCounts.GetValueOrDefault("PASSED")} · "+
                        string.Format(CultureInfo.CurrentUICulture,J1979RecipeText("LiveSummary"),
                            item.Minimum?.ToString("0.###")??"—",item.Maximum?.ToString("0.###")??"—",
                            item.Mean?.ToString("0.###")??"—",item.Unit??""));
            }
            output.AppendLine(J1979RecipeText("LiveScope"));
            functionalResults.Text=output.ToString();functionalExport.Enabled=true;
        }catch(OperationCanceledException){functionalResults.Text=J1979RecipeText("Cancelled");}
        catch(Exception e){functionalResults.Text=J1979RecipeText("Failed")+" "+e.GetType().Name;}
        finally{
            functionalCancellation=null;busy=false;functionalCancel.Enabled=false;functionalGoogleSignIn.Enabled=true;
            functionalRunLive.Enabled=functionalRunNetwork.Enabled=functionalRunNativeUsb.Enabled=functionalRunAll.Enabled=
                functionalRunJ1979.Enabled=functionalRunEvap.Enabled=functionalRunClear.Enabled=functionalRunLegacy.Enabled=
                functionalRunLegacyMutation.Enabled=DateTimeOffset.UtcNow<functionalAuthorizedUntil;
        }
    }
    private void ExportWindowsLive(ObdJ1979LiveCli.Report report){
        using var dialog=new SaveFileDialog{Filter=J1979RecipeText("JsonFilter")+"|CSV (*.csv)|*.csv",
            FileName="CamaroDiag_J1979_Live.json",AddExtension=true,DefaultExt="json",OverwritePrompt=true};
        if(dialog.ShowDialog(this)!=DialogResult.OK||DateTimeOffset.UtcNow>=liveReportAuthorization||
            functionalAuthorizedUntil!=liveReportAuthorization||!ReferenceEquals(completedLive,report))return;
        try{ObdJ1979LiveCli.WriteNew(dialog.FileName,report,dialog.FilterIndex==2);}
        catch(Exception){ShowError(J1979RecipeText("ExportFailed"));}
    }
}
