using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using System.Windows.Forms;
using CamaroDiagConnectors.Windows;

namespace CamaroDiag.J2534Bridge;

internal sealed partial class BridgeDesktopForm
{
    private readonly Button functionalInspectNativeFreezePrefix=
        Button(J1979RecipeText("FreezePrefixInspect"));

    private async Task InspectWindowsNativeFreezePrefixAsync()
    {
        if(busy||functionalCancellation is not null||DateTimeOffset.UtcNow>=functionalAuthorizedUntil)return;
        string vin=functionalEvapVin.Text.Trim().ToUpperInvariant();
        if(!Regex.IsMatch(vin,@"\A[A-HJ-NPR-Z0-9]{17}\z"))
        {ShowError(J1979RecipeText("InvalidChannel"));return;}
        using var source=new OpenFileDialog{Title=J1979RecipeText("FreezePrefixInspect"),
            CheckFileExists=true,Multiselect=false,Filter=J1979RecipeText("FreezeNativeCaptureFilter")};
        if(source.ShowDialog(this)!=DialogResult.OK)return;
        if(MessageBox.Show(this,string.Format(CultureInfo.CurrentCulture,
            J1979RecipeText("FreezePrefixConfirm"),vin),
            J1979RecipeText("FreezePrefixInspect"),MessageBoxButtons.YesNo,
            MessageBoxIcon.Warning)!=DialogResult.Yes)return;
        using var destination=new SaveFileDialog{Filter=J1979RecipeText("JsonFilter"),
            DefaultExt="json",AddExtension=true,OverwritePrompt=false,
            FileName="CamaroDiag_CF1_Prefix.json"};
        if(destination.ShowDialog(this)!=DialogResult.OK||
           DateTimeOffset.UtcNow>=functionalAuthorizedUntil||busy)return;
        var expiry=functionalAuthorizedUntil;
        busy=true;functionalInspectNativeFreezePrefix.Enabled=false;
        functionalResults.Text=J1979RecipeText("Running");
        try
        {
            var report=await Task.Run(()=>J1979FreezeNativeCli.InspectPrefix(
                source.FileName,destination.FileName,vin));
            if(IsDisposed||Disposing||DateTimeOffset.UtcNow>=expiry||
               functionalAuthorizedUntil!=expiry)return;
            var message=new StringBuilder(J1979RecipeText("FreezePrefixSaved")).AppendLine();
            message.AppendLine(destination.FileName);
            message.AppendLine(string.Format(CultureInfo.CurrentCulture,
                J1979RecipeText("FreezePrefixSummary"),report["complete_observations"],
                report["discarded_tail_bytes"],report["interruption"]));
            if(report.TryGetValue("observed_frame_progress",out var frameValue)&&
               frameValue is Dictionary<string,object?>[] progress&&progress.Length>0)
            {
                message.AppendLine(J1979RecipeText("FreezePrefixFrames"));
                foreach(var frame in progress)
                {
                    var announced=(int[])frame["advertised_measurement_pids"]!;
                    var read=(int[])frame["read_measurement_pids"]!;
                    message.AppendLine(string.Format(CultureInfo.CurrentCulture,
                        J1979RecipeText("FreezePrefixFrameRow"),
                        ((int)frame["frame"]!).ToString("X2",CultureInfo.InvariantCulture),
                        announced.Length,read.Length));
                }
            }
            functionalResults.Text=message.ToString();
        }
        catch(Exception e) when(e is IOException or InvalidDataException or ArgumentException or
            UnauthorizedAccessException or InvalidOperationException)
        {
            if(!IsDisposed&&!Disposing)
                functionalResults.Text=J1979RecipeText("CatalogueRejected")+" ("+e.GetType().Name+")";
        }
        finally
        {
            busy=false;
            if(!IsDisposed&&!Disposing)
                functionalInspectNativeFreezePrefix.Enabled=DateTimeOffset.UtcNow<functionalAuthorizedUntil;
        }
    }
}
