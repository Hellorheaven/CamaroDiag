using System.Buffers.Binary;

namespace CamaroDiag.J2534Bridge;

internal static class NativeE39KernelLinkSelfTests
{
    internal static int Run()
    {
        int checks = 0;
        void Assert(bool b, string why) { checks++; if (!b) throw new InvalidOperationException(why); }
        void Reject(Action action) {
            try { action(); } catch (Exception e) when (e is InvalidDataException or InvalidOperationException or ArgumentOutOfRangeException or UnauthorizedAccessException) { checks++; return; }
            throw new InvalidOperationException("Expected native kernel link rejection.");
        }
        byte[] handshake = [0x7D, 0, 0x43, 0x44, 0x33, 0x39, 1, 0, 28, 0];
        var requests = new List<byte[]>();
        bool corrupt = false;
        Task<byte[]> Exchange(byte[] req, CancellationToken token) {
            requests.Add(req.ToArray());
            if (req[0] == 0x3D) return Task.FromResult(handshake.ToArray());
            uint address = BinaryPrimitives.ReadUInt32BigEndian(req.AsSpan(2,4));
            int count = BinaryPrimitives.ReadUInt16BigEndian(req.AsSpan(6,2));
            byte[] response = new byte[count + 1];
            response[0] = 0x63;
            for (int i=0;i<count;i++) response[i+1]=(byte)(address+(uint)i);
            return Task.FromResult(corrupt ? response[..^1] : response);
        }
        Task<uint?> Supply(CancellationToken token) => Task.FromResult<uint?>(12500);
        var link = new NativeE39KernelLink(Exchange, Supply, 4);
        var token = CancellationToken.None;
        Reject(() => link.ReadFlashBytesAsync(0, 1, token).GetAwaiter().GetResult());
        link.ProbeAsync(token).GetAwaiter().GetResult();
        var data=link.ReadFlashBytesAsync(0x101, 9, token).GetAwaiter().GetResult();
        Assert(data.SequenceEqual(Enumerable.Range(1,9).Select(x=>(byte)x)), "Chunked physical readback");
        Assert(requests.Count==4 && requests[^1][7]==1, "Exact final chunk");
        Assert(link.ReadSupplyMillivoltsAsync(token).GetAwaiter().GetResult()==12500, "Independent supply");
        Reject(() => link.ReadFlashBytesAsync(0x2FFFFF, 2, token).GetAwaiter().GetResult());
        Reject(() => link.ExchangeAsync([0x11, 1], token).GetAwaiter().GetResult());
        corrupt=true;
        Reject(() => link.ReadFlashBytesAsync(0, 1, token).GetAwaiter().GetResult());
        Reject(() => link.ReadFlashBytesAsync(0, 1, token).GetAwaiter().GetResult());
        handshake[9]=1;
        var recovering=new NativeE39KernelLink(Exchange, Supply, 4);
        Reject(() => recovering.ProbeAsync(token).GetAwaiter().GetResult());
        return checks;
    }
}
