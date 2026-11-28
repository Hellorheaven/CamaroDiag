using System.Globalization;

namespace CamaroDiag.J2534Bridge;

internal static class BridgeRecoveryPresentationSelfTests
{
    internal static int Run()
    {
        var checks = 0;
        var keys = new[]
        {
            "VerifyOriginalButton", "ChooseOriginalTitle", "OriginalBinFilter",
            "OriginalBinName", "OriginalBinMatched", "OriginalBinRejected",
            "FailedSector", "OfflineNotice",
            "RecoveryTab", "RecoveryInstructions", "OpenJournal", "OpenCheckpoint", "NoSelection", "JournalFilter", "CheckpointFilter", "InspectionFailed", "ExportFailed", "CheckpointState", "CheckpointStage", "CheckpointTransaction", "CheckpointManifest", "CheckpointUnauthenticated", "Checkpoint_UnavailableOrCorrupt", "Checkpoint_RecoveryRequired", "Checkpoint_SafeButBlocked", "Checkpoint_IdentityMismatch", "StateNoEvents", "StateCompleted", "StateRecoveryRequired", "StateInterrupted", "RecoveryEvidenceOnly",
            "HistoryTitle", "HistoryAdd", "HistoryRemove", "HistoryUp", "HistoryDown", "HistoryInspect", "HistoryCancel", "HistoryVerified", "HistoryRejected", "HistoryFailedOrdinal", "HistorySnapshot", "HistoryTransition", "HistoryLatestState", "HistoryProblem_DuplicatePath", "HistoryProblem_SnapshotCount", "HistoryProblem_NotRegularJournal", "HistoryProblem_JournalSize", "HistoryProblem_IncompleteJournal", "HistoryProblem_UnverifiedSnapshot", "HistoryProblem_InvalidSequence", "HistoryProblem_JournalChanged", "HistoryProblem_CombinedJournalSize", "HistoryProblem_OriginalEvidenceChanged", "HistoryProblem_NotStrictExtension", "HistoryProblem_TerminalExtension", "HistoryProblem_NoCompleteNewRecord", "HistoryProblem_EvidenceChanged", "HistoryProblem_Cancelled", "HistoryProblem_EvidenceUnavailable", "HistoryProblem_ProtectedDestination",
            "ExportAssessment", "AssessmentJsonFilter", "Inspecting", "EvidenceChanged", "AssessmentExported", "JournalName", "JournalState", "JournalCounts", "JournalInvalid", "LastStage", "Unknown", "TargetIdentity", "TargetUnverified", "BackupStatus", "IntegrityVerified", "IntegrityUnverified", "Snapshot", "GeometryMatches", "GeometryUnqualified", "Sector"
        };
        foreach (var culture in new[] { CultureInfo.GetCultureInfo("fr"),
                    CultureInfo.GetCultureInfo("en") })
        {
            foreach (var key in keys)
            {
                if (string.IsNullOrWhiteSpace(BridgeRecoveryPresentation.Text(key, culture)))
                    throw new InvalidOperationException(
                        "Missing localized ECU recovery action: " + culture.Name + "/" + key);
                checks++;
            }
        }
        if (BridgeRecoveryPresentation.Text("VerifyOriginalButton",
                CultureInfo.GetCultureInfo("fr")) ==
            BridgeRecoveryPresentation.Text("VerifyOriginalButton",
                CultureInfo.GetCultureInfo("en")))
            throw new InvalidOperationException("Recovery GUI must have distinct FR/EN labels.");
        checks++;
        return checks;
    }
}
