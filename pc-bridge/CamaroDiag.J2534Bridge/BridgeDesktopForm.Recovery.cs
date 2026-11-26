using System.Drawing;
using System.Windows.Forms;

namespace CamaroDiag.J2534Bridge;

internal sealed partial class BridgeDesktopForm
{
    private TabPage BuildRecoveryTab()
    {
        var tab = new TabPage("Récupération ECU");
        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill, ColumnCount = 1, RowCount = 3
        };
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        var instructions = new Label
        {
            AutoSize = true, Dock = DockStyle.Fill, Padding = new Padding(8),
            Text = "Analyse hors ligne d'un ancien journal d'écriture ECU. " +
                "Aucune connexion, aucun reset, aucune écriture ou reprise automatique. " +
                "Conservez toujours le fichier original et le journal."
        };
        var choose = Button("Ouvrir et analyser un journal");
        var chooseConnector = Button("Ouvrir un checkpoint CDJ/1");
        var verifyOriginal = Button(BridgeRecoveryPresentation.Text("VerifyOriginalButton"));
        verifyOriginal.Enabled = false;
        string? selectedJournal = null;
        var results = new TextBox
        {
            Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical,
            Dock = DockStyle.Fill, Font = new Font(FontFamily.GenericMonospace, 10),
            Text = "Aucun journal sélectionné."
        };
        choose.Click += async (_, _) =>
        {
            if (busy) return;
            using var dialog = new OpenFileDialog
            {
                Title = "Choisir un journal de programmation ECU existant",
                Filter = "Journal ECU (*.jsonl)|*.jsonl|Tous les fichiers (*.*)|*.*",
                CheckFileExists = true, Multiselect = false
            };
            if (dialog.ShowDialog(this) != DialogResult.OK) return;
            selectedJournal = dialog.FileName;
            verifyOriginal.Enabled = true;
            results.Text = "Analyse hors ligne…";
            choose.Enabled = false;
            try
            {
                await RunBusyAsync(async () =>
                {
                    var bundle = await Task.Run(() =>
                        NativeE39SavedBlockAssessment.InspectBundle(dialog.FileName));
                    var savedBlocks = bundle.Blocks;
                    var target = bundle.Target;
                    var report = savedBlocks.JournalInspection ??
                        new NativeE39FileWriteJournal.Inspection(savedBlocks.JournalState, false);
                    var lines = new List<string>
                    {
                        "Fichier : " + Path.GetFileName(dialog.FileName),
                        "État du journal : " + ExplainRecoveryState(report.State),
                        "Ce résultat décrit le journal, pas l'état physique actuel du calculateur."
                    };
                    if (report.SequenceValid)
                    {
                        lines.Add("");
                        lines.Add($"Secteurs préparés : {report.PreparedSectors}");
                        lines.Add($"Secteurs écrits et vérifiés : {report.WrittenSectors}");
                        lines.Add($"Secteurs relus en fin de transaction : {report.FinalReadbackSectors}");
                        lines.Add("Dernière étape : " + (report.LastPhase ?? "aucune"));
                    }
                    else
                        lines.Add("Séquence non validée : les compteurs ne constituent pas une preuve.");
                    if (report.PossiblyIncompleteAddress is uint address)
                        lines.Add($"Secteur potentiellement incomplet : 0x{address:X8}");
                    lines.Add("");
                    lines.Add("Identité déclarée dans le dossier de récupération : " +
                        (target.Verified ? $"{target.Family} · VIN {target.Vin} · OSID {target.OsId}" :
                            "NON VÉRIFIÉE (" + target.Problem + ")"));
                    lines.Add("La déclaration sauvegardée ne remplace pas une identification ECU en direct.");
                    lines.Add("Sauvegardes PHYSIQUES des secteurs originaux : " +
                        (savedBlocks.Verified ? "INTÈGRES" : "NON VÉRIFIÉES"));
                    lines.Add($"Secteurs originaux contrôlés : {savedBlocks.Blocks.Count}/{savedBlocks.ExpectedSectors}");
                    if (savedBlocks.SnapshotSha256 is not null)
                        lines.Add($"SHA-256 des preuves inspectées : {savedBlocks.SnapshotSha256}");
                    if (!savedBlocks.Verified)
                        lines.Add("Anomalie sauvegarde : " + savedBlocks.Problem);
                    else
                    {
                        lines.Add("Géométrie secteurs silicium MPC5566 : " +
                            (EcuPostFlashAssessment.MatchesSiliconEraseGeometry(savedBlocks.Blocks)
                                ? "COHÉRENTE (non qualifiée pour cette carte ECU)"
                                : "INCOHÉRENTE / NON QUALIFIÉE"));
                        foreach (var block in savedBlocks.Blocks)
                            lines.Add($"Original 0x{block.Address:X8} · {block.Length} octets · SHA-256 {block.OriginalSha256}");
                    }
                    lines.Add("Aucun secteur ne sera restauré automatiquement.");
                    lines.Add("Recette véhicule après écriture : identité ECU, checksum OEM, DTC avant/après, " +
                        "démarrage, ralenti, contrôles statiques, essai conduit par l'opérateur.");
                    lines.Add("Avant restauration : confirmer l'état physique, les blocs, l'outil, " +
                        "le mode récupération et une NOUVELLE autorisation OK puis Oui/Non.");
                    lines.Add("");
                    lines.Add(report.RequiresAssessment
                        ? "Évaluation manuelle nécessaire : conserver les preuves, vérifier l'alimentation, " +
                          "l'identité et le moyen de récupération documenté avant toute autre opération."
                        : "La fin de transaction est enregistrée. Cela ne certifie ni le fonctionnement du " +
                          "véhicule ni la possibilité de restaurer le calculateur.");
                    results.Lines = lines.ToArray();
                });
                if (results.Text == "Analyse hors ligne…")
                    results.Text = "Journal non analysable. Aucune conclusion sur l'état du calculateur.";
            }
            finally { choose.Enabled = true; }
        };
        verifyOriginal.Click += async (_, _) =>
        {
            if (busy || selectedJournal is null) return;
            using var imageDialog = new OpenFileDialog
            {
                Title = BridgeRecoveryPresentation.Text("ChooseOriginalTitle"),
                Filter = BridgeRecoveryPresentation.Text("OriginalBinFilter"),
                CheckFileExists = true, Multiselect = false
            };
            if (imageDialog.ShowDialog(this) != DialogResult.OK) return;
            verifyOriginal.Enabled = false;
            var journal = selectedJournal;
            var bin = imageDialog.FileName;
            try
            {
                await RunBusyAsync(async () =>
                {
                    var comparison = await Task.Run(() =>
                        NativeE39RecoveryImageAssessment.Inspect(journal, bin));
                    var lines = results.Lines.ToList();
                    lines.Add("");
                    lines.Add(string.Format(
                        System.Globalization.CultureInfo.CurrentUICulture,
                        BridgeRecoveryPresentation.Text("OriginalBinName"),
                        Path.GetFileName(bin)));
                    lines.Add(comparison.Verified
                        ? string.Format(System.Globalization.CultureInfo.CurrentUICulture,
                            BridgeRecoveryPresentation.Text("OriginalBinMatched"),
                            comparison.VerifiedSectors)
                        : string.Format(System.Globalization.CultureInfo.CurrentUICulture,
                            BridgeRecoveryPresentation.Text("OriginalBinRejected"),
                            comparison.Problem));
                    if (comparison.FailedSectorAddress is uint failedAddress)
                        lines.Add(string.Format(System.Globalization.CultureInfo.CurrentUICulture,
                            BridgeRecoveryPresentation.Text("FailedSector"),
                            failedAddress));
                    lines.Add(BridgeRecoveryPresentation.Text("OfflineNotice"));
                    results.Lines = lines.ToArray();
                });
            }
            finally { verifyOriginal.Enabled = selectedJournal is not null; }
        };
        chooseConnector.Click += async (_, _) =>
        {
            if (busy) return;
            using var dialog = new OpenFileDialog
            {
                Title = "Choisir un checkpoint CDJ/1 exporté (64 octets)",
                Filter = "Checkpoint binaire (*.bin)|*.bin|Tous les fichiers (*.*)|*.*",
                CheckFileExists = true, Multiselect = false
            };
            if (dialog.ShowDialog(this) != DialogResult.OK) return;
            // CDJ/1 is not an E39 write journal; do not retain the previous
            // E39 selection when showing unrelated connector evidence.
            selectedJournal = null;
            verifyOriginal.Enabled = false;
            results.Text = "Analyse CDJ/1 hors ligne…";
            chooseConnector.Enabled = choose.Enabled = false;
            try
            {
                await RunBusyAsync(async () =>
                {
                    var report = await Task.Run(() => ConnectorCheckpoint.InspectFile(dialog.FileName));
                    var lines = new List<string>
                    {
                        "Fichier : " + Path.GetFileName(dialog.FileName),
                        "État CDJ/1 : " + report.Status,
                        "Authentification : NON. Aucun accès physique autorisé."
                    };
                    if (report.Record is { } record)
                    {
                        lines.Add("Étape : " + record.Stage);
                        lines.Add("Séquence : " + record.Sequence);
                        lines.Add("Transaction : " + record.TransactionHex);
                        lines.Add("SHA-256 du manifeste : " + record.ManifestSha256);
                    }
                    lines.Add("Reprise automatique et programmation interdites. Vérifier les preuves et l'état réel.");
                    results.Lines = lines.ToArray();
                });
                if (results.Text == "Analyse CDJ/1 hors ligne…")
                    results.Text = "Checkpoint non analysable. Aucune conclusion sur la cible.";
            }
            finally { chooseConnector.Enabled = choose.Enabled = true; }
        };
        layout.Controls.Add(instructions, 0, 0);
        var actions = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true };
        actions.Controls.Add(choose);
        actions.Controls.Add(verifyOriginal);
        actions.Controls.Add(chooseConnector);
        layout.Controls.Add(actions, 0, 1);
        layout.Controls.Add(results, 0, 2);
        tab.Controls.Add(layout);
        return tab;
    }

    private static string ExplainRecoveryState(NativeE39FileWriteJournal.CompletionState state) =>
        state switch
        {
            NativeE39FileWriteJournal.CompletionState.NoEvents => "Aucune opération enregistrée",
            NativeE39FileWriteJournal.CompletionState.Completed => "Fin de transaction enregistrée",
            NativeE39FileWriteJournal.CompletionState.RecoveryRequired => "Récupération / évaluation requise",
            _ => "Interrompu ou journal incomplet"
        };
}
