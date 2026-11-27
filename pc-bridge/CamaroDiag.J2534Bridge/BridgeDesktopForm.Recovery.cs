using System.Drawing;
using System.Windows.Forms;

namespace CamaroDiag.J2534Bridge;

internal sealed partial class BridgeDesktopForm
{
    private TabPage BuildRecoveryTab()
    {
        string T(string key)=>BridgeRecoveryPresentation.Text(key);
        var tab = new TabPage(T("RecoveryTab"));
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
            Text = T("RecoveryInstructions")
        };
        var choose = Button(T("OpenJournal"));
        var chooseConnector = Button(T("OpenCheckpoint"));
        var verifyOriginal = Button(BridgeRecoveryPresentation.Text("VerifyOriginalButton"));
        verifyOriginal.Enabled = false;
        string? selectedJournal = null;
        string? selectedOriginal = null;
        BridgeRecoveryReport.Assessment? displayed = null;
        string? selectedCheckpoint=null;
        ConnectorCheckpoint.FileAssessment? displayedCheckpoint=null;
        var export = Button(BridgeRecoveryPresentation.Text("ExportAssessment"));
        export.Enabled = false;
        var results = new TextBox
        {
            Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical,
            Dock = DockStyle.Fill, Font = new Font(FontFamily.GenericMonospace, 10),
            Text = T("NoSelection")
        };
        choose.Click += async (_, _) =>
        {
            if (busy) return;
            using var dialog = new OpenFileDialog
            {
                Title = T("OpenJournal"),
                Filter = T("JournalFilter"),
                CheckFileExists = true, Multiselect = false
            };
            if (dialog.ShowDialog(this) != DialogResult.OK) return;
            selectedJournal = dialog.FileName;
            selectedCheckpoint=null;displayedCheckpoint=null;
            selectedOriginal = null;
            displayed = null;
            verifyOriginal.Enabled = export.Enabled = false;
            results.Text = T("Inspecting");
            choose.Enabled = false;
            try
            {
                await RunBusyAsync(async () =>
                {
                    var assessment = await Task.Run(() => BridgeRecoveryReport.Inspect(dialog.FileName));
                    displayed = assessment;
                    results.Lines = RecoveryAssessmentLines(assessment);
                });
                if (displayed is null)
                    results.Text = T("InspectionFailed");
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
            if(busy || (selectedJournal is null || displayed is null) &&
                (selectedCheckpoint is null || displayedCheckpoint is null))return;
            using var save = new SaveFileDialog {
                Title=BridgeRecoveryPresentation.Text("ExportAssessment"),
                Filter=BridgeRecoveryPresentation.Text("AssessmentJsonFilter"),
                FileName=displayedCheckpoint is null?"CamaroDiag_E39_Recovery.json":"CamaroDiag_CDJ_Checkpoint.json",
                AddExtension=true,DefaultExt="json",
                OverwritePrompt=true
            };
            if(save.ShowDialog(this)!=DialogResult.OK)return;
            var journal=selectedJournal;
            var bin=selectedOriginal;
            var previous=displayed;
            var checkpoint=selectedCheckpoint;
            var previousCheckpoint=displayedCheckpoint;
            export.Enabled=verifyOriginal.Enabled=false;
            try {
                await RunBusyAsync(async ()=>{
                    try {
                        if(checkpoint is not null && previousCheckpoint is not null){
                            var current=await Task.Run(()=>ConnectorCheckpoint.ExportCurrent(save.FileName,checkpoint,previousCheckpoint));
                            displayedCheckpoint=current;
                            results.Lines=CheckpointAssessmentLines(current);
                        }else if(journal is not null && previous is not null){
                            var current=await Task.Run(()=>BridgeRecoveryReport.ExportCurrent(save.FileName,journal,bin,previous));
                            displayed=current;
                            results.Lines=RecoveryAssessmentLines(current);
                        }else return;
                        results.AppendText(Environment.NewLine+BridgeRecoveryPresentation.Text("AssessmentExported"));
                    } catch(InvalidDataException) {
                        displayed=null;selectedOriginal=null;
                        displayedCheckpoint=null;
                        results.Text=T("EvidenceChanged");
                    } catch(IOException) {
                        ShowError(T("ExportFailed"));
                    } catch(UnauthorizedAccessException) {
                        ShowError(T("ExportFailed"));
                    }
                });
            } finally {
                verifyOriginal.Enabled=displayed?.Backup.Verified==true && displayed.Target.Verified;
                export.Enabled=displayed?.Journal.Sha256 is not null || displayedCheckpoint?.SnapshotSha256 is not null;
            }
        };
        chooseConnector.Click += async (_, _) =>
        {
            if (busy) return;
            using var dialog = new OpenFileDialog
            {
                Title = T("OpenCheckpoint"),
                Filter = T("CheckpointFilter"),
                CheckFileExists = true, Multiselect = false
            };
            if (dialog.ShowDialog(this) != DialogResult.OK) return;
            // CDJ/1 is not an E39 write journal; do not retain the previous
            // E39 selection when showing unrelated connector evidence.
            selectedJournal = null;
            selectedCheckpoint=dialog.FileName;
            displayedCheckpoint=null;
            selectedOriginal = null;
            displayed = null;
            verifyOriginal.Enabled = export.Enabled = false;
            results.Text = T("Inspecting");
            chooseConnector.Enabled = choose.Enabled = false;
            try
            {
                await RunBusyAsync(async () =>
                {
                    var report=await Task.Run(()=>ConnectorCheckpoint.AssessFile(dialog.FileName));
                    displayedCheckpoint=report;
                    results.Lines=CheckpointAssessmentLines(report);
                });
                if(displayedCheckpoint is null)results.Text=T("InspectionFailed");
            }
            finally {
                chooseConnector.Enabled=choose.Enabled=true;
                export.Enabled=displayedCheckpoint?.SnapshotSha256 is not null;
            }
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

    private static string[] CheckpointAssessmentLines(ConnectorCheckpoint.FileAssessment report)
    {
        string T(string key)=>BridgeRecoveryPresentation.Text(key);
        string F(string key,params object[] values)=>string.Format(
            System.Globalization.CultureInfo.CurrentUICulture,T(key),values);
        var lines=new List<string>{F("JournalName",report.FileName),
            F("CheckpointState",T("Checkpoint_"+report.Inspection.Status))};
        if(report.Inspection.Record is { } record){
            lines.Add(F("CheckpointStage",record.Stage,record.Sequence));
            lines.Add(F("CheckpointTransaction",record.TransactionHex));
            lines.Add(F("CheckpointManifest",record.ManifestSha256));
        }
        if(report.SnapshotSha256 is not null)lines.Add(F("Snapshot",report.SnapshotSha256));
        if(report.Problem is not null)lines.Add(report.Problem);
        lines.Add(T("CheckpointUnauthenticated"));
        lines.Add(T("OfflineNotice"));
        return lines.ToArray();
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
        // Former technical text is retained here, not displayed:
        // lines.Add("Ce résultat décrit le journal, pas l'état physique actuel du calculateur.");
        // lines.Add("La déclaration sauvegardée ne remplace pas une identification ECU en direct.");
        // lines.Add("Aucun secteur ne sera restauré automatiquement.");
        // lines.Add("Recette véhicule après écriture : identité ECU, checksum OEM, DTC avant/après, " +
        //     "démarrage, ralenti, contrôles statiques, essai conduit par l'opérateur.");
        // lines.Add("Avant restauration : confirmer l'état physique, les blocs, l'outil, " +
        //     "le mode récupération et une NOUVELLE autorisation OK puis Oui/Non.");
        // lines.Add(report.RequiresAssessment
        //     ? "Évaluation manuelle nécessaire : conserver les preuves, vérifier l'alimentation, " +
        //       "l'identité et le moyen de récupération documenté avant toute autre opération."
        //     : "La fin de transaction est enregistrée. Cela ne certifie ni le fonctionnement du " +
        //       "véhicule ni la possibilité de restaurer le calculateur.");
        lines.Add(T("RecoveryEvidenceOnly"));
        lines.Add(T("OfflineNotice"));
        return lines.ToArray();
    }

    private static string ExplainRecoveryState(NativeE39FileWriteJournal.CompletionState state) =>
        state switch
        {
            NativeE39FileWriteJournal.CompletionState.NoEvents => BridgeRecoveryPresentation.Text("StateNoEvents"),
            NativeE39FileWriteJournal.CompletionState.Completed => BridgeRecoveryPresentation.Text("StateCompleted"),
            NativeE39FileWriteJournal.CompletionState.RecoveryRequired => BridgeRecoveryPresentation.Text("StateRecoveryRequired"),
            _ => BridgeRecoveryPresentation.Text("StateInterrupted")
        };
}
