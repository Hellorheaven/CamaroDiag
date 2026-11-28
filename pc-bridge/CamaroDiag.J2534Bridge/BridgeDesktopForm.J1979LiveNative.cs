using System.Drawing;
using System.Globalization;
using System.IO.Ports;
using System.Text;
using System.Windows.Forms;
using CamaroDiagConnectors.Windows;

namespace CamaroDiag.J2534Bridge;

internal sealed partial class BridgeDesktopForm
{
    private readonly Button functionalRunNativeLive=Button(J1979RecipeText("LiveNativeRun"));
    private readonly Button functionalInspectNativeLive=Button(J1979RecipeText("LiveNativeInspect"));
    private readonly Button functionalExportNativeLiveCsv=Button(J1979RecipeText("LiveNativeCsv"));
    private readonly Button functionalProjectNativeLive=Button(J1979RecipeText("LiveNativeScalar"));
    private readonly Button functionalExportNativeScalarCsv=Button(J1979RecipeText("LiveNativeScalarCsv"));
    private readonly Button functionalVerifyNativeScalar=Button(J1979RecipeText("LiveNativeScalarVerify"));
    private readonly Button functionalInspectNativePrefix=Button(J1979RecipeText("LiveNativePrefix"));

    private J1979LiveNativeCli.Options? ChooseNativeLiveOptions()
    {
        if(!TryNativeUsbId(requestId.Text,out uint tx)||!TryNativeUsbId(responseId.Text,out uint rx))
        {ShowError(J1979RecipeText("InvalidChannel"));return null;}
        bool extended=tx>0x7ff||rx>0x7ff;
        if(!J1979UsbAuth.PhysicalPair(tx,rx,extended))
        {ShowError(J1979RecipeText("InvalidChannel"));return null;}
        string[] ports=SerialPort.GetPortNames().OrderBy(x=>x,StringComparer.OrdinalIgnoreCase).ToArray();
        if(ports.Length==0){ShowError(J1979RecipeText("NativeUsbNoPorts"));return null;}
        using var dialog=new Form{
            Text=J1979RecipeText("LiveNativeRun"),StartPosition=FormStartPosition.CenterParent,
            ClientSize=new Size(650,450),FormBorderStyle=FormBorderStyle.FixedDialog,
            MaximizeBox=false,MinimizeBox=false};
        var port=new ComboBox{Dock=DockStyle.Fill,DropDownStyle=ComboBoxStyle.DropDownList};
        port.Items.AddRange(ports);
        var vin=new TextBox{Text=functionalEvapVin.Text.Trim().ToUpperInvariant(),Dock=DockStyle.Fill,MaxLength=17,CharacterCasing=CharacterCasing.Upper};
        var pids=new TextBox{Text="05,0C,0D",Dock=DockStyle.Fill,MaxLength=95,CharacterCasing=CharacterCasing.Upper};
        var cycles=new NumericUpDown{Minimum=1,Maximum=1000,Value=30,Dock=DockStyle.Fill};
        var interval=new NumericUpDown{Minimum=100,Maximum=60000,Value=1000,Dock=DockStyle.Fill};
        var budget=new NumericUpDown{Minimum=1,Maximum=600,Value=180,Dock=DockStyle.Fill};
        var key=Button(J1979RecipeText("NativeUsbKey"));
        var folder=Button(J1979RecipeText("NativeUsbFolder"));
        string? keyPath=null,directory=null;
        var confirm=new CheckBox{Text=J1979RecipeText("LiveNativeConfirm"),AutoSize=true};
        var run=Button(J1979RecipeText("LiveNativeRun"));
        var cancel=Button(J1979RecipeText("LegacyCancel"));cancel.DialogResult=DialogResult.Cancel;
        var grid=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=2,RowCount=11,Padding=new Padding(12)};
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,42));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,58));
        foreach(int height in new[]{32,32,32,32,32,32,32,36,36,50,45})
            grid.RowStyles.Add(new RowStyle(SizeType.Absolute,height));
        void Field(string name,Control input,int row)
        {
            grid.Controls.Add(new Label{Text=J1979RecipeText(name),Dock=DockStyle.Fill},0,row);
            grid.Controls.Add(input,1,row);
        }
        Field("NativeUsbPort",port,0);
        Field("LiveChannel",new Label{Text=$"{tx:X} → {rx:X}",Dock=DockStyle.Fill},1);
        Field("LiveVin",vin,2);Field("LiveNativePids",pids,3);Field("LiveCycles",cycles,4);
        Field("LiveInterval",interval,5);Field("LiveBudget",budget,6);Field("NativeUsbKey",key,7);Field("NativeUsbFolder",folder,8);
        grid.Controls.Add(confirm,0,9);grid.SetColumnSpan(confirm,2);
        var buttons=new FlowLayoutPanel{Dock=DockStyle.Fill};
        buttons.Controls.Add(run);buttons.Controls.Add(cancel);grid.Controls.Add(buttons,0,10);grid.SetColumnSpan(buttons,2);
        J1979LiveNative.Profile? Profile()
        {
            var entries=pids.Text.Split(',',StringSplitOptions.TrimEntries);
            if(entries.Length is <1 or >32)return null;
            var ids=new int[entries.Length];
            for(int i=0;i<entries.Length;i++)
            {
                if(entries[i].Length!=2||!byte.TryParse(entries[i],NumberStyles.AllowHexSpecifier,
                    CultureInfo.InvariantCulture,out byte value))return null;
                ids[i]=value;
            }
            try{return new J1979LiveNative.Profile(tx,rx,extended,vin.Text,ids,
                (int)cycles.Value,(int)interval.Value,(int)budget.Value*1000).Validate();}
            catch(InvalidDataException){return null;}
        }
        void Refresh()=>run.Enabled=confirm.Checked&&port.SelectedItem is string&&
            keyPath is not null&&directory is not null&&Profile() is not null;
        void Changed(){confirm.Checked=false;Refresh();}
        port.SelectedIndexChanged+=(_,_)=>Changed();vin.TextChanged+=(_,_)=>Changed();
        pids.TextChanged+=(_,_)=>Changed();cycles.ValueChanged+=(_,_)=>Changed();interval.ValueChanged+=(_,_)=>Changed();
        budget.ValueChanged+=(_,_)=>Changed();confirm.CheckedChanged+=(_,_)=>Refresh();
        key.Click+=(_,_)=>{
            using var picker=new OpenFileDialog{Title=J1979RecipeText("NativeUsbKey"),
                CheckFileExists=true,Multiselect=false,Filter=J1979RecipeText("NativeUsbKeyFilter")};
            if(picker.ShowDialog(dialog)==DialogResult.OK)
            {keyPath=picker.FileName;key.Text=J1979RecipeText("FreezeNativeSelected");Changed();}
        };
        folder.Click+=(_,_)=>{
            using var picker=new FolderBrowserDialog{Description=J1979RecipeText("NativeUsbFolder"),UseDescriptionForTitle=true};
            if(picker.ShowDialog(dialog)==DialogResult.OK)
            {directory=picker.SelectedPath;folder.Text=J1979RecipeText("FreezeNativeSelected");Changed();}
        };
        J1979LiveNativeCli.Options? chosen=null;
        run.Click+=(_,_)=>{
            if(!confirm.Checked||port.SelectedItem is not string name||keyPath is null||directory is null||
                Profile() is not {} profile)return;
            string stem="CamaroDiag_SAE-J1979_LIVE_"+Guid.NewGuid().ToString("N");
            chosen=new(name,keyPath,Path.Combine(directory,stem+".cl"),Path.Combine(directory,stem+".json"),profile);
            dialog.DialogResult=DialogResult.OK;
        };
        dialog.Controls.Add(grid);dialog.CancelButton=cancel;Refresh();
        return dialog.ShowDialog(this)==DialogResult.OK?chosen:null;
    }

    private async Task RunWindowsNativeLiveAsync()
    {
        if(functionalCancellation is not null||DateTimeOffset.UtcNow>=functionalAuthorizedUntil)return;
        if(busy||bridgeTask is {IsCompleted:false}||consoleDevice is not null)
        {ShowError(J1979RecipeText("NativeUsbPrerequisites"));return;}
        var options=ChooseNativeLiveOptions();
        if(options is null||busy||functionalCancellation is not null||DateTimeOffset.UtcNow>=functionalAuthorizedUntil||
           bridgeTask is {IsCompleted:false}||consoleDevice is not null||
           !SerialPort.GetPortNames().Contains(options.Port,StringComparer.OrdinalIgnoreCase))return;
        var expiry=functionalAuthorizedUntil;
        using var cancel=new CancellationTokenSource();
        functionalCancellation=cancel;busy=true;
        completedJ1979=null;completedNetwork=null;completedLive=null;completedFreeze=null;
        completedEvap=null;completedClear=null;completedLegacy=null;completedFunctionalChecks=[];
        functionalRunLive.Enabled=functionalRunNetwork.Enabled=functionalRunNativeUsb.Enabled=
            functionalRunAll.Enabled=functionalRunJ1979.Enabled=functionalRunEvap.Enabled=
            functionalRunClear.Enabled=functionalRunLegacy.Enabled=functionalRunLegacyMutation.Enabled=
            functionalGoogleSignIn.Enabled=functionalExport.Enabled=false;
        functionalCancel.Enabled=true;functionalResults.Text=J1979RecipeText("Running");
        bool Current()=>!cancel.IsCancellationRequested&&DateTimeOffset.UtcNow<expiry&&functionalAuthorizedUntil==expiry;
        try
        {
            var report=await Task.Run(()=>J1979LiveNativeCli.Capture(options,Current));
            if(IsDisposed||Disposing||DateTimeOffset.UtcNow>=expiry||functionalAuthorizedUntil!=expiry)return;
            functionalResults.Text=FormatNativeLive(report,options.CapturePath,options.OutputPath);
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

    private Task InspectWindowsNativeLiveAsync()=>InspectWindowsNativeLiveAsync(false,false);
    private Task ExportWindowsNativeLiveCsvAsync()=>InspectWindowsNativeLiveAsync(true,false);
    private Task InspectWindowsNativePrefixAsync()=>InspectWindowsNativeLiveAsync(false,true);

    private Task ProjectWindowsNativeLiveAsync()=>InspectWindowsNativeLiveAsync(false,false,true);
    private Task ExportWindowsNativeScalarCsvAsync()=>InspectWindowsNativeLiveAsync(true,false,true);

    private async Task InspectWindowsNativeLiveAsync(bool csv,bool prefix,bool scalar=false)
    {
        if(busy||functionalCancellation is not null||DateTimeOffset.UtcNow>=functionalAuthorizedUntil)return;
        var expiry=functionalAuthorizedUntil;
        string vin=functionalEvapVin.Text.Trim().ToUpperInvariant();
        if(!J1979LiveNative.ValidVin(vin)){ShowError(J1979RecipeText("LiveNativeVinRequired"));return;}
        using var source=new OpenFileDialog{Title=J1979RecipeText(scalar?(csv?"LiveNativeScalarCsv":"LiveNativeScalar"):prefix?"LiveNativePrefix":csv?"LiveNativeCsv":"LiveNativeInspect"),CheckFileExists=true,
            Multiselect=false,Filter=J1979RecipeText("LiveNativeCaptureFilter")};
        if(source.ShowDialog(this)!=DialogResult.OK)return;
        using var destination=new SaveFileDialog{Filter=J1979RecipeText(csv?"LiveNativeCsvFilter":"JsonFilter"),
            DefaultExt=csv?"csv":"json",AddExtension=true,OverwritePrompt=false,
            FileName=scalar?(csv?"CamaroDiag_CL1_Values.csv":"CamaroDiag_CL1_Projection.json"):prefix?"CamaroDiag_CL1_Prefix.json":csv?"CamaroDiag_CL1_Samples.csv":"CamaroDiag_CL1_Inspection.json"};
        if(destination.ShowDialog(this)!=DialogResult.OK||busy||DateTimeOffset.UtcNow>=expiry||
            functionalAuthorizedUntil!=expiry||functionalEvapVin.Text.Trim().ToUpperInvariant()!=vin)return;
        busy=true;completedFreeze=null;functionalExport.Enabled=false;
        functionalRunLive.Enabled=functionalRunNetwork.Enabled=functionalRunNativeUsb.Enabled=
            functionalRunAll.Enabled=functionalRunJ1979.Enabled=functionalRunEvap.Enabled=
            functionalRunClear.Enabled=functionalRunLegacy.Enabled=functionalRunLegacyMutation.Enabled=
            functionalGoogleSignIn.Enabled=false;
        functionalResults.Text=J1979RecipeText("Running");
        try
        {
            bool Current()=>DateTimeOffset.UtcNow<expiry&&functionalAuthorizedUntil==expiry;
            if(scalar)
            {
                var projection=await Task.Run(()=>J1979LiveNativeCli.ExportScalar(source.FileName,vin,destination.FileName,csv,Current));
                if(!IsDisposed&&!Disposing&&Current())functionalResults.Text=FormatNativeScalar(projection,destination.FileName);
                return;
            }
            if(prefix)
            {
                var partial=await Task.Run(()=>J1979LiveNativeCli.InspectPrefix(source.FileName,vin,destination.FileName,Current));
                if(!IsDisposed&&!Disposing&&Current())
                    functionalResults.Text=FormatNativePrefix(partial,source.FileName,destination.FileName);
                return;
            }
            var report=await Task.Run(()=>csv?
                J1979LiveNativeCli.ExportCsv(source.FileName,vin,destination.FileName,Current):
                J1979LiveNativeCli.Inspect(source.FileName,vin,destination.FileName,Current));
            if(!IsDisposed&&!Disposing&&DateTimeOffset.UtcNow<expiry&&functionalAuthorizedUntil==expiry)
                functionalResults.Text=FormatNativeLive(report,source.FileName,destination.FileName);
        }
        catch(Exception){if(!IsDisposed&&!Disposing)functionalResults.Text=J1979RecipeText("Failed");}
        finally
        {
            busy=false;
            if(!IsDisposed&&!Disposing)
            {
                functionalGoogleSignIn.Enabled=true;
                functionalRunLive.Enabled=functionalRunNetwork.Enabled=functionalRunNativeUsb.Enabled=
                    functionalRunAll.Enabled=functionalRunJ1979.Enabled=functionalRunEvap.Enabled=
                    functionalRunClear.Enabled=functionalRunLegacy.Enabled=functionalRunLegacyMutation.Enabled=
                    DateTimeOffset.UtcNow<functionalAuthorizedUntil;
            }
        }
    }

    private async Task VerifyWindowsNativeScalarAsync()
    {
        if(busy||functionalCancellation is not null||DateTimeOffset.UtcNow>=functionalAuthorizedUntil)return;
        var expiry=functionalAuthorizedUntil;
        string vin=functionalEvapVin.Text.Trim().ToUpperInvariant();
        if(!J1979LiveNative.ValidVin(vin)){ShowError(J1979RecipeText("LiveNativeVinRequired"));return;}
        using var capture=new OpenFileDialog{Title=J1979RecipeText("LiveNativeInspect"),
            CheckFileExists=true,Multiselect=false,Filter=J1979RecipeText("LiveNativeCaptureFilter")};
        if(capture.ShowDialog(this)!=DialogResult.OK)return;
        using var report=new OpenFileDialog{Title=J1979RecipeText("LiveNativeScalar"),CheckFileExists=true,
            Multiselect=false,Filter=J1979RecipeText("JsonFilter")};
        if(report.ShowDialog(this)!=DialogResult.OK)return;
        if(MessageBox.Show(this,string.Format(CultureInfo.CurrentCulture,
            J1979RecipeText("LiveNativeScalarVerifyConfirm"),vin),J1979RecipeText("LiveNativeScalarVerify"),
            MessageBoxButtons.YesNo,MessageBoxIcon.Question)!=DialogResult.Yes)return;
        using var proof=new SaveFileDialog{Filter=J1979RecipeText("JsonFilter"),DefaultExt="json",
            AddExtension=true,OverwritePrompt=false,FileName="CamaroDiag_CL1_Scalar_Verification.json"};
        if(proof.ShowDialog(this)!=DialogResult.OK||busy||functionalCancellation is not null||
            DateTimeOffset.UtcNow>=expiry||functionalAuthorizedUntil!=expiry||
            functionalEvapVin.Text.Trim().ToUpperInvariant()!=vin)return;
        using var cancellation=new CancellationTokenSource();
        functionalCancellation=cancellation;busy=true;
        functionalRunLive.Enabled=functionalRunNetwork.Enabled=functionalRunNativeUsb.Enabled=
            functionalRunAll.Enabled=functionalRunJ1979.Enabled=functionalRunEvap.Enabled=
            functionalRunClear.Enabled=functionalRunLegacy.Enabled=functionalRunLegacyMutation.Enabled=
            functionalGoogleSignIn.Enabled=functionalExport.Enabled=false;
        functionalCancel.Enabled=true;functionalResults.Text=J1979RecipeText("Running");
        bool Current()=>!cancellation.IsCancellationRequested&&DateTimeOffset.UtcNow<expiry&&
            functionalAuthorizedUntil==expiry;
        try
        {
            var projected=await Task.Run(()=>J1979LiveNativeCli.VerifyScalar(capture.FileName,vin,
                report.FileName,proof.FileName,Current));
            if(!IsDisposed&&!Disposing&&Current())
                functionalResults.Text=J1979RecipeText("LiveNativeScalarVerified")+Environment.NewLine+
                    FormatNativeScalar(projected,proof.FileName);
        }
        catch(OperationCanceledException){if(!IsDisposed&&!Disposing)functionalResults.Text=J1979RecipeText("Cancelled");}
        catch(Exception){if(!IsDisposed&&!Disposing)functionalResults.Text=J1979RecipeText("LiveNativeScalarRejected");}
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

    private static string FormatNativeScalar(J1979LiveScalarProjection.Projection projection,string path)
    {
        var text=new StringBuilder(J1979RecipeText(projection.NumericProjectionComplete?"Completed":"Partial")).AppendLine();
        text.AppendLine(J1979RecipeText("LiveNativeScalarScope"));
        if(!projection.IdentityRechecked)text.AppendLine(J1979RecipeText("LiveNativeScalarIdentity"));
        string Display(double? value)=>value?.ToString("0.###",CultureInfo.CurrentCulture)??"—";
        foreach(var channel in projection.Channels)
        {
            text.AppendLine($"{channel.Pid:X2} · {channel.Name} · {channel.Count}/{channel.Requested}");
            text.AppendLine($"Min {Display(channel.Minimum)} · Max {Display(channel.Maximum)} · {J1979RecipeText("LiveNativeScalarMean")} {Display(channel.Mean)} {channel.Unit}");
            if(channel.Missing>0)text.AppendLine(string.Join(" · ",channel.Outcomes.Select(o=>$"{o.Key}: {o.Value}")));
        }
        text.AppendLine(J1979RecipeText("NativeUsbProvenance")).AppendLine(path);
        return text.ToString();
    }

    private static string FormatNativePrefix(Dictionary<string,object?> report,string capture,string json)
    {
        var text=new StringBuilder(J1979RecipeText("Partial")).AppendLine();
        text.AppendLine(J1979RecipeText("LiveNativePrefixScope"));
        text.AppendLine(string.Format(CultureInfo.CurrentCulture,J1979RecipeText("LiveNativePrefixRecords"),report["complete_records"]));
        text.AppendLine(string.Format(CultureInfo.CurrentCulture,J1979RecipeText("LiveNativePrefixSamples"),report["raw_samples_in_prefix"]));
        text.AppendLine(string.Format(CultureInfo.CurrentCulture,J1979RecipeText("LiveNativePrefixTail"),report["discarded_tail_bytes"]));
        text.AppendLine(J1979RecipeText("NativeUsbProvenance"));
        text.AppendLine(capture);text.AppendLine(json);
        return text.ToString();
    }

    private static string FormatNativeLive(J1979LiveNative.Report report,string capture,string json)
    {
        bool complete=report.CaptureIntegrityVerified&&!report.Cancelled&&report.Summary?.Completed==true;
        var text=new StringBuilder(J1979RecipeText(complete?"Completed":"Partial")).AppendLine();
        if(report.Source=="OFFLINE_CL1")text.AppendLine(J1979RecipeText("FreezeNativeOffline"));
        if(report.CaptureIntegrityVerified&&report.Summary is {} summary&&report.Profile is {} p)
        {
            text.AppendLine(p.ExpectedVin);
            text.AppendLine($"{p.RequestCanId} → {p.ResponseCanId}");
            text.AppendLine(string.Format(J1979RecipeText("LiveCycleCount"),summary.CompletedCycles,p.RequestedCycles));
            foreach(var row in summary.RawPerPid)
                text.AppendLine(string.Format(J1979RecipeText("LiveRawCount"),row.Pid.ToString("X2"),row.Count,p.RequestedCycles));
            text.AppendLine(J1979RecipeText("LiveRawOnly"));
            if(summary.StopReason is not null)text.AppendLine(summary.StopReason);
        }
        else if(report.Error is not null)text.AppendLine(report.Error);
        text.AppendLine(J1979RecipeText("NativeUsbProvenance"));
        text.AppendLine(capture);text.AppendLine(json);
        return text.ToString();
    }
}
