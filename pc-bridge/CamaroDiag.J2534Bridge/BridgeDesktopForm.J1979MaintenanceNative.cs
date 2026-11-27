using System.Globalization;
using System.Text;
using System.Windows.Forms;
using CamaroDiagConnectors.Windows;

namespace CamaroDiag.J2534Bridge;

internal sealed partial class BridgeDesktopForm
{
    private readonly Button functionalInspectNativeMaintenance=Button(J1979RecipeText("NativeMaintenanceInspect"));
    private readonly Button functionalInspectNativeMaintenancePrefix=Button(J1979RecipeText("NativeMaintenancePrefixInspect"));
    private readonly Button functionalRunNativeMaintenance=Button(J1979RecipeText("NativeMaintenanceRun"));

    private J1979MaintenanceUsbCli.Options? ChooseMaintenanceOptions()
    {
        if(!TryNativeUsbId(requestId.Text,out uint tx)||!TryNativeUsbId(responseId.Text,out uint rx))
        {ShowError(J1979RecipeText("InvalidChannel"));return null;}
        bool extended=tx>0x7ff||rx>0x7ff;
        if(!(extended?tx==0x18da10f1&&rx==0x18daf110:tx==0x7e0&&rx==0x7e8))
        {ShowError(J1979RecipeText("InvalidChannel"));return null;}
        string[] ports=System.IO.Ports.SerialPort.GetPortNames().OrderBy(x=>x,StringComparer.OrdinalIgnoreCase).ToArray();
        if(ports.Length==0){ShowError(J1979RecipeText("NativeUsbNoPorts"));return null;}
        using var dialog=new Form{
            Text=J1979RecipeText("NativeMaintenanceRun"),StartPosition=FormStartPosition.CenterParent,
            ClientSize=new System.Drawing.Size(680,490),FormBorderStyle=FormBorderStyle.FixedDialog,
            MaximizeBox=false,MinimizeBox=false};
        var port=new ComboBox{Dock=DockStyle.Fill,DropDownStyle=ComboBoxStyle.DropDownList};
        port.Items.AddRange(ports);
        var operation=new ComboBox{Dock=DockStyle.Fill,DropDownStyle=ComboBoxStyle.DropDownList};
        operation.Items.AddRange(new[]{J1979RecipeText("NativeMaintenanceClear"),J1979RecipeText("NativeMaintenanceEvap")});
        var vin=new TextBox{Text=functionalEvapVin.Text.Trim().ToUpperInvariant(),Dock=DockStyle.Fill,
            MaxLength=17,CharacterCasing=CharacterCasing.Upper};
        var key=Button(J1979RecipeText("NativeUsbKey"));var folder=Button(J1979RecipeText("NativeUsbFolder"));
        string? keyPath=null,directory=null;
        var warning=new Label{Dock=DockStyle.Fill,AutoSize=false};
        var accepted=new CheckBox{Text=J1979RecipeText("NativeMaintenanceAccept"),AutoSize=true};
        var confirmed=new CheckBox{Text=J1979RecipeText("NativeMaintenanceConfirm"),AutoSize=true};
        var run=Button(J1979RecipeText("NativeMaintenanceRun"));
        var cancel=Button(J1979RecipeText("LegacyCancel"));cancel.DialogResult=DialogResult.Cancel;
        var grid=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=2,RowCount=10,Padding=new Padding(12)};
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,38));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,62));
        foreach(int height in new[]{32,32,32,32,36,36,90,42,55,45})
            grid.RowStyles.Add(new RowStyle(SizeType.Absolute,height));
        void Field(string label,Control input,int row){
            grid.Controls.Add(new Label{Text=J1979RecipeText(label),Dock=DockStyle.Fill},0,row);
            grid.Controls.Add(input,1,row);
        }
        Field("NativeMaintenanceOperation",operation,0);Field("NativeUsbPort",port,1);
        Field("LiveChannel",new Label{Text=$"{tx:X} → {rx:X}",Dock=DockStyle.Fill},2);
        Field("LiveVin",vin,3);Field("NativeUsbKey",key,4);Field("NativeUsbFolder",folder,5);
        grid.Controls.Add(warning,0,6);grid.SetColumnSpan(warning,2);
        grid.Controls.Add(accepted,0,7);grid.SetColumnSpan(accepted,2);
        grid.Controls.Add(confirmed,0,8);grid.SetColumnSpan(confirmed,2);
        var buttons=new FlowLayoutPanel{Dock=DockStyle.Fill};buttons.Controls.Add(run);buttons.Controls.Add(cancel);
        grid.Controls.Add(buttons,0,9);grid.SetColumnSpan(buttons,2);
        J1979MaintenanceUsb.Profile? Profile()
        {
            if(operation.SelectedIndex is not (0 or 1))return null;
            try{
                var p=new J1979MaintenanceUsb.Profile(operation.SelectedIndex+1,tx,rx,extended,vin.Text,60000);
                _=p.Encode();return p;
            }catch(InvalidDataException){return null;}
        }
        void Refresh(){
            confirmed.Enabled=accepted.Checked;
            run.Enabled=accepted.Checked&&confirmed.Checked&&port.SelectedItem is string&&keyPath is not null&&
                directory is not null&&Profile() is not null;
        }
        void Changed(){
            accepted.Checked=false;confirmed.Checked=false;
            warning.Text=operation.SelectedIndex switch{
                0=>J1979RecipeText("NativeMaintenanceClearWarning"),1=>J1979RecipeText("NativeMaintenanceEvapWarning"),_=>""};
            Refresh();
        }
        operation.SelectedIndexChanged+=(_,_)=>Changed();port.SelectedIndexChanged+=(_,_)=>Changed();
        vin.TextChanged+=(_,_)=>Changed();accepted.CheckedChanged+=(_,_)=>{confirmed.Checked=false;Refresh();};
        confirmed.CheckedChanged+=(_,_)=>Refresh();
        key.Click+=(_,_)=>{
            using var picker=new OpenFileDialog{CheckFileExists=true,Multiselect=false,Filter=J1979RecipeText("NativeUsbKeyFilter")};
            if(picker.ShowDialog(dialog)==DialogResult.OK){keyPath=picker.FileName;key.Text=J1979RecipeText("FreezeNativeSelected");Changed();}
        };
        folder.Click+=(_,_)=>{
            using var picker=new FolderBrowserDialog{Description=J1979RecipeText("NativeUsbFolder"),UseDescriptionForTitle=true};
            if(picker.ShowDialog(dialog)==DialogResult.OK){directory=picker.SelectedPath;folder.Text=J1979RecipeText("FreezeNativeSelected");Changed();}
        };
        J1979MaintenanceUsbCli.Options? chosen=null;
        run.Click+=(_,_)=>{
            if(!accepted.Checked||!confirmed.Checked||port.SelectedItem is not string name||
                keyPath is null||directory is null||Profile() is not {} profile)return;
            string stem="CamaroDiag_SAE-J1979_MAINTENANCE_"+Guid.NewGuid().ToString("N");
            chosen=new(name,keyPath,Path.Combine(directory,stem+".cm"),Path.Combine(directory,stem+".json"),profile);
            dialog.DialogResult=DialogResult.OK;
        };
        dialog.Controls.Add(grid);dialog.CancelButton=cancel;Changed();
        return dialog.ShowDialog(this)==DialogResult.OK?chosen:null;
    }

    private async Task RunWindowsNativeMaintenanceAsync()
    {
        if(busy||functionalCancellation is not null||DateTimeOffset.UtcNow>=functionalAuthorizedUntil)return;
        if(bridgeTask is {IsCompleted:false}||consoleDevice is not null)
        {ShowError(J1979RecipeText("NativeUsbPrerequisites"));return;}
        var expiry=functionalAuthorizedUntil;
        var options=ChooseMaintenanceOptions();
        if(options is null||busy||functionalCancellation is not null||DateTimeOffset.UtcNow>=expiry||
            functionalAuthorizedUntil!=expiry||bridgeTask is {IsCompleted:false}||consoleDevice is not null||
            !System.IO.Ports.SerialPort.GetPortNames().Contains(options.Port,StringComparer.OrdinalIgnoreCase))return;
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
        try{
            var report=await Task.Run(()=>J1979MaintenanceUsbCli.Capture(options,Current));
            if(!IsDisposed&&!Disposing&&DateTimeOffset.UtcNow<expiry&&functionalAuthorizedUntil==expiry)
            {
                if(report["inspection"] is Dictionary<string,object?> journal)
                    functionalResults.Text=FormatNativeMaintenance(journal,options.ReportPath);
                else functionalResults.Text=J1979RecipeText(Equals(report["recovery_required"],true)?
                    "NativeMaintenanceRecovery":"NativeMaintenanceUnverified")+Environment.NewLine+options.ReportPath;
            }
        }
        catch(Exception){
            if(!IsDisposed&&!Disposing)functionalResults.Text=J1979RecipeText("NativeMaintenanceRecovery");
        }
        finally{
            functionalCancellation=null;busy=false;
            if(!IsDisposed&&!Disposing){
                functionalCancel.Enabled=false;functionalGoogleSignIn.Enabled=true;
                functionalRunLive.Enabled=functionalRunNetwork.Enabled=functionalRunNativeUsb.Enabled=
                    functionalRunAll.Enabled=functionalRunJ1979.Enabled=functionalRunEvap.Enabled=
                    functionalRunClear.Enabled=functionalRunLegacy.Enabled=functionalRunLegacyMutation.Enabled=
                    DateTimeOffset.UtcNow<functionalAuthorizedUntil;
            }
        }
    }


    private async Task InspectWindowsNativeMaintenanceAsync(bool prefix=false)
    {
        if(busy||functionalCancellation is not null||DateTimeOffset.UtcNow>=functionalAuthorizedUntil)return;
        var expiry=functionalAuthorizedUntil;
        string vin=functionalEvapVin.Text.Trim().ToUpperInvariant();
        if(!J1979LiveNative.ValidVin(vin)){ShowError(J1979RecipeText("LiveNativeVinRequired"));return;}
        using var source=new OpenFileDialog{Title=J1979RecipeText(prefix?"NativeMaintenancePrefixInspect":"NativeMaintenanceInspect"),
            CheckFileExists=true,Multiselect=false,Filter=J1979RecipeText("NativeMaintenanceFilter")};
        if(source.ShowDialog(this)!=DialogResult.OK)return;
        using var destination=new SaveFileDialog{Filter=J1979RecipeText("JsonFilter"),DefaultExt="json",
            AddExtension=true,OverwritePrompt=false,FileName=prefix?"CamaroDiag_CM1_Interrupted.json":"CamaroDiag_CM1_Inspection.json"};
        bool Current()=>!IsDisposed&&!Disposing&&DateTimeOffset.UtcNow<expiry&&
            functionalAuthorizedUntil==expiry&&functionalEvapVin.Text.Trim().ToUpperInvariant()==vin;
        if(destination.ShowDialog(this)!=DialogResult.OK||busy||functionalCancellation is not null||!Current())return;
        busy=true;functionalExport.Enabled=false;
        functionalRunLive.Enabled=functionalRunNetwork.Enabled=functionalRunNativeUsb.Enabled=
            functionalRunAll.Enabled=functionalRunJ1979.Enabled=functionalRunEvap.Enabled=
            functionalRunClear.Enabled=functionalRunLegacy.Enabled=functionalRunLegacyMutation.Enabled=
            functionalGoogleSignIn.Enabled=false;
        functionalResults.Text=J1979RecipeText("Running");
        // Do not read WinForms controls from the worker. UI is disabled
        // while the owner expiry and lease are checked during file I/O.
        try
        {
            var report=await Task.Run(()=>J1979MaintenanceJournal.InspectFile(source.FileName,vin,
                destination.FileName,()=>DateTimeOffset.UtcNow<expiry&&functionalAuthorizedUntil==expiry,prefix));
            if(Current())functionalResults.Text=FormatNativeMaintenance(report,destination.FileName);
        }
        catch(Exception){if(Current())functionalResults.Text=J1979RecipeText("Failed");}
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

    private static string FormatNativeMaintenance(Dictionary<string,object?> report,string output)
    {
        var text=new StringBuilder(J1979RecipeText("NativeMaintenanceOffline")).AppendLine();
        text.AppendLine(J1979RecipeText(Equals(report.GetValueOrDefault("prefix_integrity_verified"),true)?
            "NativeMaintenancePrefixConsistent":"NativeMaintenanceConsistent"));
        if(Equals(report["recovery_required"],true))
            text.AppendLine(J1979RecipeText("NativeMaintenanceRecovery"));
        if(report.GetValueOrDefault("terminal") is Dictionary<string,object?> terminal)
        {
            string outcome=Convert.ToString(terminal["outcome"],CultureInfo.InvariantCulture)??"";
            string key=outcome switch{
                "NOT_TESTED"=>"NativeMaintenanceNotTested","REFUSED"=>"NativeMaintenanceRefused",
                "UNCERTAIN"=>"NativeMaintenanceUncertain","ACKNOWLEDGED_PARTIAL"=>"NativeMaintenanceAckPartial",
                "ACKNOWLEDGED_READBACK"=>"NativeMaintenanceAckReadback","ACKNOWLEDGED_EVAP"=>"NativeMaintenanceAckEvap",
                _=>"Partial"};
            text.AppendLine(J1979RecipeText(key));
        }
        else text.AppendLine(J1979RecipeText("NativeMaintenanceNoTerminal"));
        text.AppendLine(J1979RecipeText("NativeMaintenanceNoRetry"));
        text.AppendLine(output);return text.ToString();
    }
}
