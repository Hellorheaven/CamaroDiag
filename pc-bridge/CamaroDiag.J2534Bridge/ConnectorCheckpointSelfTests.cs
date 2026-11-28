using System.Buffers.Binary;

namespace CamaroDiag.J2534Bridge;

internal static class ConnectorCheckpointSelfTests
{
    internal static void Run()
    {
        static void Require(bool value)
        {
            if (!value) throw new InvalidOperationException("CDJ/1 self-test failed");
        }
        // Identical synthetic fixture in firmware and Android.
        var wire = Convert.FromHexString("43444a0102000000010000000102030405060708090a0b0c0d0e0f10202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3fa75e21b6");
        var inspected = ConnectorCheckpoint.Inspect(wire);
        Require(inspected.Status == ConnectorCheckpoint.Disposition.RecoveryRequired &&
            inspected.Record is { Stage: ConnectorCheckpoint.Stage.EraseIntent, Sequence: 1 });
        Require(!inspected.Authenticated && !inspected.PermitsProgramming && !inspected.PermitsAutomaticRecovery);
        Require(ConnectorCheckpoint.Inspect(wire, "00").Status == ConnectorCheckpoint.Disposition.IdentityMismatch);
        Require(ConnectorCheckpoint.Inspect(wire[..63]).Status == ConnectorCheckpoint.Disposition.UnavailableOrCorrupt);
        wire[30]^=1;
        Require(ConnectorCheckpoint.Inspect(wire).Status == ConnectorCheckpoint.Disposition.UnavailableOrCorrupt);
        wire[30]^=1;
        var directory=Path.Combine(Path.GetTempPath(),"camarodiag-cdj-report-"+Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        try{
            var input=Path.Combine(directory,"checkpoint.bin");
            var output=Path.Combine(directory,"report.json");
            File.WriteAllBytes(input,wire);
            var record=inspected.Record!;
            var options=ConnectorCheckpoint.Parse([ConnectorCheckpoint.Command,input,"--transaction",
                record.TransactionHex.ToUpperInvariant(),"--manifest",record.ManifestSha256,"--output",output]);
            Require(options.Transaction==record.TransactionHex&&options.Manifest==record.ManifestSha256);
            var assessment=ConnectorCheckpoint.AssessFile(input,options.Transaction,options.Manifest);
            Require(assessment.SnapshotSha256 is {Length:64}&&
                assessment.Inspection.Status==ConnectorCheckpoint.Disposition.RecoveryRequired);
            Require(!assessment.Authenticated&&!assessment.PhysicalVehicleVerified&&!assessment.PermitsProgramming);
            ConnectorCheckpoint.ExportCurrent(output,input,assessment);
            using(var json=System.Text.Json.JsonDocument.Parse(File.ReadAllBytes(output))){
                Require(json.RootElement.GetProperty("inspection").GetProperty("status").GetString()=="RecoveryRequired");
                Require(!json.RootElement.GetProperty("permitsAutomaticRecovery").GetBoolean());
            }
            bool refused=false;
            try{ConnectorCheckpoint.ExportCurrent(output,input,assessment);}catch(IOException){refused=true;}
            Require(refused);
            var wrong=ConnectorCheckpoint.AssessFile(input,new string('A',32),record.ManifestSha256);
            Require(wrong.Inspection.Status==ConnectorCheckpoint.Disposition.IdentityMismatch);
            var altered=wire.ToArray();altered[30]^=1;
            File.WriteAllBytes(input,altered);
            refused=false;
            var stale=Path.Combine(directory,"stale.json");
            try{ConnectorCheckpoint.ExportCurrent(stale,input,assessment);}catch(InvalidDataException){refused=true;}
            Require(refused&&!File.Exists(stale));
            var corrupt=ConnectorCheckpoint.AssessFile(input);
            Require(corrupt.SnapshotSha256 is {Length:64}&&
                corrupt.Inspection.Status==ConnectorCheckpoint.Disposition.UnavailableOrCorrupt);
            ConnectorCheckpoint.ExportCurrent(stale,input,corrupt);
            File.WriteAllBytes(input,wire[..63]);
            Require(ConnectorCheckpoint.AssessFile(input).SnapshotSha256 is null);
            refused=false;
            try{ConnectorCheckpoint.Parse([ConnectorCheckpoint.Command,input,"--manifest","00"]);}
            catch(ArgumentException){refused=true;}
            Require(refused);
            Require(File.ReadAllBytes(input).SequenceEqual(wire[..63]));
        }finally{Directory.Delete(directory,true);}
        for (byte stage=1;stage<=10;stage++)
        {
            for (uint seq=0;seq<=9;seq++)
            {
                wire[4]=stage;
                BinaryPrimitives.WriteUInt32LittleEndian(wire.AsSpan(8),seq);
                BinaryPrimitives.WriteUInt32LittleEndian(wire.AsSpan(60),ConnectorBinaryDiscovery.Crc32(wire.AsSpan(0,60)));
                bool reachable = stage==9?seq==1:stage==10?seq>=2&&seq<=7:seq==(uint)stage-1;
                var result=ConnectorCheckpoint.Inspect(wire);
                Require((result.Status!=ConnectorCheckpoint.Disposition.UnavailableOrCorrupt)==reachable);
                Require(!result.PermitsProgramming&&!result.PermitsAutomaticRecovery);
            }
        }
    }
}
