using System.Drawing;
using System.Globalization;
using System.Windows.Forms;

namespace CamaroDiag.J2534Bridge;

internal sealed partial class BridgeDesktopForm
{
    // File dialog multiselection order is not chronology. Present a separate
    // explicit list with move/remove actions; never sort by timestamp or name.
    private string[]? ChooseRecoveryHistory()
    {
        string T(string key) => BridgeRecoveryPresentation.Text(key);
        using var selector = new Form
        {
            Text = T("HistoryTitle"),
            StartPosition = FormStartPosition.CenterParent,
            Size = new Size(800, 400),
            MinimumSize = new Size(480, 240),
            ShowInTaskbar = false,
            MinimizeBox = false,
            MaximizeBox = false
        };
        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            Padding = new Padding(8),
            ColumnCount = 1,
            RowCount = 3
        };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        var toolbar = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true };
        var list = new ListBox
        {
            Dock = DockStyle.Fill,
            SelectionMode = SelectionMode.One,
            HorizontalScrollbar = true,
            IntegralHeight = false
        };
        var footer = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            AutoSize = true,
            FlowDirection = FlowDirection.RightToLeft
        };
        Button Action(string key) => new() { Text = T(key), AutoSize = true };
        var add = Action("HistoryAdd");
        var remove = Action("HistoryRemove");
        var up = Action("HistoryUp");
        var down = Action("HistoryDown");
        var inspect = Action("HistoryInspect");
        var cancel = Action("HistoryCancel");
        cancel.DialogResult = DialogResult.Cancel;
        inspect.DialogResult = DialogResult.OK;
        selector.CancelButton = cancel;
        selector.AcceptButton = inspect;

        void RefreshActions()
        {
            var index = list.SelectedIndex;
            add.Enabled = list.Items.Count < BridgeRecoveryHistory.MaximumSnapshots;
            remove.Enabled = index >= 0;
            up.Enabled = index > 0;
            down.Enabled = index >= 0 && index < list.Items.Count - 1;
            inspect.Enabled = list.Items.Count >= 2;
        }

        void Move(int offset)
        {
            var index = list.SelectedIndex;
            var next = index + offset;
            if (index < 0 || next < 0 || next >= list.Items.Count) return;
            var item = list.Items[index];
            list.Items.RemoveAt(index);
            list.Items.Insert(next, item);
            list.SelectedIndex = next;
            RefreshActions();
        }

        add.Click += (_, _) =>
        {
            if (!add.Enabled) return;
            using var dialog = new OpenFileDialog
            {
                Title = T("HistoryAdd"),
                Filter = T("JournalFilter"),
                CheckFileExists = true,
                Multiselect = false
            };
            if (dialog.ShowDialog(selector) != DialogResult.OK) return;
            var path = Path.GetFullPath(dialog.FileName);
            if (list.Items.Cast<string>().Contains(path, StringComparer.OrdinalIgnoreCase))
            {
                MessageBox.Show(selector, T("HistoryProblem_DuplicatePath"), T("HistoryTitle"),
                    MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            list.SelectedIndex = list.Items.Add(path);
            RefreshActions();
        };
        remove.Click += (_, _) =>
        {
            var index = list.SelectedIndex;
            if (index < 0) return;
            list.Items.RemoveAt(index);
            list.SelectedIndex = Math.Min(index, list.Items.Count - 1);
            RefreshActions();
        };
        up.Click += (_, _) => Move(-1);
        down.Click += (_, _) => Move(1);
        list.SelectedIndexChanged += (_, _) => RefreshActions();

        toolbar.Controls.AddRange([add, remove, up, down]);
        footer.Controls.AddRange([cancel, inspect]);
        layout.Controls.Add(toolbar, 0, 0);
        layout.Controls.Add(list, 0, 1);
        layout.Controls.Add(footer, 0, 2);
        selector.Controls.Add(layout);
        RefreshActions();
        return selector.ShowDialog(this) == DialogResult.OK && list.Items.Count >= 2
            ? list.Items.Cast<string>().ToArray() : null;
    }

    private static string[] RecoveryHistoryLines(BridgeRecoveryHistory.Report report)
    {
        string T(string key) => BridgeRecoveryPresentation.Text(key);
        string F(string key, params object[] values) =>
            string.Format(CultureInfo.CurrentUICulture, T(key), values);
        string State(string state) =>
            Enum.TryParse<NativeE39FileWriteJournal.CompletionState>(state, out var parsed)
                ? ExplainRecoveryState(parsed) : T("Unknown");
        var lines = new List<string>
        {
            report.Verified ? F("HistoryVerified", report.Snapshots.Count) : T("HistoryRejected")
        };
        if (report.FailedOrdinal is int failed) lines.Add(F("HistoryFailedOrdinal", failed));
        if (report.ProblemCode is not null)
            lines.Add(T("HistoryProblem_" + report.ProblemCode));
        foreach (var snapshot in report.Snapshots)
        {
            lines.Add("");
            lines.Add(F("HistorySnapshot", snapshot.Ordinal, snapshot.JournalName,
                snapshot.Records, State(snapshot.Journal.State)));
            if (snapshot.Backup.SnapshotSha256 is not null)
                lines.Add(F("Snapshot", snapshot.Backup.SnapshotSha256));
            if (snapshot.Target.Verified)
                lines.Add(F("TargetIdentity", snapshot.Target.Family!, snapshot.Target.Vin!,
                    snapshot.Target.OsId!));
        }
        if (report.Transitions.Count != 0) lines.Add("");
        foreach (var transition in report.Transitions)
            lines.Add(F("HistoryTransition", transition.FromOrdinal, transition.ToOrdinal,
                transition.AddedRecords, transition.AddedBytes));
        if (report.Verified && report.LatestRecordedState is not null)
            lines.Add(F("HistoryLatestState", State(report.LatestRecordedState)));
        if (report.LatestRecordedUncertainAddress is uint address)
            lines.Add(F("FailedSector", address));
        lines.Add(T("RecoveryEvidenceOnly"));
        lines.Add(T("OfflineNotice"));
        return lines.ToArray();
    }
}
