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
        var multiplePids = request.Length > 2 && request[0] == 0x01 ||
            request.Length > 3 && request[0] == 0x02;
        var echoesIdentifier = request.Length >= 2 &&
            request[0] is 0x01 or 0x02 or 0x06 or 0x08 or 0x09;
        var reply = ObdJ1979Transaction.Decode(request, response,
            expectIdentifierEcho: echoesIdentifier && !multiplePids);
        if (reply.Kind == ObdJ1979Transaction.ReplyKind.Negative)
            return new Result(
                $"SAE J1979 service 0x{reply.Service:X2} negative response NRC=0x{reply.NegativeResponseCode:X2}",
                false, reply.Service);

        if (multiplePids)
            return InterpretMultiplePids(request, reply);

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
                string.Join("; ", tests.Select(FormatMonitorTest)) +
                " (no monitor pass verdict)",
                true, reply.Service);
        }

        if (request.Length == 2 && request[0] == 0x01 && request[1] == 0x01)
        {
            var status = ObdJ1979MonitorStatus.Decode(response);
            return new Result(ObdJ1979MonitorStatus.Format(status), true, reply.Service);
        }

        if (request.Length == 3 && request[0] == 0x02 && request[1] == 0x02)
        {
            if (reply.Data.Length != 4 || reply.Data[1] != request[2])
                throw new InvalidDataException("Incorrect freeze-frame number or DTC length.");
            var code = System.Buffers.Binary.BinaryPrimitives.ReadUInt16BigEndian(reply.Data.AsSpan(2));
            var display = code == 0 ? "none" : ObdJ1979Dtc.Decode(code).Display;
            return new Result($"SAE J1979 freeze-frame={request[2]} DTC={display}", true, reply.Service);
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

        if (request.Length == 2 && request[0] == 9 &&
            ObdJ1979ExtendedVehicleInfo.IsExtended(request[1]))
        {
            var extended = ObdJ1979ExtendedVehicleInfo.Can(request[1],response);
            return new Result(ObdJ1979ExtendedVehicleInfo.Format(extended),true,reply.Service);
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
            if (request[0] == 1 && ObdJ1979Status.Width(request[1]) is not null)
            {
                var states=ObdJ1979Status.DecodeResponse(request,response)
                    ?? throw new InvalidDataException("Invalid categorical PID record.");
                if(ObdJ1979MultiSignal.Width(request[1]) is not null){
                    var values=ObdJ1979MultiSignal.DecodeResponse(request,response)
                        ?? throw new InvalidDataException("Invalid multi-signal PID record.");
                    return new Result(ObdJ1979Status.Format(states)+"; "+ObdJ1979MultiSignal.Format(values),true,reply.Service);
                }
                return new Result(ObdJ1979Status.Format(states),true,reply.Service);
            }
            if (request[0] == 1 && ObdJ1979MultiSignal.Width(request[1]) is not null)
            {
                var signals=ObdJ1979MultiSignal.DecodeResponse(request,response)
                    ?? throw new InvalidDataException("Invalid multi-signal PID record.");
                return new Result(ObdJ1979MultiSignal.Format(signals),true,reply.Service);
            }
            if(ObdJ1979OperatingContext.IsTrim(request[1])){
                var trim=ObdJ1979OperatingContext.DecodeTrim(request.ToArray(),response[(request[0]==2?3:2)..].ToArray(),null)
                    ?? throw new InvalidDataException("Invalid fuel trim width.");
                return new Result(string.Join("; ",trim.Signals.Select(signal=>signal.Name+"="+
                    (signal.Number is double number?number.ToString("0.###",CultureInfo.InvariantCulture)+" %":
                        "RAW="+signal.RawEncoded.ToString("X2",CultureInfo.InvariantCulture)))),true,reply.Service);
            }
            if(request[0]==1&&request[1]==0x41){
                var cycle=ObdJ1979OperatingContext.DecodeCycle(response[2..].ToArray(),null)
                    ?? throw new InvalidDataException("Invalid monitoring-cycle record.");
                return new Result("PID 41 RAW="+cycle.RawHex,true,reply.Service);
            }
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
    private static string FormatMonitorTest(ObdJ1979Mode06.Test test)
    {
        string encoded = $"TID=0x{test.TestId:X2} UASID=0x{test.UnitScalingId:X2} " +
            $"raw={test.Value} min={test.Minimum} max={test.Maximum}";
        if (test.Scaled is not { } value) return encoded;
        return encoded + FormattableString.Invariant(
            $" value={value.Value:G} min_scaled={value.Minimum:G} max_scaled={value.Maximum:G} unit={value.Unit}");
    }

    private static Result InterpretMultiplePids(ReadOnlySpan<byte> request, ObdJ1979Transaction.Reply reply)
    {
        var freeze = reply.Service == 0x02;
        var keySize = freeze ? 2 : 1;
        if ((request.Length - 1) % keySize != 0 || request.Length > 7)
            throw new ArgumentException("Invalid number of CAN Mode 01/02 request parameters.");

        var pending = new List<(byte Pid, byte Frame)>();
        for (int i = 1; i < request.Length; i += keySize)
            pending.Add((request[i], freeze ? request[i + 1] : (byte)0));
        if (pending.Any(k => k.Pid % 32 == 0) && pending.Any(k => k.Pid % 32 != 0))
            throw new ArgumentException("PID support pages and data values require separate requests.");
        if (reply.Data.Length == 0)
            throw new InvalidDataException("Missing SAE J1979 PID records.");

        // ECUs may omit unsupported parameters or return the requested records
        // in any order. Remove one occurrence per response, including duplicates.
        var records = new List<string>();
        for (var position = 0; position < reply.Data.Length;)
        {
            var field = reply.Data.AsSpan(position);
            if (field.Length < keySize) throw new InvalidDataException("Truncated PID identifier.");
            var pid = field[0];
            var frame = freeze ? field[1] : (byte)0;
            if (!pending.Remove((pid, frame)))
                throw new InvalidDataException("Unexpected PID, frame number or excess duplicate.");
            int? length = pid % 32 == 0 || (!freeze && pid == 0x01) ? 4 :
                freeze && pid == 0x02 ? 2 : ObdJ1979OperatingContext.IsTrim(pid)?null:ObdJ1979PidValues.DataLength(pid) ??
                    (freeze?null:ObdJ1979MultiSignal.Width(pid) ?? ObdJ1979Status.Width(pid));
            if (length is null)
            {
                // Without the data width there is no unambiguous next identifier.
                records.Add($"unparsed-from-PID={pid:X2} raw={Convert.ToHexString(field)}");
                return new Result(string.Join("; ", records), true, reply.Service);
            }
            var recordLength = keySize + length.Value;
            if (field.Length < recordLength)
                throw new InvalidDataException("Truncated SAE J1979 PID data.");
            byte[] singleRequest = freeze ? [reply.Service, pid, frame] : [reply.Service, pid];
            byte[] singleResponse = new byte[1 + recordLength];
            singleResponse[0] = (byte)(reply.Service + 0x40);
            field[..recordLength].CopyTo(singleResponse.AsSpan(1));
            records.Add(Interpret(singleRequest, singleResponse).Description);
            position += recordLength;
        }
        if (pending.Count > 0)
            records.Add("not-returned=" + string.Join(",", pending.Select(key =>
                freeze ? $"{key.Pid:X2}/{key.Frame:X2}" : $"{key.Pid:X2}")));
        return new Result(string.Join("; ", records), true, reply.Service);
    }

}
