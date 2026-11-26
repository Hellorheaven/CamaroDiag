using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Windows.Forms;

namespace CamaroDiag.J2534Bridge;

internal sealed partial class BridgeDesktopForm
{
    private sealed record WindowsEvapReport(string Schema, string Protocol, string Version,
        string ExpectedVin, string Adapter, int ProtocolNumber, string RequestCanId,
        string ResponseCanId, DateTimeOffset StartedAtUtc, DateTimeOffset FinishedAtUtc,
        bool MonitorCompletionVerified, ObdJ1979EvapTest.Result Result);

    private readonly Button functionalRunEvap = Button(J1979RecipeText("EvapRun"));
    private readonly TextBox functionalEvapVin = new() { MaxLength = 17, CharacterCasing = CharacterCasing.Upper,
        Dock = DockStyle.Fill };
    private WindowsEvapReport? completedEvap;

    private async Task RunWindowsEvapAsync()
    {
        if (functionalCancellation is not null || busy) return;
        if (DateTimeOffset.UtcNow >= functionalAuthorizedUntil) return;
        if (bridgeTask is { IsCompleted: false } || consoleDevice is not null ||
            adapter.SelectedItem is not AdapterChoice selected || !functionalEcmProfileConfirmed.Checked)
        {
            ShowError(J1979RecipeText("Prerequisites"));
            return;
        }
        var expectedVin = functionalEvapVin.Text.Trim();
        if (!Regex.IsMatch(expectedVin, @"\A[A-HJ-NPR-Z0-9]{17}\z"))
        {
            ShowError(J1979RecipeText("EvapVinRequired"));
            return;
        }
        NativeGmlanBenchProbe.Options options;
        try
        {
            options = NativeGmlanBenchProbe.Parse([
                NativeGmlanBenchProbe.Command,
                "--adapter-index", adapter.SelectedIndex.ToString(CultureInfo.InvariantCulture),
                "--request-can-id", requestId.Text.Trim(),
                "--response-can-id", responseId.Text.Trim(),
                "--protocol", protocol.SelectedItem?.ToString() ?? ""
            ]);
            if (!StandardObdPhysicalPolicy.SupportsPrimaryChannel(options.RequestCanId,
                    options.ResponseCanId, options.ProtocolNumber))
                throw new ArgumentException();
        }
        catch (ArgumentException)
        {
            ShowError(J1979RecipeText("InvalidChannel"));
            return;
        }

        // Two separate, local confirmations; no automatic execution through
        // the read-only campaign and no generic raw actuator entry point.
        var warning = MessageBox.Show(this, J1979RecipeText("EvapWarning"),
            J1979RecipeText("EvapRun"), MessageBoxButtons.OKCancel,
            MessageBoxIcon.Warning, MessageBoxDefaultButton.Button2);
        if (warning != DialogResult.OK) return;
        var yes = MessageBox.Show(this, string.Format(CultureInfo.CurrentUICulture,
                J1979RecipeText("EvapConfirm"), expectedVin, options.RequestCanId.ToString("X"),
                options.ResponseCanId.ToString("X")),
            J1979RecipeText("EvapRun"), MessageBoxButtons.YesNo,
            MessageBoxIcon.Warning, MessageBoxDefaultButton.Button2);
        if (yes != DialogResult.Yes || DateTimeOffset.UtcNow >= functionalAuthorizedUntil) return;
        var expiry = functionalAuthorizedUntil;

        using var cancellation = new CancellationTokenSource();
        functionalCancellation = cancellation;
        busy = true;
        completedEvap = null;
        completedClear = null;
        completedLegacy = null;
        completedJ1979 = null;
        completedFunctionalChecks = [];
        functionalRunNativeUsb.Enabled = functionalRunAll.Enabled = functionalRunJ1979.Enabled = functionalRunEvap.Enabled = functionalRunClear.Enabled = functionalRunLegacy.Enabled = functionalRunLegacyMutation.Enabled =
            functionalGoogleSignIn.Enabled = functionalExport.Enabled = functionalEvapVin.Enabled = false;
        functionalCancel.Enabled = true;
        functionalResults.Text = J1979RecipeText("EvapRunning");
        void RequireActive()
        {
            cancellation.Token.ThrowIfCancellationRequested();
            if (DateTimeOffset.UtcNow >= expiry) throw new OperationCanceledException();
        }
        try
        {
            var started = DateTimeOffset.UtcNow;
            var result = await Task.Run(() =>
            {
                RequireActive();
                using var device = new J2534Device(selected.Adapter);
                device.Open(options.RequestCanId, options.ResponseCanId, options.Can29Bit, options.ProtocolNumber);
                RequireActive();
                return device.RunStandardEvapTest(expectedVin, warning == DialogResult.OK,
                    yes == DialogResult.Yes, RequireActive);
            });
            // Preserve evidence if the user cancels after possible TX.
            // Expired owner sessions may not expose the diagnostic payload.
            if (DateTimeOffset.UtcNow >= expiry)
            {
                functionalResults.Text = J1979RecipeText("EvapExpired");
                return;
            }
            var report = new WindowsEvapReport("camarodiag-functional-j1979-evap-v1",
                "SAE-J1979", typeof(BridgeDesktopForm).Assembly.GetName().Version?.ToString() ?? "",
                expectedVin, selected.Adapter.DisplayName, options.ProtocolNumber,
                options.RequestCanId.ToString("X"), options.ResponseCanId.ToString("X"),
                started, DateTimeOffset.UtcNow, false, result);
            var output = new StringBuilder(J1979RecipeText("EvapOutcome_" + result.Outcome));
            output.AppendLine().AppendLine(J1979RecipeText("EvapNotCompletion"));
            if (result.Reason is not null) output.AppendLine(result.Reason);
            foreach (var exchange in result.Exchanges)
                output.AppendLine(exchange.RequestHex + " -> " + (exchange.ResponseHex ?? J1979RecipeText("Unavailable")));
            functionalResults.Text = output.ToString();
            completedEvap = report;
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
            functionalGoogleSignIn.Enabled = functionalEvapVin.Enabled = true;
            functionalRunNativeUsb.Enabled = functionalRunAll.Enabled = functionalRunJ1979.Enabled = functionalRunEvap.Enabled = functionalRunClear.Enabled = functionalRunLegacy.Enabled = functionalRunLegacyMutation.Enabled =
                DateTimeOffset.UtcNow < functionalAuthorizedUntil;
        }
    }

    private void ExportWindowsEvap(WindowsEvapReport report)
    {
        using var dialog = new SaveFileDialog
        {
            Filter = J1979RecipeText("JsonFilter"), AddExtension = true, DefaultExt = "json",
            FileName = "CamaroDiag_SAE-J1979_EVAP.json", OverwritePrompt = true
        };
        if (dialog.ShowDialog(this) != DialogResult.OK ||
            DateTimeOffset.UtcNow >= functionalAuthorizedUntil ||
            !ReferenceEquals(completedEvap, report)) return;
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
