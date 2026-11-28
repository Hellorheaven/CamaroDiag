using System.Drawing;
using System.Globalization;
using System.IO.Ports;
using System.Text;
using System.Windows.Forms;
using CamaroDiagConnectors.Windows;

namespace CamaroDiag.J2534Bridge;

internal sealed partial class BridgeDesktopForm
{
    private readonly Button functionalRunNativeFreeze=Button(J1979RecipeText("FreezeNativeRun"));
    private readonly Button functionalInspectNativeFreeze=Button(J1979RecipeText("FreezeNativeInspect"));

    private J1979FreezeNativeCli.Options? ChooseNativeFreezeOptions()
    {
        if(!TryNativeUsbId(requestId.Text,out uint tx)||!TryNativeUsbId(responseId.Text,out uint rx))
        {ShowError(J1979RecipeText("InvalidChannel"));return null;}
        bool extended=tx>0x7ff||rx>0x7ff;
        if(!J1979UsbAuth.PhysicalPair(tx,rx,extended))
        {ShowError(J1979RecipeText("InvalidChannel"));return null;}
        string[] ports=SerialPort.GetPortNames().OrderBy(x=>x,StringComparer.OrdinalIgnoreCase).ToArray();
        if(ports.Length==0){ShowError(J1979RecipeText("NativeUsbNoPorts"));return null;}
        using var dialog=new Form{
            Text=J1979RecipeText("FreezeNativeRun"),StartPosition=FormStartPosition.CenterParent,
            ClientSize=new Size(650,405),FormBorderStyle=FormBorderStyle.FixedDialog,
            MaximizeBox=false,MinimizeBox=false};
        var port=new ComboBox{Dock=DockStyle.Fill,DropDownStyle=ComboBoxStyle.DropDownList};
        port.Items.AddRange(ports);
        var vin=new TextBox{Text=functionalEvapVin.Text.Trim().ToUpperInvariant(),Dock=DockStyle.Fill,MaxLength=17,CharacterCasing=CharacterCasing.Upper};
        var frames=new TextBox{Text="00",Dock=DockStyle.Fill,MaxLength=23,CharacterCasing=CharacterCasing.Upper};
        var pids=new TextBox{Dock=DockStyle.Fill,MaxLength=191,CharacterCasing=CharacterCasing.Upper};
        var budget=new NumericUpDown{Minimum=1,Maximum=600,Value=180,Dock=DockStyle.Fill};
        var key=Button(J1979RecipeText("NativeUsbKey"));
        var folder=Button(J1979RecipeText("NativeUsbFolder"));
        string? keyPath=null,directory=null;
        var confirm=new CheckBox{Text=J1979RecipeText("FreezeConfirm"),AutoSize=true};
        var run=Button(J1979RecipeText("FreezeNativeRun"));
        var cancel=Button(J1979RecipeText("LegacyCancel"));cancel.DialogResult=DialogResult.Cancel;
        var grid=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=2,RowCount=10,Padding=new Padding(12)};
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,42));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,58));
        foreach(int height in new[]{32,32,32,32,32,32,36,36,50,45})
            grid.RowStyles.Add(new RowStyle(SizeType.Absolute,height));
        void Field(string name,Control input,int row)
        {
            grid.Controls.Add(new Label{Text=J1979RecipeText(name),Dock=DockStyle.Fill},0,row);
            grid.Controls.Add(input,1,row);
        }
        Field("NativeUsbPort",port,0);
        Field("LiveChannel",new Label{Text=$"{tx:X} → {rx:X}",Dock=DockStyle.Fill},1);
        Field("LiveVin",vin,2);Field("FreezeFrames",frames,3);Field("FreezePids",pids,4);
        Field("LiveBudget",budget,5);Field("NativeUsbKey",key,6);Field("NativeUsbFolder",folder,7);
        grid.Controls.Add(confirm,0,8);grid.SetColumnSpan(confirm,2);
        var buttons=new FlowLayoutPanel{Dock=DockStyle.Fill};
        buttons.Controls.Add(run);buttons.Controls.Add(cancel);grid.Controls.Add(buttons,0,9);grid.SetColumnSpan(buttons,2);
        J1979FreezeNative.Profile? Profile()
        {
            int[]? Parse(string input,int maximum,bool emptyAllowed)
            {
                if(string.IsNullOrWhiteSpace(input))return emptyAllowed?[]:null;
                var entries=input.Split(',',StringSplitOptions.TrimEntries);
                if(entries.Length>maximum)return null;
                var ids=new int[entries.Length];
                for(int i=0;i<entries.Length;i++)
                {
                    if(entries[i].Length is <1 or >2||!byte.TryParse(entries[i],NumberStyles.AllowHexSpecifier,
                        CultureInfo.InvariantCulture,out byte value))return null;
                    ids[i]=value;
                }
                return ids;
            }
            var selected=Parse(frames.Text,8,false);var measurements=Parse(pids.Text,64,true);
            if(selected is null||measurements is null)return null;
            try{return new J1979FreezeNative.Profile(tx,rx,extended,
                new(vin.Text,selected,measurements,(int)budget.Value*1000)).Validate();}
            catch(ArgumentException){return null;}
        }
        void Refresh()=>run.Enabled=confirm.Checked&&port.SelectedItem is string&&
            keyPath is not null&&directory is not null&&Profile() is not null;
        void Changed(){confirm.Checked=false;Refresh();}
        port.SelectedIndexChanged+=(_,_)=>Changed();vin.TextChanged+=(_,_)=>Changed();
        frames.TextChanged+=(_,_)=>Changed();pids.TextChanged+=(_,_)=>Changed();
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
        J1979FreezeNativeCli.Options? chosen=null;
        run.Click+=(_,_)=>{
            if(!confirm.Checked||port.SelectedItem is not string name||keyPath is null||directory is null||
                Profile() is not {} profile)return;
            string stem="CamaroDiag_SAE-J1979_FREEZE_"+Guid.NewGuid().ToString("N");
            chosen=new(name,keyPath,Path.Combine(directory,stem+".cf"),Path.Combine(directory,stem+".json"),profile);
            dialog.DialogResult=DialogResult.OK;
        };
        dialog.Controls.Add(grid);dialog.CancelButton=cancel;Refresh();
        return dialog.ShowDialog(this)==DialogResult.OK?chosen:null;
    }

    private async Task RunWindowsNativeFreezeAsync()
    {
        if(functionalCancellation is not null||DateTimeOffset.UtcNow>=functionalAuthorizedUntil)return;
        if(busy||bridgeTask is {IsCompleted:false}||consoleDevice is not null)
        {ShowError(J1979RecipeText("NativeUsbPrerequisites"));return;}
        var options=ChooseNativeFreezeOptions();
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
            var report=await Task.Run(()=>J1979FreezeNativeCli.Capture(options,Current));
            if(IsDisposed||Disposing||DateTimeOffset.UtcNow>=expiry||functionalAuthorizedUntil!=expiry)return;
            functionalResults.Text=FormatNativeFreeze(report,options.CapturePath,options.OutputPath);
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

    private async Task InspectWindowsNativeFreezeAsync()
    {
        if(busy||functionalCancellation is not null||DateTimeOffset.UtcNow>=functionalAuthorizedUntil)return;
        using var source=new OpenFileDialog{Title=J1979RecipeText("FreezeNativeInspect"),CheckFileExists=true,
            Multiselect=false,Filter=J1979RecipeText("FreezeNativeCaptureFilter")};
        if(source.ShowDialog(this)!=DialogResult.OK)return;
        using var destination=new SaveFileDialog{Filter=J1979RecipeText("JsonFilter"),DefaultExt="json",
            AddExtension=true,OverwritePrompt=false,FileName="CamaroDiag_CF1_Inspection.json"};
        if(destination.ShowDialog(this)!=DialogResult.OK||busy||DateTimeOffset.UtcNow>=functionalAuthorizedUntil)return;
        var expiry=functionalAuthorizedUntil;
        busy=true;completedFreeze=null;functionalExport.Enabled=false;
        functionalRunLive.Enabled=functionalRunNetwork.Enabled=functionalRunNativeUsb.Enabled=
            functionalRunAll.Enabled=functionalRunJ1979.Enabled=functionalRunEvap.Enabled=
            functionalRunClear.Enabled=functionalRunLegacy.Enabled=functionalRunLegacyMutation.Enabled=
            functionalGoogleSignIn.Enabled=false;
        functionalResults.Text=J1979RecipeText("Running");
        try
        {
            var report=await Task.Run(()=>J1979FreezeNativeCli.Inspect(source.FileName,destination.FileName,
                ()=>DateTimeOffset.UtcNow<expiry&&functionalAuthorizedUntil==expiry));
            if(!IsDisposed&&!Disposing&&DateTimeOffset.UtcNow<expiry&&functionalAuthorizedUntil==expiry)
                functionalResults.Text=FormatNativeFreeze(report,source.FileName,destination.FileName);
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

    private static string FormatNativeFreeze(J1979FreezeNative.Report report,string capture,string json)
    {
        bool complete=report.CaptureIntegrityVerified&&!report.Cancelled&&report.Summary?.AllFramesStable==true;
        var text=new StringBuilder(J1979RecipeText(complete?"Completed":"Partial")).AppendLine();
        if(report.Source=="OFFLINE_CF1")text.AppendLine(J1979RecipeText("FreezeNativeOffline"));
        if(report.CaptureIntegrityVerified&&report.Result is {} result)
        {
            var p=report.Profile!;
            var presentation=new ObdJ1979FreezeCli.Report("","SAE-J1979","","","",
                0,p.Tx.ToString("X"),p.Rx.ToString("X"),false,DateTimeOffset.MinValue,
                DateTimeOffset.MinValue,null,result);
            text.AppendLine(FormatFreeze(presentation));
        }
        else if(report.Error is not null)text.AppendLine(report.Error);
        text.AppendLine(J1979RecipeText("NativeUsbProvenance"));
        text.AppendLine(capture);text.AppendLine(json);
        return text.ToString();
    }
}
