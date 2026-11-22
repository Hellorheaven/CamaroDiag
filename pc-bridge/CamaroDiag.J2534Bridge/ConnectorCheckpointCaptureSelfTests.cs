namespace CamaroDiag.J2534Bridge;

internal static class ConnectorCheckpointCaptureSelfTests
{
    internal static void Run()
    {
        const string fixture = "43444a0102000000010000000102030405060708090a0b0c0d0e0f10202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3fa75e21b6";
        static void Check(bool pass) { if (!pass) throw new InvalidOperationException("CDJ USB capture self-test"); }
        var bytes = ConnectorCheckpointCapture.Decode("CDJ/1 " + fixture + "\n");
        Check(bytes is not null && Convert.ToHexString(bytes).ToLowerInvariant() == fixture);
        Check(ConnectorCheckpointCapture.Decode("CDJ/1 NONE\n") is null);
        foreach (var bad in new[] {"CDJ/1 CORRUPT\n","CDJ/1 NONE\nextra","CDJ/1 "+fixture,
                     "CDJ/1 "+fixture+"\nextra","CDJ/1 "+new string('0',128)+"\n"})
        {
            bool rejected=false;
            try { ConnectorCheckpointCapture.Decode(bad); }
            catch (InvalidDataException) { rejected=true; }
            Check(rejected);
        }
        // A transient per-byte timeout must not fail an otherwise valid response.
        var wire = "CDJ/1 " + fixture + "\n";
        int offset = 0, attempts = 0;
        var captured = ConnectorCheckpointCapture.ReadResponse(
            timeout =>
            {
                Check(timeout > 0 && timeout <= 1500);
                if (++attempts <= 2) throw new TimeoutException("short serial timeout");
                return wire[offset++];
            }, () => 0, () => 500);
        Check(captured is not null && captured.SequenceEqual(bytes!));
        Check(attempts == wire.Length + 2);

        // An incomplete response cannot be mistaken for a missing journal.
        long elapsed = 0;
        bool deadlineRejected = false;
        try
        {
            ConnectorCheckpointCapture.ReadResponse(
                _ => { elapsed += 1000; throw new TimeoutException("serial"); },
                () => 0, () => elapsed);
        }
        catch (TimeoutException) { deadlineRejected = true; }
        Check(deadlineRejected && elapsed >= 3000);

        string directory = Path.Combine(Path.GetTempPath(), "CamaroDiag-CDJ-" + Guid.NewGuid());
        Directory.CreateDirectory(directory);
        try
        {
            string file = Path.Combine(directory, "checkpoint.cdj");
            Check(ConnectorCheckpointCapture.Save(file, bytes!).Status == ConnectorCheckpoint.Disposition.RecoveryRequired);
            Check(File.ReadAllBytes(file).SequenceEqual(bytes!));
            var corrupt = bytes!.ToArray();
            corrupt[30] ^= 1;
            var invalidPath = Path.Combine(directory, "corrupt.cdj");
            bool corruptRejected = false;
            try { ConnectorCheckpointCapture.Save(invalidPath, corrupt); }
            catch (InvalidDataException) { corruptRejected = true; }
            Check(corruptRejected && !File.Exists(invalidPath));

            bool refused=false;
            try { ConnectorCheckpointCapture.Save(file, bytes!); }
            catch (IOException) { refused=true; }
            Check(refused);
        }
        finally { Directory.Delete(directory, recursive:true); }
    }
}
