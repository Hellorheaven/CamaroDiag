namespace CamaroDiag.J2534Bridge;

/// <summary>Hardware-free 0x19/0x01 OBDonUDS request/reply and CLI vectors.</summary>
internal static class ObdOnUdsDtcCountSelfTests
{
    internal static int Run()
    {
        var count = 0;
        void Check(bool ok, string name)
        {
            count++;
            if (!ok) throw new InvalidOperationException("OBDonUDS DTC count: " + name);
        }
        void Reject<T>(Action act, string name) where T : Exception
        {
            var threw = false;
            try { act(); }
            catch (T) { threw = true; }
            Check(threw, name);
        }

        Check(ObdOnUdsServices.ReadDtcCountByStatusMask(0xFF)
            .SequenceEqual(new byte[] { 0x19, 0x01, 0xFF }),
            "Count request passes explicit status mask through standard UDS.");
        var found = ObdOnUdsServices.DecodeDtcCountByStatusMask(
            [0x59, 0x01, 0xFF, 0x01, 0x01, 0x02]);
        Check(found.StatusAvailabilityMask == 0xFF &&
            found.DtcFormatIdentifier == 1 && found.Count == 258,
            "DTC count is unsigned big-endian uint16 and retains format.");
        Check(ObdOnUdsServices.DecodeDtcCountByStatusMask(
            [0x59, 0x01, 0, 0, 0, 0]).Count == 0,
            "Zero-count reply is not manufactured faults.");
        Reject<InvalidDataException>(() =>
            ObdOnUdsServices.DecodeDtcCountByStatusMask(
                [0x59, 0x01, 0xFF, 0x01, 0x01]),
            "Truncated DTC count cannot become zero.");
        Reject<InvalidDataException>(() =>
            ObdOnUdsServices.DecodeDtcCountByStatusMask(
                [0x59, 0x02, 0xFF, 0x01, 0x01, 0x02]),
            "Different ReadDTC subfunction is rejected.");
        Reject<InvalidDataException>(() =>
            ObdOnUdsServices.DecodeDtcCountByStatusMask(
                [0x59, 0x01, 0xFF, 0x01, 0x01, 0x02, 0x00]),
            "Extra bytes cannot silently extend a UDS DTC count.");

        var canOptions = StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand,
            "--adapter-index", "0", "--baud", "500000", "--tx-id", "7E0",
            "--rx-id", "7E8", "--fd", "false",
            "--obd-dtc-count-mask", "255"
        ]);
        Check(canOptions.Request.SequenceEqual(new byte[] { 0x19, 0x01, 0xFF }) &&
            canOptions.ValidateUds, "Windows CAN CLI has correlated 19/01 route.");

        var ipOptions = StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand,
            "--host", "127.0.0.1", "--tester", "0E00", "--ecu", "1000",
            "--obd-dtc-count-mask", "255"
        ]);
        Check(ipOptions.Request.SequenceEqual(new byte[] { 0x19, 0x01, 0xFF }),
            "DoIP CLI can issue same standard DTC count request.");

        Reject<ArgumentException>(() => StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand,
            "--adapter-index", "0", "--baud", "500000", "--tx-id", "7E0",
            "--rx-id", "7E8", "--fd", "false", "--payload", "1901FF",
            "--obd-dtc-count-mask", "255"
        ]), "Two payload selectors must not send both requests.");

        return count;
    }
}
