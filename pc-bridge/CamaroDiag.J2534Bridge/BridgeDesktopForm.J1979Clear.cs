using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Windows.Forms;

namespace CamaroDiag.J2534Bridge;

internal sealed partial class BridgeDesktopForm
{
    private sealed record WindowsClearReport(string Schema, string Protocol, string Version,
        string ExpectedVin, string Adapter, int ProtocolNumber, string RequestCanId,
        string ResponseCanId, DateTimeOffset StartedAtUtc, DateTimeOffset FinishedAtUtc,
        ObdJ1979ClearRecipe.Result Result);

    private readonly Button functionalRunClear = Button(J1979RecipeText("ClearRun"));
    private WindowsClearReport? completedClear;

    private async Task RunWindowsClearAsync()
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
            ShowError(J1979RecipeText("ClearVinRequired"));
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
        var warning = MessageBox.Show(this, J1979RecipeText("ClearWarning"),
            J1979RecipeText("ClearRun"), MessageBoxButtons.OKCancel,
            MessageBoxIcon.Warning, MessageBoxDefaultButton.Button2);
        if (warning != DialogResult.OK) return;
        var yes = MessageBox.Show(this, string.Format(CultureInfo.CurrentUICulture,
                J1979RecipeText("ClearConfirm"), expectedVin, options.RequestCanId.ToString("X"),
                options.ResponseCanId.ToString("X")),
            J1979RecipeText("ClearRun"), MessageBoxButtons.YesNo,
            MessageBoxIcon.Warning, MessageBoxDefaultButton.Button2);
        if (yes != DialogResult.Yes || DateTimeOffset.UtcNow >= functionalAuthorizedUntil) return;
        var expiry = functionalAuthorizedUntil;

        using var cancellation = new CancellationTokenSource();
        functionalCancellation = cancellation;
        busy = true;
        completedClear = null;
        completedEvap = null;
        completedJ1979 = null;
        completedFunctionalChecks = [];
        functionalRunAll.Enabled = functionalRunJ1979.Enabled = functionalRunEvap.Enabled = functionalRunClear.Enabled =
            functionalGoogleSignIn.Enabled = functionalExport.Enabled = functionalEvapVin.Enabled = false;
        functionalCancel.Enabled = true;
        functionalResults.Text = J1979RecipeText("ClearRunning");
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
                return device.RunStandardClearRecipe(expectedVin, warning == DialogResult.OK,
                    yes == DialogResult.Yes, RequireActive);
            });
            // Preserve evidence if the user cancels after possible TX.
            // Expired owner sessions may not expose the diagnostic payload.
            if (DateTimeOffset.UtcNow >= expiry)
            {
                functionalResults.Text = J1979RecipeText("ClearExpired");
                return;
            }
            var report = new WindowsClearReport("camarodiag-functional-j1979-clear-v1",
                "SAE-J1979", typeof(BridgeDesktopForm).Assembly.GetName().Version?.ToString() ?? "",
                expectedVin, selected.Adapter.DisplayName, options.ProtocolNumber,
                options.RequestCanId.ToString("X"), options.ResponseCanId.ToString("X"),
                started, DateTimeOffset.UtcNow, result);
            var output = new StringBuilder(J1979RecipeText("ClearOutcome_" + result.Outcome));
            output.AppendLine().AppendLine(J1979RecipeText("ClearNotCompletion"));
            if (result.Reason is not null) output.AppendLine(result.Reason);
            foreach (var observation in result.Observations)
                output.AppendLine(observation.Stage + " " + observation.RequestHex + " -> " +
                    (observation.ResponseHex ?? J1979RecipeText("Unavailable")) + " " +
                    (observation.Interpretation ?? observation.Error ?? ""));
            functionalResults.Text = output.ToString();
            completedClear = report;
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
            functionalRunAll.Enabled = functionalRunJ1979.Enabled = functionalRunEvap.Enabled = functionalRunClear.Enabled =
                DateTimeOffset.UtcNow < functionalAuthorizedUntil;
        }
    }

    private void ExportWindowsClear(WindowsClearReport report)
    {
        using var dialog = new SaveFileDialog
        {
            Filter = J1979RecipeText("JsonFilter"), AddExtension = true, DefaultExt = "json",
            FileName = "CamaroDiag_SAE-J1979_CLEAR.json", OverwritePrompt = true
        };
        if (dialog.ShowDialog(this) != DialogResult.OK ||
            DateTimeOffset.UtcNow >= functionalAuthorizedUntil ||
            !ReferenceEquals(completedClear, report)) return;
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
