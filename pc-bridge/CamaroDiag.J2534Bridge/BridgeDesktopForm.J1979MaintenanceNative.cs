using System.Globalization;
using System.Text;
using System.Windows.Forms;
using CamaroDiagConnectors.Windows;

namespace CamaroDiag.J2534Bridge;

internal sealed partial class BridgeDesktopForm
{
    private readonly Button functionalInspectNativeMaintenance=Button(J1979RecipeText("NativeMaintenanceInspect"));

    private async Task InspectWindowsNativeMaintenanceAsync()
    {
        if(busy||functionalCancellation is not null||DateTimeOffset.UtcNow>=functionalAuthorizedUntil)return;
        var expiry=functionalAuthorizedUntil;
        string vin=functionalEvapVin.Text.Trim().ToUpperInvariant();
        if(!J1979LiveNative.ValidVin(vin)){ShowError(J1979RecipeText("LiveNativeVinRequired"));return;}
        using var source=new OpenFileDialog{Title=J1979RecipeText("NativeMaintenanceInspect"),
            CheckFileExists=true,Multiselect=false,Filter=J1979RecipeText("NativeMaintenanceFilter")};
        if(source.ShowDialog(this)!=DialogResult.OK)return;
        using var destination=new SaveFileDialog{Filter=J1979RecipeText("JsonFilter"),DefaultExt="json",
            AddExtension=true,OverwritePrompt=false,FileName="CamaroDiag_CM1_Inspection.json"};
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
                destination.FileName,()=>DateTimeOffset.UtcNow<expiry&&functionalAuthorizedUntil==expiry));
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
        text.AppendLine(J1979RecipeText("NativeMaintenanceConsistent"));
        if(Equals(report["recovery_required"],true))
            text.AppendLine(J1979RecipeText("NativeMaintenanceRecovery"));
        if(report["terminal"] is Dictionary<string,object?> terminal)
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
