namespace CamaroDiag.J2534Bridge;

/// <summary>ISO 14229 19/02 DTC mask correlation; no actual ECU exercised.</summary>
internal static class ObdOnUdsDtcStatusSelfTests
{
    internal static int Run()
    {
        var tests = 0;
        void Check(bool ok, string title)
        {
            tests++;
            if (!ok) throw new InvalidOperationException("UDS DTC mask: " + title);
        }
        void Reject<T>(Action action, string title) where T : Exception
        {
            var failed = false;
            try { action(); }
            catch (T) { failed = true; }
            Check(failed, title);
        }

        Check(ObdOnUdsServices.ReadDtcByStatusMask(0x05)
            .SequenceEqual(new byte[] { 0x19, 0x02, 0x05 }),
            "Status-mask request keeps selected bits, including for DoIP.");
        var result = ObdOnUdsServices.DecodeDtcByStatusMask(
            0x05, [0x59, 0x02, 0x0F,
                0x12, 0x34, 0x56, 0x01,
                0x11, 0x22, 0x33, 0x04]);
        Check(result.AvailabilityMask == 0x0F &&
            result.Codes.Count == 2 &&
            result.Codes[0].Code == 0x123456 &&
            result.Codes[1].Status == 0x04,
            "Multiple DTCs are correlated by OR of selected status bits.");
        Check(ObdOnUdsServices.DecodeDtcByStatusMask(
            0x01, [0x59, 0x02, 0x01]).Codes.Count == 0,
            "An empty, well-formed DTC response remains empty.");
        Check(ObdOnUdsServices.DecodeDtcByStatusMask(
            0x80, [0x59, 0x02, 0x01]).Codes.Count == 0,
            "Unavailable status bit can yield a valid empty response.");

        Reject<InvalidDataException>(() =>
            ObdOnUdsServices.DecodeDtcByStatusMask(
                0x01, [0x59, 0x02, 0x0F, 0x12, 0x34, 0x56, 0x02]),
            "A DTC with no requested status bit must not match.");
        Reject<InvalidDataException>(() =>
            ObdOnUdsServices.DecodeDtcByStatusMask(
                0xFF, [0x59, 0x02, 0x01, 0x12, 0x34, 0x56, 0x02]),
            "DTC status bits outside ECU's availability mask rejected.");
        Reject<InvalidDataException>(() =>
            ObdOnUdsServices.DecodeDtcByStatusMask(
                0x01, [0x59, 0x02, 0x01,
                    0x12, 0x34, 0x56, 0x01,
                    0x12, 0x34, 0x56, 0x01]),
            "Duplicate DTC records from the same reply rejected.");
        Reject<InvalidDataException>(() =>
            ObdOnUdsServices.DecodeDtcByStatusMask(
                0x01, [0x59, 0x02, 0x01, 0x12, 0x34, 0x56]),
            "Incomplete DTC record rejected.");
        Reject<InvalidDataException>(() =>
            ObdOnUdsServices.DecodeDtcByStatusMask(
                0x01, [0x59, 0x01, 0x01]),
            "Count subfunction is not mistaken for status-list subfunction.");
        return tests;
    }
}
