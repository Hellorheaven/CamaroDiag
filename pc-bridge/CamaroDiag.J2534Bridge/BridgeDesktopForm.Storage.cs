using System.Windows.Forms;

namespace CamaroDiag.J2534Bridge;

internal sealed partial class BridgeDesktopForm
{
    // Keep explicit user-chosen locations supported. The initial choice is
    // always the correct category, not the last unrelated file dialog folder.
    private bool PrepareExportDialog(SaveFileDialog dialog, UserExportStorage.Category category, string filename)
    {
        try
        {
            dialog.InitialDirectory = UserExportStorage.EnsureCategory(UserExportStorage.DefaultRoot(), category);
            dialog.FileName = UserExportStorage.NewFileName(filename);
            dialog.RestoreDirectory = true;
            return true;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or ArgumentException
            or NotSupportedException)
        {
            ShowError("Dossier CamaroDiag indisponible : " + error.Message);
            return false;
        }
    }
}
