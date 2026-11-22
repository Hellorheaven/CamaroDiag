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
            results.Text = "Analyse hors ligne…";
            choose.Enabled = false;
            try
            {
                await RunBusyAsync(async () =>
                {
                    var report = await Task.Run(() =>
                        NativeE39FileWriteJournal.InspectDetailed(dialog.FileName));
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
        layout.Controls.Add(instructions, 0, 0);
        var actions = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true };
        actions.Controls.Add(choose);
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
