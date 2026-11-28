namespace CamaroDiag.J2534Bridge;

/// <summary>
/// The standardized Mode 08 TID 01 evaporative-system test on one
/// documented physical CAN ECM. No generic actuator dispatch.
/// A positive reply proves acceptance of the request, not test completion.
/// </summary>
internal static class ObdJ1979EvapTest
{
    internal sealed record ExchangeEvidence(string RequestHex, string? ResponseHex);
    internal sealed record Result(string Outcome, bool CommandAttempted,
        string? ObservedVin, string? Reason, IReadOnlyList<ExchangeEvidence> Exchanges);

    internal static Result Run(string expectedVin, bool primaryPhysicalChannel,
        bool warningAcknowledged, bool confirmedYes,
        Action requireCurrent, Func<byte[], int, byte[]> read,
        Func<byte[]> transmitApprovedTest)
    {
        if (!System.Text.RegularExpressions.Regex.IsMatch(expectedVin ?? "",
                @"\A[A-HJ-NPR-Z0-9]{17}\z") ||
            !primaryPhysicalChannel || !warningAcknowledged || !confirmedYes)
            throw new UnauthorizedAccessException("Physical ECM identity and two independent confirmations required.");
        ArgumentNullException.ThrowIfNull(requireCurrent);
        ArgumentNullException.ThrowIfNull(read);
        ArgumentNullException.ThrowIfNull(transmitApprovedTest);
        var exchanges = new List<ExchangeEvidence>();
        string? vin = null;
        bool attempted = false;

        byte[] Read(byte[] request)
        {
            requireCurrent();
            if (!StandardObdPhysicalPolicy.AllowsRead(request))
                throw new InvalidOperationException("Non-read precondition request.");
            var reply = read(request, 1800);
            exchanges.Add(new(Convert.ToHexString(request), Convert.ToHexString(reply)));
            requireCurrent();
            return reply;
        }
        Result Reject(string reason) => new("NOT_TESTED", false, vin, reason, exchanges.ToArray());

        try
        {
            vin = ObdJ1979Vin.DecodeIsoTp(Read([9, 2]));
            if (!string.Equals(vin, expectedVin, StringComparison.Ordinal))
                return Reject("VIN_MISMATCH");
            var inventory = Read([8, 0]);
            if (inventory.Length != 6 || inventory[0] != 0x48 ||
                inventory[1] != 0 || (inventory[2] & 0x80) == 0)
                return Reject("TID_01_NOT_ADVERTISED");

            var speed = Read([1, 0x0D]);
            var rpm = Read([1, 0x0C]);
            if (!StandardObdPhysicalPolicy.CanClear(true, primaryPhysicalChannel, speed, rpm))
                return Reject("STATIONARY_EVIDENCE_REQUIRED");

            // No retry after this boundary, including timeout, malformed
            // response or cancellation. The ECU's actual state may be unknown.
            requireCurrent();
            attempted = true;
            exchanges.Add(new("0801", null));
            var response = transmitApprovedTest();
            exchanges[^1] = new("0801", Convert.ToHexString(response));
            if (response.AsSpan().SequenceEqual(new byte[] { 0x48, 0x01 }))
                return new("ACKNOWLEDGED", true, vin, null, exchanges.ToArray());
            if (response.Length == 3 && response[0] == 0x7F && response[1] == 8 &&
                response[2] != 0 && response[2] != 0x78)
                return new("REFUSED", true, vin, $"NRC_{response[2]:X2}", exchanges.ToArray());
            return new("UNCERTAIN", true, vin, "INVALID_OR_INCOMPLETE_RESPONSE", exchanges.ToArray());
        }
        catch (OperationCanceledException) when (!attempted) { throw; }
        catch (Exception error) when (error is IOException or TimeoutException or
            InvalidDataException or OperationCanceledException or InvalidOperationException)
        {
            return new(attempted ? "UNCERTAIN" : "NOT_TESTED", attempted, vin,
                error.GetType().Name, exchanges.ToArray());
        }
    }
}
