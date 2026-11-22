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
