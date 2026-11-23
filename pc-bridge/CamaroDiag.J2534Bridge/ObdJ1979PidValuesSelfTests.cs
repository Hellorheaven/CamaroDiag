namespace CamaroDiag.J2534Bridge;

/// <summary>Offline J1979 PID scaling vectors, no vehicle or actual J2534 driver.</summary>
internal static class ObdJ1979PidValuesSelfTests
{
    internal static int Run()
    {
        var count = 0;
        void Check(bool valid, string name)
        {
            count++;
            if (!valid) throw new InvalidOperationException("J1979 PID value: " + name);
        }
        void Reject<T>(Action action, string name) where T : Exception
        {
            var rejected = false;
            try { action(); }
            catch (T) { rejected = true; }
            Check(rejected, name);
        }
        static ObdJ1979PidValues.Value Read(byte pid, params byte[] values) =>
            ObdJ1979PidValues.Decode([0x01, pid],
                new byte[] { 0x41, pid }.Concat(values).ToArray())!;

        Check(Read(0x0C, 0x2E, 0xE0).Number == 3000 &&
            Read(0x0C, 0x2E, 0xE0).Unit == "rpm", "Engine RPM scaling.");
        Check(Read(0x0D, 100).Number == 100 &&
            Read(0x0D, 100).Unit == "km/h", "Vehicle speed scaling.");
        Check(Read(0x05, 0x28).Number == 0, "Coolant zero Celsius at 40 raw.");
        Check(Read(0x05, 0).Number == -40, "Coolant negative offset.");
        Check(Read(0x10, 1, 0).Number == 2.56, "MAF two-byte scaling.");
        Check(Read(0x06, 0x80).Number == 0, "STFT neutral point.");
        Check(Read(0x07, 0x80).Number == 0, "LTFT neutral point.");
        Check(Read(0x08, 0x80).Number == 0, "Bank 2 STFT neutral point.");
        Check(Read(0x09, 0x80).Number == 0, "Bank 2 LTFT neutral point.");
        Check(Read(0x52, 0xFF).Number == 100 &&
            Read(0x52, 0).Number == 0,
            "Ethanol 0..100 percent mapping.");
        Check(Read(0x11, 0xFF).Number == 100, "Throttle 100 percent.");
        Check(ObdJ1979PidValues.Decode([0x01, 0x90],
            [0x41, 0x90, 0x01]) is null,
            "No invented units for unknown manufacturer-defined PID.");
        Check(Math.Abs(Read(0x04, 0xFF).Number - 100) < 0.001,
            "Calculated load full-scale is 100 percent.");
        Check(Read(0x0E, 0x80).Number == 0 &&
            Read(0x0E, 0).Number == -64,
            "Timing advance offset formula.");
        Check(Read(0x0F, 0x28).Number == 0,
            "Intake air temperature Celsius offset.");
        Check(Read(0x1F, 0x01, 0x02).Number == 258,
            "Engine runtime two-byte big-endian seconds.");
        Check(Math.Abs(Read(0x2F, 0xFF).Number - 100) < 0.001,
            "Fuel tank level percentage.");
        Check(Read(0x33, 101).Number == 101,
            "Barometric pressure in kPa.");
        Check(Read(0x42, 0x30, 0x39).Number == 12.345,
            "Control module voltage in volts.");
        Check(Read(0x46, 0x28).Number == 0,
            "Ambient temperature Celsius offset.");
        Check(Read(0x5C, 0x78).Number == 80,
            "Engine oil temperature Celsius offset.");
        Check(Read(0x5E, 0x00, 0xC8).Number == 10,
            "Engine fuel rate in litres per hour.");
        Reject<InvalidDataException>(() =>
            ObdJ1979PidValues.Decode([0x01, 0x42],
                [0x41, 0x42, 0x30]),
            "Module voltage requires two bytes.");
        Reject<InvalidDataException>(() =>
            ObdJ1979PidValues.Decode([0x01, 0x5E],
                [0x41, 0x5E, 0x00, 0xC8, 0x00]),
            "Engine fuel rate rejects surplus bytes.");
        Check(ObdJ1979PidValues.Format(Read(0x0D, 100))
            .Contains("vehicle-speed=100 km/h"),
            "Invariant unit label for calibrated signal.");

        var freeze = ObdJ1979PidValues.Decode([0x02, 0x0C, 0x00],
            [0x42, 0x0C, 0x00, 0x0F, 0xA0]);
        Check(freeze is { Number: 1000, Mode: 0x02 },
            "Freeze-frame PID also carries and checks snapshot index.");

        Reject<InvalidDataException>(() =>
            ObdJ1979PidValues.Decode([0x01, 0x0C], [0x41, 0x0C, 0x2E]),
            "Truncated RPM rejected.");
        Reject<InvalidDataException>(() =>
            ObdJ1979PidValues.Decode([0x01, 0x0D], [0x41, 0x0D, 20, 0]),
            "Excess speed data rejected.");
        Reject<InvalidDataException>(() =>
            ObdJ1979PidValues.Decode([0x02, 0x0C, 0],
                [0x42, 0x0C, 1, 0x0F, 0xA0]),
            "Wrong freeze-frame rejected.");
        Reject<InvalidDataException>(() =>
            ObdJ1979PidValues.Decode([0x01, 0x0D], [0x7F, 0x01, 0x12]),
            "NRC must not be used as a sensor value.");
        Reject<InvalidDataException>(() =>
            ObdJ1979PidValues.Decode([0x01, 0x0D], [0x41, 0x0C, 20]),
            "Wrong echoed PID rejected.");
        return count;
    }
}
