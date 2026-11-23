using System.Drawing;
using System.IO.Ports;
using System.Windows.Forms;

namespace CamaroDiag.J2534Bridge;

internal sealed partial class BridgeDesktopForm
{
    private TabPage BuildConnectorTab()
    {
        var tab = new TabPage("CamaroDiagConnectors");
        var panel = Grid();
        var ports = new ComboBox { Dock = DockStyle.Fill, DropDownStyle = ComboBoxStyle.DropDownList };
        var refresh = Button("Actualiser ports COM");
        var verify = Button("Tester le prototype CD/1");
        var confirmed = new CheckBox
        {
            AutoSize = true,
            Dock = DockStyle.Fill,
            Text = "Je confirme : carte CD/1 isolée, sans véhicule, ECU ni mémoire raccordés."
        };
        var report = new TextBox
        {
            Dock = DockStyle.Fill, Multiline = true, ReadOnly = true,
            ScrollBars = ScrollBars.Both, WordWrap = false, Font = new Font("Consolas", 10f),
            Text = "Découverte publique uniquement. Aucune connexion ECU, aucun relais réseau."
        };
        void UpdateButton() => verify.Enabled = confirmed.Checked && ports.SelectedItem is string;
        refresh.Click += (_, _) =>
        {
            if (busy || bridgeTask is { IsCompleted: false } || consoleDevice is not null) return;
            confirmed.Checked = false;
            ports.Items.Clear();
            try
            {
                foreach (var name in SerialPort.GetPortNames()
                    .Where(ConnectorDiscoveryCli.IsValidPort).OrderBy(p => p, StringComparer.OrdinalIgnoreCase))
                    ports.Items.Add(name);
            }
            catch (Exception error) { report.Text = "Ports COM indisponibles : " + error.Message; }
            ports.SelectedIndex = -1; // never auto-select or probe
            UpdateButton();
        };
        ports.SelectedIndexChanged += (_, _) =>
        {
            confirmed.Checked = false;
            UpdateButton();
        };
        confirmed.CheckedChanged += (_, _) => UpdateButton();
        verify.Click += async (_, _) =>
        {
            if (busy || bridgeTask is { IsCompleted: false } || consoleDevice is not null ||
                functionalCancellation is not null)
            {
                ShowError("Arrêtez le pont, la console et les autres opérations.");
                return;
            }
            if (!confirmed.Checked || ports.SelectedItem is not string chosen) return;
            confirmed.Checked = false; // require fresh consent for every probe
            refresh.Enabled = ports.Enabled = confirmed.Enabled = verify.Enabled = false;
            report.Text = "Découverte CD/1 du port sélectionné...";
            try
            {
                await RunBusyAsync(async () =>
                {
                    try
                    {
                        var lines = await Task.Run(() => ConnectorDiscoveryCli.VerifyPort(chosen));
                        report.Text = string.Join(Environment.NewLine, lines) + Environment.NewLine +
                            "Prototype reconnu (non authentifié). Accès véhicule désactivé.";
                    }
                    catch (Exception error)
                    {
                        report.Text = "Découverte impossible : " + error.Message +
                            Environment.NewLine + "Aucune capacité véhicule activée.";
                    }
                });
            }
            finally
            {
                if (!IsDisposed)
                {
                    refresh.Enabled = ports.Enabled = confirmed.Enabled = true;
                    UpdateButton();
                }
            }
        };
        WideRow(panel, new Label
        {
            AutoSize = true, ForeColor = Color.DarkSlateGray,
            Text = "Essai local du firmware expérimental CD/1. Redémarrer la carte isolée entre essais. " +
                "Ne jamais sélectionner un câble KKL, ELM ou un autre adaptateur. Aucun pilote J2534 requis."
        }, 75);
        Row(panel, "Port COM vérifié", ports, refresh);
        WideRow(panel, confirmed, 50);
        WideRow(panel, verify, 45);
        WideRow(panel, report, 240);
        UpdateButton();
        tab.Controls.Add(panel);
        return tab;
    }
}
