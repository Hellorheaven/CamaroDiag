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
        string directory = Path.Combine(Path.GetTempPath(), "CamaroDiag-CDJ-" + Guid.NewGuid());
        Directory.CreateDirectory(directory);
        try
        {
            string file = Path.Combine(directory, "checkpoint.cdj");
            Check(ConnectorCheckpointCapture.Save(file, bytes!).Status == ConnectorCheckpoint.Disposition.RecoveryRequired);
            Check(File.ReadAllBytes(file).SequenceEqual(bytes!));
            bool refused=false;
            try { ConnectorCheckpointCapture.Save(file, bytes!); }
            catch (IOException) { refused=true; }
            Check(refused);
        }
        finally { Directory.Delete(directory, recursive:true); }
    }
}
