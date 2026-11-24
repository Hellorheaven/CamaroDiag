namespace CamaroDiag.J2534Bridge;

/// <summary>
/// A strictly scoped interpretation of one SAE J1979 application reply after
/// real ISO-TP transport. This is independent of the actual adapter/vehicle:
/// no VIN, PID scaling or ECU capability is inferred without a valid reply.
/// </summary>
internal static class ObdJ1979Interpretation
{
    internal sealed record Result(string Description, bool Positive, byte Service);

    internal static Result Interpret(ReadOnlySpan<byte> request,
        ReadOnlySpan<byte> response)
    {
        // A positive reply for a different PID/monitor/test is not proof
        // of success even when the service byte itself matches.
        var echoesIdentifier = request.Length >= 2 &&
            request[0] is 0x01 or 0x02 or 0x06 or 0x08 or 0x09;
        var reply = ObdJ1979Transaction.Decode(request, response,
            expectIdentifierEcho: echoesIdentifier);
        if (reply.Kind == ObdJ1979Transaction.ReplyKind.Negative)
            return new Result(
                $"SAE J1979 service 0x{reply.Service:X2} negative response NRC=0x{reply.NegativeResponseCode:X2}",
                false, reply.Service);

        if (request.Length == 2 && request[0] == 0x06)
        {
            if (request[1] % 0x20 == 0)
            {
                var page = ObdJ1979Mode06.DecodeAvailability(request, response);
                return new Result(
                    $"SAE J1979 Mode 06 MID page=0x{page.BaseId:X2} " +
                    $"supported={string.Join(",", page.MonitorIds.Select(x => x.ToString("X2")))} " +
                    $"next={page.HasNextPage}", true, reply.Service);
            }
            var tests = ObdJ1979Mode06.DecodeTests(request, response);
            return new Result(
                $"SAE J1979 Mode 06 MID=0x{request[1]:X2} " +
                $"tests={tests.Count} " +
                string.Join("; ", tests.Select(t =>
                    $"TID=0x{t.TestId:X2} UASID=0x{t.UnitScalingId:X2} " +
                    $"raw={t.Value} min={t.Minimum} max={t.Maximum}")) +
                " (raw values; no UASID conversion or pass verdict)",
                true, reply.Service);
        }

        if (request.Length == 2 && request[0] == 0x01 && request[1] == 0x01)
        {
            var status = ObdJ1979MonitorStatus.Decode(response);
            return new Result(ObdJ1979MonitorStatus.Format(status), true, reply.Service);
        }

        if (request[0] is 0x01 or 0x02 &&
            request.Length >= 2 && request[1] <= 0xE0 &&
            request[1] % 0x20 == 0)
        {
            var page = ObdJ1979PidSupport.Decode(request, response);
            return new Result(
                $"SAE J1979 mode 0x{page.Mode:X2} PID page=0x{page.RequestedBase:X2} " +
                $"supported={string.Join(",", page.SupportedPids.Select(x => x.ToString("X2")))} " +
                $"next={page.HasNextPage}" +
                (page.FreezeFrameNumber is byte n ? $" freeze-frame={n}" : ""),
                true, reply.Service);
        }

        if (request.Length == 2 && request[0] == 0x09 && request[1] == 0x00)
        {
            var types = ObdJ1979VehicleInfo.DecodeSupported(response);
            return new Result(
                $"SAE J1979 Mode 09 available InfoTypes=" +
                string.Join(",", types.InfoTypes.Select(x => x.ToString("X2"))) +
                $" next={types.HasNextPage}", true, reply.Service);
        }
        if (request.Length == 2 && request[0] == 0x09 && request[1] == 0x04)
        {
            var identifiers = ObdJ1979VehicleInfo.DecodeCalibrationIds(response);
            return new Result(
                $"SAE J1979 CALID count={identifiers.Identifiers.Count} values=" +
                string.Join(",", identifiers.Identifiers), true, reply.Service);
        }
        if (request.Length == 2 && request[0] == 0x09 && request[1] == 0x06)
        {
            var values = ObdJ1979VehicleInfo.DecodeCvns(response);
            return new Result(
                $"SAE J1979 CVN count={values.HexValues.Count} raw=" +
                string.Join(",", values.HexValues) +
                " (not manufacturer authenticity evidence)", true, reply.Service);
        }

        if (request.Length == 2 && request[0] == 0x09 && request[1] == 0x0A)
        {
            var name = ObdJ1979VehicleInfo.DecodeEcuName(response);
            return new Result($"SAE J1979 ECU={name}", true, reply.Service);
        }

        if (request.Length == 2 && request[0] == 0x09 && request[1] == 0x02)
        {
            var vin = ObdJ1979Vin.DecodeIsoTp(response);
            return new Result($"SAE J1979 VIN={vin}", true, reply.Service);
        }

        if (request.Length == 1 && request[0] is 0x03 or 0x07 or 0x0A)
        {
            var codes = ObdJ1979Dtc.DecodeCanCounted(reply.Data);
            return new Result(
                $"SAE J1979 DTC mode=0x{reply.Service:X2} " +
                $"count={codes.Count} codes={string.Join(",", codes.Select(x => x.Display))}",
                true, reply.Service);
        }

        if (request[0] is 0x01 or 0x02 &&
            request.Length == (request[0] == 0x02 ? 3 : 2))
        {
            var value = ObdJ1979PidValues.Decode(request, response);
            if (value is not null)
                return new Result(ObdJ1979PidValues.Format(value),
                    true, reply.Service);
        }

        // Unknown PID value remains raw. Refrain from manufacturing units,
        // defaulting an absent value to zero, or treating a positive answer
        // as proof that the requested actuation/programming took effect.
        return new Result(
            $"SAE J1979 positive response mode=0x{reply.Service:X2} " +
            $"data={Convert.ToHexString(reply.Data)} (raw, no scaling or ECU effect verified)",
            true, reply.Service);
    }
}
