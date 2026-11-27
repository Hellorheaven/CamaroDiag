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
        string? selectedOriginal = null;
        BridgeRecoveryReport.Assessment? displayed = null;
        var export = Button(BridgeRecoveryPresentation.Text("ExportAssessment"));
        export.Enabled = false;
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
            selectedOriginal = null;
            displayed = null;
            verifyOriginal.Enabled = export.Enabled = false;
            results.Text = "Analyse hors ligne…";
            choose.Enabled = false;
            try
            {
                await RunBusyAsync(async () =>
                {
                    var assessment = await Task.Run(() => BridgeRecoveryReport.Inspect(dialog.FileName));
                    displayed = assessment;
                    results.Lines = RecoveryAssessmentLines(assessment);
                });
                if (results.Text == "Analyse hors ligne…")
                    results.Text = "Journal non analysable. Aucune conclusion sur l'état du calculateur.";
            }
            finally {
                choose.Enabled = true;
                verifyOriginal.Enabled = displayed?.Backup.Verified == true && displayed.Target.Verified;
                export.Enabled = displayed?.Journal.Sha256 is not null;
            }
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
            verifyOriginal.Enabled = export.Enabled = false;
            var journal = selectedJournal;
            var bin = imageDialog.FileName;
            try
            {
                await RunBusyAsync(async () =>
                {
                    var previous=displayed;
                    displayed=null;
                    selectedOriginal=null;
                    results.Text=BridgeRecoveryPresentation.Text("Inspecting");
                    var assessment=await Task.Run(()=>BridgeRecoveryReport.Inspect(journal,bin));
                    // Compare the journal/backup identity with the prior
                    // display; never append a new comparison to an old view.
                    if(previous is null || previous.Journal.Sha256!=assessment.Journal.Sha256 ||
                        previous.Backup.SnapshotSha256!=assessment.Backup.SnapshotSha256 ||
                        previous.Target!=assessment.Target)
                        throw new InvalidDataException(BridgeRecoveryPresentation.Text("EvidenceChanged"));
                    displayed=assessment;
                    selectedOriginal=bin;
                    results.Lines=RecoveryAssessmentLines(assessment);
                });
            }
            finally {
                verifyOriginal.Enabled = displayed?.Backup.Verified == true && displayed.Target.Verified;
                export.Enabled = displayed?.Journal.Sha256 is not null;
                if(displayed is null)results.Text=BridgeRecoveryPresentation.Text("EvidenceChanged");
            }
        };
        export.Click += async (_, _) =>
        {
            if(busy || selectedJournal is null || displayed is null)return;
            using var save = new SaveFileDialog {
                Title=BridgeRecoveryPresentation.Text("ExportAssessment"),
                Filter=BridgeRecoveryPresentation.Text("AssessmentJsonFilter"),
                FileName="CamaroDiag_E39_Recovery.json",AddExtension=true,DefaultExt="json",
                OverwritePrompt=true
            };
            if(save.ShowDialog(this)!=DialogResult.OK)return;
            var journal=selectedJournal;
            var bin=selectedOriginal;
            var previous=displayed;
            export.Enabled=verifyOriginal.Enabled=false;
            try {
                await RunBusyAsync(async ()=>{
                    try {
                        var current=await Task.Run(()=>BridgeRecoveryReport.ExportCurrent(save.FileName,journal,bin,previous));
                        displayed=current;
                        results.Lines=RecoveryAssessmentLines(current);
                        results.AppendText(Environment.NewLine+BridgeRecoveryPresentation.Text("AssessmentExported"));
                    } catch(InvalidDataException) {
                        displayed=null;selectedOriginal=null;
                        results.Text=BridgeRecoveryPresentation.Text("EvidenceChanged");
                    }
                });
            } finally {
                verifyOriginal.Enabled=displayed?.Backup.Verified==true && displayed.Target.Verified;
                export.Enabled=displayed?.Journal.Sha256 is not null;
            }
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
            selectedOriginal = null;
            displayed = null;
            verifyOriginal.Enabled = export.Enabled = false;
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
        actions.Controls.Add(export);
        layout.Controls.Add(actions, 0, 1);
        layout.Controls.Add(results, 0, 2);
        tab.Controls.Add(layout);
        return tab;
    }

    private static string[] RecoveryAssessmentLines(BridgeRecoveryReport.Assessment report)
    {
        string T(string key)=>BridgeRecoveryPresentation.Text(key);
        string F(string key,params object[] values)=>string.Format(
            System.Globalization.CultureInfo.CurrentUICulture,T(key),values);
        var audit=report.Journal;
        var backup=report.Backup;
        var lines=new List<string>{
            F("JournalName",report.JournalName),
            F("JournalState",ExplainRecoveryState(Enum.Parse<NativeE39FileWriteJournal.CompletionState>(audit.State))),
            audit.SequenceValid?F("JournalCounts",audit.PreparedSectors,audit.WrittenSectors,audit.FinalReadbackSectors):
                T("JournalInvalid"),
            F("LastStage",audit.LastPhase??T("Unknown"))
        };
        if(audit.PossiblyIncompleteAddress is uint address)lines.Add(F("FailedSector",address));
        lines.Add(report.Target.Verified?F("TargetIdentity",report.Target.Family!,report.Target.Vin!,report.Target.OsId!):
            F("TargetUnverified",report.Target.Problem));
        lines.Add(F("BackupStatus",backup.Verified?T("IntegrityVerified"):T("IntegrityUnverified"),
            backup.Sectors.Count,backup.ExpectedSectors));
        if(!backup.Verified)lines.Add(backup.Problem);
        if(backup.SnapshotSha256 is not null)lines.Add(F("Snapshot",backup.SnapshotSha256));
        if(backup.SiliconGeometryMatches.HasValue)lines.Add(backup.SiliconGeometryMatches.Value?
            T("GeometryMatches"):T("GeometryUnqualified"));
        foreach(var sector in backup.Sectors)lines.Add(F("Sector",sector.Address,sector.Length,sector.OriginalSha256));
        if(report.OriginalBin is { } comparison){
            lines.Add("");
            lines.Add(F("OriginalBinName",report.OriginalBinName??""));
            lines.Add(comparison.Verified?F("OriginalBinMatched",comparison.VerifiedSectors):
                F("OriginalBinRejected",comparison.Problem));
            if(comparison.FailedSectorAddress is uint failed)lines.Add(F("FailedSector",failed));
        }
        // Previous extensive technical explanations remain in source history;
        // only decision-relevant state and safety limitations are displayed.
        lines.Add(T("OfflineNotice"));
        return lines.ToArray();
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
