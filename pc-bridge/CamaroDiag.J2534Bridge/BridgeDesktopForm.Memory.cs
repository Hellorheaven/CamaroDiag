using System.Drawing;
using System.Globalization;
using System.Text.Json;
using System.Windows.Forms;

namespace CamaroDiag.J2534Bridge;

internal sealed partial class BridgeDesktopForm
{
    private sealed class ScopedTransport(J2534Device device) :
        NativeScopedMemoryReader.ITransport
    {
        public Task<byte[]> ExchangeAsync(byte[] request, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            var response = device.Exchange(request, 6000);
            // J2534 Exchange is synchronous: cancellation can occur while
            // PassThruReadMsgs is blocked. A late reply must not be accepted.
            token.ThrowIfCancellationRequested();
            return Task.FromResult(response);
        }
    }

    private readonly TextBox memoryVin = new() { Dock = DockStyle.Fill, MaxLength = 17 };
    private readonly TextBox memoryStart = new()
        { Dock = DockStyle.Fill, Text = "" }; // No unverified GM address defaults
    private readonly NumericUpDown memoryLength = new()
    {
        Dock = DockStyle.Fill, Minimum = 1,
        Maximum = NativeScopedMemoryReader.MaximumUiCaptureBytes, Value = 256
    };
    private readonly NumericUpDown memoryBlock = new()
        { Dock = DockStyle.Fill, Minimum = 1, Maximum = 256, Value = 64 };
    private readonly ComboBox memorySession = new()
        { Dock = DockStyle.Fill, DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly ComboBox memoryRegion = new()
        { Dock = DockStyle.Fill, DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly Label memoryStatus = new()
        { Dock = DockStyle.Fill, AutoSize = true, Text = "Profil non défini." };
    private readonly Button memoryCreateProfile = Button("Créer le profil JSON");
    private readonly Button memoryLoadProfile = Button("Ouvrir un profil existant");
    private readonly Button memoryVerifyArchive = Button("Vérifier une sauvegarde ZIP");
    private readonly Button memoryCapture = Button("Lire deux fois et sauvegarder");
    private readonly Button memoryCancel = Button("Annuler la lecture");
    private CancellationTokenSource? memoryCancellation;

    private TabPage BuildMemoryTab()
    {
        var tab = new TabPage("Lecture mémoire ECU");
        var fields = Grid();
        memorySession.Items.Add("01 - Diagnostic par défaut");
        memorySession.Items.Add("03 - Diagnostic étendu");
        memorySession.SelectedIndex = 0;
        foreach (var region in new[] {
            "OTHER", "CALIBRATION", "PROGRAM_FLASH", "DATA_FLASH", "EXTERNAL_EEPROM"
        }) memoryRegion.Items.Add(region);
        memoryRegion.SelectedIndex = 0;

        WideRow(fields, new Label
        {
            Text = "Lecture UDS 0x23 strictement limitée à une adresse vérifiée et " +
                "au VIN réellement lu dans le calculateur. Aucun découpage mémoire GM " +
                "n'est fourni par défaut. Deux lectures identiques sont obligatoires avant sauvegarde.",
            AutoSize = true, ForeColor = Color.DarkSlateGray
        }, 80);
        Row(fields, "VIN attendu (17 caractères)", memoryVin);
        Row(fields, "Adresse début (hex)", memoryStart);
        Row(fields, "Longueur de lecture (octets)", memoryLength);
        Row(fields, "Octets par bloc", memoryBlock);
        Row(fields, "Session autorisée", memorySession);
        Row(fields, "Région déclarée (non certifiée)", memoryRegion);
        var profileButtons = new FlowLayoutPanel { Dock = DockStyle.Fill };
        profileButtons.Controls.Add(memoryCreateProfile);
        profileButtons.Controls.Add(memoryLoadProfile);
        memoryCreateProfile.Click += (_, _) => SaveMemoryProfile();
        memoryLoadProfile.Click += (_, _) => LoadMemoryProfile();
        WideRow(fields, profileButtons, 48);
        var acquisitionButtons = new FlowLayoutPanel { Dock = DockStyle.Fill };
        acquisitionButtons.Controls.Add(memoryCapture);
        acquisitionButtons.Controls.Add(memoryVerifyArchive);
        memoryVerifyArchive.Click += async (_, _) => await VerifyMemoryArchiveAsync();
        acquisitionButtons.Controls.Add(memoryCancel);
        memoryCancel.Enabled = false;
        memoryCapture.Click += async (_, _) => await CaptureMemoryAsync();
        memoryCancel.Click += (_, _) =>
        {
            memoryCancel.Enabled = false;
            memoryStatus.Text = "Annulation demandée, attente de la fin du bloc en cours…";
            memoryCancellation?.Cancel();
        };
        WideRow(fields, acquisitionButtons, 48);
        WideRow(fields, memoryStatus, 62);
        WideRow(fields, new Label
        {
            Text = "L'interface, les CAN IDs et le profil de transport sont ceux du panneau " +
                "Console CAN/OBD. Pour la lecture mémoire, la correspondance avec le profil " +
                "et le VIN sera vérifiée avant le premier octet. " +
                "Aucun droit d'écriture ou SecurityAccess n'est accordé.",
            AutoSize = true, ForeColor = Color.DarkSlateGray
        }, 82);
        tab.Controls.Add(fields);
        return tab;
    }

    private static uint ParseUiHex(string text)
    {
        var value = text.Trim();
        if (value.StartsWith("0x", StringComparison.OrdinalIgnoreCase))
            value = value[2..];
        if (value.Length is < 1 or > 8 || value.Any(x => !Uri.IsHexDigit(x)) ||
            !uint.TryParse(value, NumberStyles.AllowHexSpecifier,
                CultureInfo.InvariantCulture, out var number))
            throw new ArgumentException("Adresse mémoire invalide : hexadécimal sur 1 à 8 chiffres.");
        return number;
    }

    private J2534MemoryReadScope CreateScopeFromForm()
    {
        var channel = NativeGmlanBenchProbe.Parse([
            NativeGmlanBenchProbe.Command, "--adapter-index",
            Math.Max(0, adapter.SelectedIndex).ToString(CultureInfo.InvariantCulture),
            "--request-can-id", consoleRequestId.Text.Trim(),
            "--response-can-id", consoleResponseId.Text.Trim(),
            "--protocol", consoleProtocol.SelectedItem?.ToString() ?? ""
        ]);
        return new J2534MemoryReadScope(
            memoryVin.Text.Trim().ToUpperInvariant(),
            channel.RequestCanId, channel.ResponseCanId, channel.ProtocolNumber,
            ParseUiHex(memoryStart.Text), checked((uint)memoryLength.Value),
            checked((int)memoryBlock.Value), memorySession.SelectedIndex == 1 ? 3 : 1,
            memoryRegion.SelectedItem?.ToString() ?? "OTHER");
    }

    private void SaveMemoryProfile()
    {
        if (bridgeTask is { IsCompleted: false })
        {
            ShowError("Arrêtez le pont avant de changer son profil mémoire.");
            return;
        }
        try
        {
            var scope = CreateScopeFromForm();
            using var picker = new SaveFileDialog
            {
                Title = "Créer un nouveau profil de lecture mémoire ECU",
                Filter = "Profil JSON (*.json)|*.json",
                FileName = "CamaroDiag-Memoire-ECU.json",
                AddExtension = true
            };
            if (!PrepareExportDialog(picker, UserExportStorage.Category.Profiles, "CamaroDiag-Memoire-ECU.json")) return;
            if (picker.ShowDialog(this) != DialogResult.OK) return;
            var json = JsonSerializer.Serialize(new
            {
                expectedVin = scope.ExpectedVin,
                requestCanIdHex = scope.RequestCanId.ToString("X", CultureInfo.InvariantCulture),
                responseCanIdHex = scope.ResponseCanId.ToString("X", CultureInfo.InvariantCulture),
                protocolNumber = scope.ProtocolNumber,
                startAddressHex = scope.StartAddress.ToString("X8", CultureInfo.InvariantCulture),
                lengthBytes = scope.Length,
                maxReadBytes = scope.MaxReadBytes,
                session = scope.Session,
                region = scope.Region
            }, new JsonSerializerOptions { WriteIndented = true });
            using (var file = new FileStream(picker.FileName,
                FileMode.CreateNew, FileAccess.Write, FileShare.None))
            using (var writer = new StreamWriter(file))
                writer.Write(json);
            // The real profile parser is the single source of authority.
            _ = J2534MemoryReadScope.Load(picker.FileName);
            memoryProfile.Text = picker.FileName;
            memoryStatus.Text = "Profil validé et activé : " + picker.FileName +
                ". Redémarrer le pont pour qu'Android puisse lire ce périmètre.";
        }
        catch (Exception ex) when (ex is ArgumentException or IOException or
            UnauthorizedAccessException or OverflowException or JsonException)
        {
            ShowError("Profil de lecture refusé : " + ex.Message);
        }
    }

    private void LoadMemoryProfile()
    {
        if (bridgeTask is { IsCompleted: false })
        {
            ShowError("Arrêtez le pont avant de remplacer le profil de lecture.");
            return;
        }
        using var picker = new OpenFileDialog
        {
            Title = "Choisir un profil de lecture ECU existant",
            Filter = "Profil JSON (*.json)|*.json",
            CheckFileExists = true
        };
        if (picker.ShowDialog(this) != DialogResult.OK) return;
        try
        {
            var scope = J2534MemoryReadScope.Load(picker.FileName);
            if (scope.Length > NativeScopedMemoryReader.MaximumUiCaptureBytes)
                throw new ArgumentException("La lecture Windows est limitée à 16 Mio.");
            memoryVin.Text = scope.ExpectedVin;
            memoryStart.Text = scope.StartAddress.ToString("X8", CultureInfo.InvariantCulture);
            memoryLength.Value = scope.Length;
            memoryBlock.Value = scope.MaxReadBytes;
            memorySession.SelectedIndex = scope.Session == 3 ? 1 : 0;
            memoryRegion.SelectedItem = scope.Region;
            consoleRequestId.Text = scope.RequestCanId.ToString("X", CultureInfo.InvariantCulture);
            consoleResponseId.Text = scope.ResponseCanId.ToString("X", CultureInfo.InvariantCulture);
            consoleProtocol.SelectedItem = scope.ProtocolNumber.ToString(CultureInfo.InvariantCulture);
            memoryProfile.Text = picker.FileName;
            memoryStatus.Text = "Profil chargé et validé : " + picker.FileName;
        }
        catch (Exception ex) when (ex is ArgumentException or IOException or
            UnauthorizedAccessException or JsonException or KeyNotFoundException or OverflowException)
        {
            ShowError("Profil invalide : " + ex.Message);
        }
    }

    private async Task VerifyMemoryArchiveAsync()
    {
        if (busy || memoryCancellation is not null) return;
        if (!File.Exists(memoryProfile.Text))
        {
            ShowError("Chargez d'abord le profil JSON correspondant à la sauvegarde.");
            return;
        }
        using var picker = new OpenFileDialog
        {
            Title = "Vérifier l'intégrité d'une sauvegarde ECU ZIP existante",
            Filter = "Archive ECU ZIP (*.zip)|*.zip",
            CheckFileExists = true
        };
        if (picker.ShowDialog(this) != DialogResult.OK) return;
        memoryVerifyArchive.Enabled = false;
        try
        {
            var profilePath = memoryProfile.Text;
            var archivePath = picker.FileName;
            var result = await Task.Run(() =>
            {
                var scope = J2534MemoryReadScope.Load(profilePath);
                return NativeScopedBackupImport.VerifyZip(archivePath, scope);
            });
            var missing = result.RequiredRegions
                .Where(region => region != result.DeclaredRegion).ToArray();
            memoryStatus.Text = "Archive vérifiée sans contacter l'ECU : " +
                result.ByteCount + " octets, SHA-256 " + result.Sha256 +
                ". Deux copies identiques, VIN lié au profil. " +
                (result.ChannelBound
                    ? "Canal CAN et session liés au profil."
                    : "Ancien format sans preuve de canal CAN/session.") +
                " Région déclarée : " +
                result.DeclaredRegion + ", origine déclarée : " + result.DeclaredOrigin + ". " +
                (result.RegionBound
                    ? "Région liée au type déclaré par le profil."
                    : "Type de région non qualifié dans le profil de lecture.") + " " +
                (result.Firmware is { } firmware
                    ? $"OSID GM déclaré dans l'archive : {firmware.OperatingSystemId} (DID 0x{firmware.OsDid:X2}). "
                    : "OSID GM non enregistré. ") +
                (missing.Length == 0 ? "" :
                    "Régions requises absentes de ce ZIP : " + string.Join(", ", missing) + ". ") +
                "Cette vérification ne prouve ni la couverture ECU complète ni la restaurabilité.";
        }
        catch (Exception ex) when (ex is IOException or InvalidDataException or ArgumentException or
            UnauthorizedAccessException or OverflowException or JsonException or
            NotSupportedException or InvalidOperationException)
        {
            memoryStatus.Text = "Archive refusée : " + ex.Message;
        }
        finally
        {
            memoryVerifyArchive.Enabled = true;
      private async Task CaptureMemoryAsync()
    {
        if (busy) return;
        if (bridgeTask is { IsCompleted: false } || consoleDevice is not null)
        {
            ShowError("Fermez la console et arrêtez le pont avant la lecture mémoire locale.");
            return;
        }
        if (adapter.SelectedItem is not AdapterChoice chosen)
        {
            ShowError("Sélectionnez une interface J2534 dans Connexion Android.");
            return;
        }

        J2534MemoryReadScope scope;
        try
        {
            if (!File.Exists(memoryProfile.Text))
                throw new ArgumentException("Créez ou ouvrez un profil mémoire JSON valide.");
            scope = J2534MemoryReadScope.Load(memoryProfile.Text);
            if (scope.Length > NativeScopedMemoryReader.MaximumUiCaptureBytes)
                throw new ArgumentException("Le captureur Windows est limité à 16 Mio.");
            var input = CreateScopeFromForm();
            if (input.ExpectedVin != scope.ExpectedVin ||
                !scope.MatchesChannel(input.RequestCanId, input.ResponseCanId,
                    input.ProtocolNumber) ||
                input.StartAddress != scope.StartAddress ||
                input.Length != scope.Length ||
                input.MaxReadBytes != scope.MaxReadBytes ||
                input.Session != scope.Session ||
                input.Region != scope.Region)
                throw new UnauthorizedAccessException(
                    "Les champs de l'écran diffèrent du profil JSON : régénérez ou rechargez le profil.");
        }
        catch (Exception ex) when (ex is IOException or ArgumentException or
            UnauthorizedAccessException or OverflowException or JsonException)
        {
            ShowError("Lecture mémoire refusée : " + ex.Message);
            return;
        }

        if (MessageBox.Show(this, "Cette opération va lire deux fois la zone mémoire " +
            "explicitement renseignée, sans écrire dans l'ECU. Vérifiez le VIN, " +
            "la tension d'alimentation et les adresses avant de continuer.",
            "Lecture ECU réelle — confirmation", MessageBoxButtons.OKCancel,
            MessageBoxIcon.Warning) != DialogResult.OK) return;

        using var destination = new SaveFileDialog
        {
            Title = "Nouvelle sauvegarde vérifiable de la zone lue",
            Filter = "Archive ECU vérifiable Android/Windows (*.zip)|*.zip",
            AddExtension = true,
            FileName = "CamaroDiag-Memoire-ECU.zip"
        };
        if (!PrepareExportDialog(destination, UserExportStorage.Category.EcuBackups, "CamaroDiag-Memoire-ECU.zip")) return;
        if (destination.ShowDialog(this) != DialogResult.OK) return;
        if (!Path.GetExtension(destination.FileName).Equals(".zip", StringComparison.OrdinalIgnoreCase))
        {
            ShowError("La sauvegarde vérifiable doit conserver les deux lectures et le manifeste dans un ZIP.");
            return;
        }
        if (File.Exists(destination.FileName))
        {
            ShowError("Choisissez un nouveau fichier : une sauvegarde ne doit jamais être écrasée.");
            return;
        }

        memoryCancellation = new CancellationTokenSource();
        var cancellation = memoryCancellation;
        memoryCancel.Enabled = true;
        memoryCapture.Enabled = false;
        var progressActive = true;
        var progress = new Progress<(int Pass, int BytesRead, int TotalBytes)>(state =>
        {
            // Progress<T> marshals updates onto the UI dispatcher. Old updates
            // queued before failure/cancellation cannot overwrite its status.
            if (!progressActive) return;
            memoryStatus.Text = $"Lecture {state.Pass}/2 : " +
                $"{state.BytesRead:N0}/{state.TotalBytes:N0} octets.";
        });
        try
        {
            await RunBusyAsync(async () =>
            {
                try
                {
                var result = await Task.Run(async () =>
                {
                    using var device = new J2534Device(chosen.Adapter, scope);
                    var can29 = scope.ProtocolNumber is 34 or 36 or 64;
                    device.Open(scope.RequestCanId, scope.ResponseCanId, can29,
                        scope.ProtocolNumber); // VIN F190 physically checked inside Open()
                    // OSID is optional: some readable ECUs do not implement
                    // legacy GM 0x1A. Never invent a firmware version from VIN.
                    NativeGmlanIdentification.Identity? before = null;
                    try { before = device.ReadGmlanIdentification(); }
                    catch (Exception error) when (error is IOException or
                        InvalidDataException or TimeoutException)
                    {
                        cancellation.Token.ThrowIfCancellationRequested();
                        if (!device.IsOpen) throw;
                    }
                    if (before is not null && before.Vin != scope.ExpectedVin)
                        throw new UnauthorizedAccessException(
                            "GM OSID identity disagrees with the memory profile VIN.");
                    var link = new ScopedTransport(device);
                    var capture = await NativeScopedMemoryReader.ReadWithSessionAsync(
                        link, scope, progress, cancellation.Token);
                    NativeScopedBackupArchive.FirmwareIdentity? firmware = null;
                    if (before is not null)
                    {
                        cancellation.Token.ThrowIfCancellationRequested();
                        // The second identity read must agree exactly with the
                        // first before any OSID is written to the archive.
                        var after = device.ReadGmlanIdentification();
                        var stable = NativeGmlanBenchProbe.RequireStableIdentity(before, after);
                        if (stable.Vin != scope.ExpectedVin)
                            throw new UnauthorizedAccessException(
                                "GM identity changed after both ECU memory acquisitions.");
                        firmware = new NativeScopedBackupArchive.FirmwareIdentity(
                            stable.OperatingSystemId, stable.OsDid);
                    }
                    cancellation.Token.ThrowIfCancellationRequested();
                    return (Capture: capture, Firmware: firmware);
                }, cancellation.Token);
                // Cancellation after the final read must not create a file.
                cancellation.Token.ThrowIfCancellationRequested();
                await Task.Run(() =>
                {
                    NativeScopedBackupArchive.SaveNew(
                        destination.FileName, scope, result.Capture, cancellation.Token,
                        result.Firmware);
                    // The GUI now requires ZIP, not an unverifiable raw BIN.
                    // Inspect the published archive once more offline.
                    NativeScopedBackupArchive.RequirePublishedCaptureMatch(
                        destination.FileName, scope, result.Capture, result.Firmware);
                }, cancellation.Token);
                progressActive = false;
                memoryStatus.Text = "Sauvegarde de plage ECU vérifiée par deux lectures : " +
                    result.Capture.Bytes.Length + " octets, SHA-256 " + result.Capture.Sha256 +
                    ". Couverture limitée à la zone demandée, pas à tout le calculateur. " +
                    (result.Firmware is { } firmware
                        ? $"OSID GM lu deux fois : {firmware.OperatingSystemId} (DID 0x{firmware.OsDid:X2}). "
                        : "OSID GM non disponible sur ce chemin de lecture. ") +
                    "Les deux lectures et le manifeste sont conservés dans le ZIP.");
                }
                catch (OperationCanceledException)
                {
                    progressActive = false;
                    memoryStatus.Text = "Lecture ECU annulée : aucune nouvelle archive validée.";
                    throw;
                }
                catch (Exception error)
                {
                    progressActive = false;
                    memoryStatus.Text = error is
                        NativeScopedMemoryReader.MemoryReadRefusedException denied
                        ? $"Zone ECU non lisible : adresse 0x{denied.Address:X8}, " +
                          $"{denied.RequestedBytes} octets, refus NRC 0x{denied.Nrc:X2}. " +
                          "Aucune sauvegarde créée."
                        : "Sauvegarde ECU non validée : " + error.Message;
                    throw;
                }
            });
        }
        finally
        {
            progressActive = false;
            memoryCancel.Enabled = false;
            memoryCapture.Enabled = true;
            memoryCancellation = null;
            cancellation.Dispose();
        }
    }
}
