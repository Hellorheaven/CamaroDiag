namespace CamaroDiag.J2534Bridge;

/// <summary>Offline SAE J1979 PID 01 vectors; does not read a real ECU.</summary>
internal static class ObdJ1979MonitorStatusSelfTests
{
    internal static int Run()
    {
        var tests = 0;
        void Check(bool ok, string name)
        {
            tests++;
            if (!ok) throw new InvalidOperationException("J1979 readiness: " + name);
        }
        void Reject<T>(Action action, string name) where T : Exception
        {
            var caught = false;
            try { action(); }
            catch (T) { caught = true; }
            Check(caught, name);
        }
        var milOn = ObdJ1979MonitorStatus.Decode(
            [0x41, 0x01, 0x83, 0x08, 0xA5, 0x5A]);
        Check(milOn.MilOn && milOn.ConfirmedDtcCount == 3 &&
            milOn.CompressionIgnition &&
            milOn.MonitorByteB == 0x08 &&
            milOn.MonitorByteC == 0xA5 &&
            milOn.MonitorByteD == 0x5A,
            "MIL and exact 7-bit DTC count with compression flag.");
        Check(ObdJ1979MonitorStatus.Format(milOn)
            .Contains("confirmed-DTCs=3") &&
            ObdJ1979MonitorStatus.Format(milOn)
            .Contains("readiness-BCD=08A55A"),
            "Raw monitor flags retained without invented readiness result.");
        var noDtc = ObdJ1979MonitorStatus.Decode(
            [0x41, 0x01, 0x00, 0x00, 0x00, 0x00]);
        Check(!noDtc.MilOn && noDtc.ConfirmedDtcCount == 0 &&
            !noDtc.CompressionIgnition,
            "Zero DTC and MIL OFF are preserved.");
        Reject<InvalidDataException>(() =>
            ObdJ1979MonitorStatus.Decode(
                [0x41, 0x01, 0x80, 0x08, 0xA5]),
            "Missing readiness byte rejected.");
        Reject<InvalidDataException>(() =>
            ObdJ1979MonitorStatus.Decode(
                [0x41, 0x00, 0x80, 0x08, 0xA5, 0x5A]),
            "Wrong PID cannot impersonate monitor status.");
        Reject<InvalidDataException>(() =>
            ObdJ1979MonitorStatus.Decode(
                [0x7F, 0x01, 0x12]),
            "NRC cannot become a status value.");
        return tests;
    }
}
