using System.Globalization;
using System.Reflection;
using System.Resources;
using System.Text;
using System.Text.Json;
using System.Windows.Forms;

namespace CamaroDiag.J2534Bridge;

internal sealed partial class BridgeDesktopForm
{
    private sealed record WindowsJ1979Report(string Schema, string Protocol,
        string Version, string Adapter, int ProtocolNumber,
        string RequestCanId, string ResponseCanId, DateTimeOffset StartedAtUtc,
        DateTimeOffset FinishedAtUtc, ObdJ1979FunctionalScan.Result Result);

    private static readonly ResourceManager J1979RecipeResources =
        new("CamaroDiag.J2534Bridge.BridgeJ1979Recipe",
            typeof(BridgeDesktopForm).Assembly);
    private static string J1979RecipeText(string key) =>
        J1979RecipeResources.GetString(key, CultureInfo.CurrentUICulture)
        ?? throw new InvalidOperationException("Missing J1979 resource: " + key);

    private async Task RunWindowsJ1979Async()
    {
        if (functionalCancellation is not null) return;
        if (DateTimeOffset.UtcNow >= functionalAuthorizedUntil)
        {
            functionalRunAll.Enabled = functionalRunJ1979.Enabled = functionalRunEvap.Enabled = functionalRunClear.Enabled = functionalRunLegacy.Enabled = functionalRunLegacyMutation.Enabled = false;
            return;
        }
        if (busy || bridgeTask is { IsCompleted: false } || consoleDevice is not null ||
            adapter.SelectedItem is not AdapterChoice selected || !functionalEcmProfileConfirmed.Checked)
        {
            ShowError(J1979RecipeText("Prerequisites"));
            return;
        }
        NativeGmlanBenchProbe.Options options;
        try
        {
            // Reuse the physical CAN address/profile parser, not a GMLAN request.
            options = NativeGmlanBenchProbe.Parse([
                NativeGmlanBenchProbe.Command,
                "--adapter-index", adapter.SelectedIndex.ToString(CultureInfo.InvariantCulture),
                "--request-can-id", requestId.Text.Trim(),
                "--response-can-id", responseId.Text.Trim(),
                "--protocol", protocol.SelectedItem?.ToString() ?? ""
            ]);
            if (!StandardObdPhysicalPolicy.SupportsChannel(options.RequestCanId,
                    options.ResponseCanId, options.ProtocolNumber))
                throw new ArgumentException();
        }
        catch (ArgumentException)
        {
            ShowError(J1979RecipeText("InvalidChannel"));
            return;
        }
        var expires = functionalAuthorizedUntil;
        using var cancellation = new CancellationTokenSource();
        functionalCancellation = cancellation;
        busy = true;
        completedJ1979 = null;
        completedEvap = null;
        completedClear = null;
        completedLegacy = null;
        completedFunctionalChecks = [];
        functionalRunAll.Enabled = functionalRunJ1979.Enabled = functionalRunEvap.Enabled = functionalRunClear.Enabled = functionalRunLegacy.Enabled = functionalRunLegacyMutation.Enabled =
            functionalGoogleSignIn.Enabled = functionalExport.Enabled = false;
        functionalCancel.Enabled = true;
        functionalResults.Text = J1979RecipeText("Running");
        void RequireActive()
        {
            cancellation.Token.ThrowIfCancellationRequested();
            if (DateTimeOffset.UtcNow >= expires)
                throw new OperationCanceledException();
        }
        try
        {
            var started = DateTimeOffset.UtcNow;
            var result = await Task.Run(() =>
            {
                RequireActive();
                using var device = new J2534Device(selected.Adapter);
                device.Open(options.RequestCanId, options.ResponseCanId,
                    options.Can29Bit, options.ProtocolNumber);
                RequireActive();
                return ObdJ1979FunctionalScan.Run(device.Exchange, RequireActive,
                    TimeSpan.FromMinutes(3));
            });
            RequireActive();
            var report = new WindowsJ1979Report("camarodiag-functional-j1979-can-v1",
                "SAE-J1979", typeof(BridgeDesktopForm).Assembly.GetName().Version?.ToString() ?? "",
                selected.Adapter.DisplayName, options.ProtocolNumber,
                options.RequestCanId.ToString("X"), options.ResponseCanId.ToString("X"),
                started, DateTimeOffset.UtcNow, result);
            var output = new StringBuilder();
            output.AppendLine(J1979RecipeText(result.Completed ? "Completed" : "Partial"));
            output.AppendLine("VIN: " + (result.ObservedVin ?? J1979RecipeText("Unavailable")));
            foreach (var step in result.Steps)
                output.AppendLine($"{step.RequestHex} [{step.Status}] {step.ResponseHex} {step.Detail}");
            output.AppendLine(J1979RecipeText("MutationsNotTested"));
            functionalResults.Text = output.ToString();
            completedJ1979 = report;
            functionalExport.Enabled = true;
        }
        catch (OperationCanceledException)
        {
            functionalResults.Text = J1979RecipeText("Cancelled");
        }
        catch (Exception error)
        {
            functionalResults.Text = J1979RecipeText("Failed") + " " + error.GetType().Name;
        }
        finally
        {
            functionalCancellation = null;
            busy = false;
            functionalCancel.Enabled = false;
            functionalGoogleSignIn.Enabled = true;
            functionalRunAll.Enabled = functionalRunJ1979.Enabled = functionalRunEvap.Enabled = functionalRunClear.Enabled = functionalRunLegacy.Enabled = functionalRunLegacyMutation.Enabled =
                DateTimeOffset.UtcNow < functionalAuthorizedUntil;
        }
    }

    private void ExportWindowsJ1979(WindowsJ1979Report report)
    {
        using var dialog = new SaveFileDialog
        {
            Filter = J1979RecipeText("JsonFilter"),
            AddExtension = true, DefaultExt = "json",
            FileName = "CamaroDiag_SAE-J1979_CAN.json", OverwritePrompt = true
        };
        if (dialog.ShowDialog(this) != DialogResult.OK ||
            DateTimeOffset.UtcNow >= functionalAuthorizedUntil ||
            !ReferenceEquals(completedJ1979, report)) return;
        try
        {
            var json = JsonSerializer.Serialize(report, new JsonSerializerOptions
            {
                WriteIndented = true, PropertyNamingPolicy = JsonNamingPolicy.CamelCase
            });
            File.WriteAllText(dialog.FileName, json, Encoding.UTF8);
        }
        catch (Exception)
        {
            ShowError(J1979RecipeText("ExportFailed"));
        }
    }
}
