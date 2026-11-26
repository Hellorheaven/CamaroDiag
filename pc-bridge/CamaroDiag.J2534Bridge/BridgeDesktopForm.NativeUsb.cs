using System.Diagnostics;
using System.Globalization;
using System.IO.Ports;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Windows.Forms;
using CamaroDiagConnectors.Windows;

namespace CamaroDiag.J2534Bridge;

internal sealed partial class BridgeDesktopForm
{
    private readonly Button functionalRunNativeUsb = Button(J1979RecipeText("NativeUsbRun"));

    private async Task RunWindowsNativeUsbAsync()
    {
        if(functionalCancellation is not null||DateTimeOffset.UtcNow>=functionalAuthorizedUntil)return;
        if(busy||bridgeTask is {IsCompleted:false}||consoleDevice is not null){
            ShowError(J1979RecipeText("NativeUsbPrerequisites"));return;
        }
        string vin=functionalEvapVin.Text.Trim().ToUpperInvariant();
        if(!Regex.IsMatch(vin,@"\A[A-HJ-NPR-Z0-9]{17}\z")){
            ShowError(J1979RecipeText("EvapVinRequired"));return;
        }
        if(!TryNativeUsbId(requestId.Text,out uint tx)||!TryNativeUsbId(responseId.Text,out uint rx)){
            ShowError(J1979RecipeText("InvalidChannel"));return;
        }
        bool extended=tx>0x7ff||rx>0x7ff;
        if(!J1979UsbAuth.PhysicalPair(tx,rx,extended)||!functionalEcmProfileConfirmed.Checked){
            ShowError(J1979RecipeText("InvalidChannel"));return;
        }
        string[] ports=SerialPort.GetPortNames().OrderBy(x=>x,StringComparer.OrdinalIgnoreCase).ToArray();
        if(ports.Length==0){ShowError(J1979RecipeText("NativeUsbNoPorts"));return;}
        using var choice=new Form{Text=J1979RecipeText("NativeUsbRun"),Width=520,Height=180,
            StartPosition=FormStartPosition.CenterParent,FormBorderStyle=FormBorderStyle.FixedDialog,
            MinimizeBox=false,MaximizeBox=false};
        using var selector=new ComboBox{DropDownStyle=ComboBoxStyle.DropDownList,Dock=DockStyle.Top};
        selector.Items.AddRange(ports);
        using var ok=new Button{Text=J1979RecipeText("NativeUsbSelect"),DialogResult=DialogResult.OK,Dock=DockStyle.Bottom};
        using var label=new Label{Text=J1979RecipeText("NativeUsbPort"),Dock=DockStyle.Top,AutoSize=true};
        choice.Controls.Add(selector);choice.Controls.Add(label);choice.Controls.Add(ok);
        choice.AcceptButton=ok;ok.Enabled=false;selector.SelectedIndexChanged+=(_,_)=>ok.Enabled=selector.SelectedIndex>=0;
        if(choice.ShowDialog(this)!=DialogResult.OK||selector.SelectedItem is not string portName)return;
        using var keyDialog=new OpenFileDialog{Title=J1979RecipeText("NativeUsbKey"),CheckFileExists=true,
            Multiselect=false,Filter=J1979RecipeText("NativeUsbKeyFilter")};
        if(keyDialog.ShowDialog(this)!=DialogResult.OK)return;
        using var folder=new FolderBrowserDialog{Description=J1979RecipeText("NativeUsbFolder"),UseDescriptionForTitle=true};
        if(folder.ShowDialog(this)!=DialogResult.OK)return;
        string confirmation=string.Format(CultureInfo.CurrentUICulture,J1979RecipeText("NativeUsbConfirm"),portName,vin,tx.ToString("X"),rx.ToString("X"));
        if(MessageBox.Show(this,confirmation,J1979RecipeText("NativeUsbRun"),MessageBoxButtons.YesNo,
            MessageBoxIcon.Warning,MessageBoxDefaultButton.Button2)!=DialogResult.Yes)return;
        if(DateTimeOffset.UtcNow>=functionalAuthorizedUntil||busy||functionalCancellation is not null||
           bridgeTask is {IsCompleted:false}||consoleDevice is not null||
           !SerialPort.GetPortNames().Contains(portName,StringComparer.OrdinalIgnoreCase))return;
        var expires=functionalAuthorizedUntil;
        using var cancel=new CancellationTokenSource();
        functionalCancellation=cancel;busy=true;
        completedJ1979=null;completedEvap=null;completedClear=null;completedLegacy=null;completedFunctionalChecks=[];
        functionalRunNativeUsb.Enabled=functionalRunAll.Enabled=functionalRunJ1979.Enabled=functionalRunEvap.Enabled=
            functionalRunClear.Enabled=functionalRunLegacy.Enabled=functionalRunLegacyMutation.Enabled=
            functionalGoogleSignIn.Enabled=functionalExport.Enabled=false;
        functionalCancel.Enabled=true;functionalResults.Text=J1979RecipeText("Running");
        string id=Guid.NewGuid().ToString("N");
        string rawPath=Path.Combine(folder.SelectedPath,"CamaroDiag_SAE-J1979_USB_"+id+".cj");
        string reportPath=Path.Combine(folder.SelectedPath,"CamaroDiag_SAE-J1979_USB_"+id+".json");
        string keyPath=keyDialog.FileName;
        bool Current()=>!cancel.IsCancellationRequested&&DateTimeOffset.UtcNow<expires;
        try{
            var outcome=await Task.Run(()=>{
                byte[] key=new byte[32];
                try{
                    if(!Current())throw new OperationCanceledException();
                    using(var keyFile=new FileStream(keyPath,FileMode.Open,FileAccess.Read,FileShare.Read)){
                        keyFile.ReadExactly(key);if(keyFile.ReadByte()!=-1)throw new IOException("Invalid credential size");
                    }
                    if(!Current())throw new OperationCanceledException();
                    using var json=new FileStream(reportPath,FileMode.CreateNew,FileAccess.Write,FileShare.None);
                    J1979UsbCapture.Result result;var timer=Stopwatch.StartNew();
                    const int budget=60000;
                    try{
                        using var raw=new FileStream(rawPath,FileMode.CreateNew,FileAccess.Write,FileShare.None);
                        if(!Current())throw new OperationCanceledException();
                        using var port=new SerialPort(portName,115200,Parity.None,8,StopBits.One){
                            Handshake=Handshake.None,DtrEnable=true,RtsEnable=false,ReadTimeout=100,WriteTimeout=1000
                        };
                        port.Open();
                        result=new J1979UsbCapture(new J1979SerialPortLink(port,Current)).Run(tx,rx,extended,budget,key,raw);
                        raw.Flush(flushToDisk:true);
                    }catch(Exception ex) when(ex is IOException or UnauthorizedAccessException or TimeoutException or InvalidOperationException or OperationCanceledException){
                        result=new(false,false,0,ex.Message);
                    }
                    bool cancelled=!Current();
                    J1979NativeReport.Write(json,result,typeof(BridgeDesktopForm).Assembly.GetName().Version?.ToString()??"",
                        vin,"ECM",portName,tx,rx,extended,budget,timer.ElapsedMilliseconds,Path.GetFileName(rawPath),cancelled);
                    json.Flush(flushToDisk:true);
                    return (result,cancelled);
                }finally{CryptographicOperations.ZeroMemory(key);}
            });
            if(IsDisposed||Disposing)return;
            var result=outcome.result;
            bool vinMatches=result.ObservedVin==vin;
            bool complete=!outcome.cancelled&&result.DeviceCredentialAuthenticated&&result.CaptureIntegrityVerified&&
                result.Summary?.End=="COMPLETE"&&vinMatches;
            var text=new StringBuilder(J1979RecipeText(complete?"Completed":"Partial")).AppendLine();
            text.AppendLine("VIN: "+(result.ObservedVin??J1979RecipeText("Unavailable")));
            if(!vinMatches)text.AppendLine(J1979RecipeText("NativeUsbVinMismatch"));
            foreach(var step in result.Steps)
                text.AppendLine($"{step.RequestHex} [{step.Status}] {step.ResponseHex}");
            text.AppendLine(J1979RecipeText("NativeUsbProvenance"));
            text.AppendLine(reportPath);text.AppendLine(rawPath);
            functionalResults.Text=text.ToString();
        }catch(OperationCanceledException){if(!IsDisposed&&!Disposing)functionalResults.Text=J1979RecipeText("Cancelled");}
        catch(Exception){if(!IsDisposed&&!Disposing)functionalResults.Text=J1979RecipeText("Failed");}
        finally{
            functionalCancellation=null;busy=false;
            if(!IsDisposed&&!Disposing){
                functionalCancel.Enabled=false;functionalGoogleSignIn.Enabled=true;
                functionalRunNativeUsb.Enabled=functionalRunAll.Enabled=functionalRunJ1979.Enabled=
                    functionalRunEvap.Enabled=functionalRunClear.Enabled=functionalRunLegacy.Enabled=
                    functionalRunLegacyMutation.Enabled=DateTimeOffset.UtcNow<functionalAuthorizedUntil;
            }
        }
    }
    private static bool TryNativeUsbId(string value,out uint id){
        value=value.Trim();if(value.StartsWith("0x",StringComparison.OrdinalIgnoreCase))value=value[2..];
        return uint.TryParse(value,NumberStyles.AllowHexSpecifier,CultureInfo.InvariantCulture,out id);
    }
}
