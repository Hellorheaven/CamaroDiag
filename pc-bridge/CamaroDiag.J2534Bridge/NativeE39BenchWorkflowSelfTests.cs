using System.Buffers.Binary;
using System.Security.Cryptography;

namespace CamaroDiag.J2534Bridge;

internal static class NativeE39BenchWorkflowSelfTests
{
    private sealed class Journal : NativeE39WriteSequence.IDurableJournal
    {
        internal List<NativeE39WriteSequence.Event> Events = [];
        internal Dictionary<uint, byte[]> PhysicalBlocks = [];
        public Task PreserveOriginalBlockAsync(uint address, byte[] liveBytes,
            string sha256, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            if (!string.Equals(Convert.ToHexString(SHA256.HashData(liveBytes)),
                sha256, StringComparison.OrdinalIgnoreCase) ||
                !PhysicalBlocks.TryAdd(address, liveBytes.ToArray()))
                throw new InvalidDataException("Physical ECU block backup is invalid.");
            return Task.CompletedTask;
        }
        public Task AppendAsync(NativeE39WriteSequence.Event evidence, CancellationToken token)
        {
            token.ThrowIfCancellationRequested(); Events.Add(evidence); return Task.CompletedTask;
        }
    }

    // Hardware-free protocol transport, not a qualified physical programmer.
    private sealed class Bench(byte[] original, NativeGmlanIdentification.Identity identity)
        : NativeE39BenchWorkflow.ITransport
    {
        internal byte[] Memory = original.ToArray();
        internal int Erases, IdentityReads, Frames;
        internal bool AlterSecondIdentity, CorruptAfterWrite;
        internal Func<CancellationToken, Task>? BeforeFirstIdentity;
        internal CancellationTokenSource? CancelAfterFlashRead;
        internal CancellationTokenSource? CancelAfterRunningSupplyRead;
        private bool corruptDone, running;
        public async Task<NativeGmlanIdentification.Identity> ReadIdentityAsync(CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            var pause = BeforeFirstIdentity;
            BeforeFirstIdentity = null;
            if (pause is not null) await pause(token);
            token.ThrowIfCancellationRequested();
            IdentityReads++;
            return AlterSecondIdentity && IdentityReads == 2
                ? identity with { OperatingSystemId = identity.OperatingSystemId + 1 } : identity;
        }
        public Task<uint?> ReadSupplyMillivoltsAsync(CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            if (running) CancelAfterRunningSupplyRead?.Cancel();
            return Task.FromResult<uint?>(13500);
        }
        public Task SendAsync(byte[] request, CancellationToken token)
        { token.ThrowIfCancellationRequested(); Frames++; return Task.CompletedTask; }
        public Task DelayAsync(TimeSpan duration, CancellationToken token)
        { token.ThrowIfCancellationRequested(); return Task.CompletedTask; }

        public Task<byte[]> ExchangeAsync(byte[] request, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            Frames++;
            byte[] reply;
            if (!running)
            {
                reply = request[0] switch
                {
                    0x10 => [0x50,2], 0x28 => [0x68], 0x27 => [0x67,1,0,0],
                    0xA5 => [0xE5], 0x34 => [0x74], 0x36 when request[1] == 0x80 => [0x99],
                    0x36 => [0x76], _ => throw new InvalidOperationException("Unexpected staging request")
                };
                if (request[0] == 0x36 && request[1] == 0x80) running = true;
                return Task.FromResult(reply);
            }
            if (request.SequenceEqual(new byte[]{0x3D,0}))
                return Task.FromResult(new byte[]{0x7D,0,0x43,0x44,0x33,0x39,1,0,28,0});
            if (request[0] == 0x23)
            {
                int address = checked((int)BinaryPrimitives.ReadUInt32BigEndian(request.AsSpan(2,4)));
                int size = BinaryPrimitives.ReadUInt16BigEndian(request.AsSpan(6,2));
                reply = new byte[size+1]; reply[0] = 0x63;
                Buffer.BlockCopy(Memory,address,reply,1,size);
                // Model a non-cooperative transport that completes a read after
                // the caller has already canceled the operation.
                CancelAfterFlashRead?.Cancel();
            }
            else if (request[0] == 0x3D && request[1] == 2)
            {
                int size = Three(request,2), address = Three(request,5);
                uint crc = NativeE39FlashCommands.ComputeCrc32(Memory.AsSpan(address,size));
                reply = [0x7D,2,0,0,0,0]; BinaryPrimitives.WriteUInt32BigEndian(reply.AsSpan(2),crc);
            }
            else if (request[0] == 0x3D && request[1] == 5)
            {
                int address = Three(request,2);
                var block = NativeMpc5566FlashMap.Blocks().Single(b => b.Start == (uint)address);
                Array.Fill(Memory,(byte)0xFF,address,(int)(block.EndExclusive-block.Start));
                Erases++; reply = [0x7D,5,0];
            }
            else if (request[0] == 0x36)
            {
                if (request[1] == 0)
                {
                    int address = Three(request,4), size = BinaryPrimitives.ReadUInt16BigEndian(request.AsSpan(2,2));
                    Buffer.BlockCopy(request,7,Memory,address,size);
                    if (CorruptAfterWrite && !corruptDone)
                    { Memory[0x100] ^= 1; corruptDone = true; } // outside changed sectors
                }
                reply = [0x76];
            }
            else throw new InvalidOperationException("Unexpected native request");
            return Task.FromResult(reply);
        }
        private static int Three(byte[] request,int offset) =>
            (request[offset] << 16) | (request[offset+1] << 8) | request[offset+2];
    }

    internal static int Run()
    {
        int checks = 0;
        void Check(bool ok,string name) { checks++; if (!ok) throw new InvalidOperationException(name); }
        static void Reject<T>(Action action) where T:Exception
        { try { action(); } catch(T) { return; } throw new InvalidOperationException("Expected " + typeof(T).Name); }
        static string Hash(byte[] b) => Convert.ToHexString(SHA256.HashData(b));
        const string vin = "1G1FA1RXXR0123456";
        const uint os = 12664221;
        byte[] original = NativeE39ImageInspectorSelfTests.Fixture();
        byte[] target = original.ToArray();
        target[0x20040] ^= 0x31;
        BinaryPrimitives.WriteUInt16BigEndian(target.AsSpan(0x20000,2),
            NativeE39ImageInspector.ComputeSum(target,(0x20040,0x200FF)));
        BinaryPrimitives.WriteUInt16BigEndian(target.AsSpan(0x20020,2),
            NativeE39ImageInspector.ComputeCvn(target,(0x20040,0x2004F),(0x20050,0x2005F)));
        byte[] kernel = [1,2,3,4]; // synthetic loader payload, never a deployable kernel
        var id = new NativeGmlanIdentification.Identity(vin,os,0xC1,true,"mock");
        var backup = new NativeE39BackupQualification.Evidence(vin,os,Hash(original),original.Length);
        var session = new NativeE39ProgrammingSession.Qualification(vin,os,true,true,true,12000,15000);
        var policy = new NativeE39BenchWorkflow.Policy(session,Hash(kernel),4091,4090,4080);
        NativeE39WriteSequence.Result Execute(Bench b, Journal j,
            NativeE39BenchWorkflow.Policy p,NativeE39BackupQualification.Evidence e) =>
            NativeE39BenchWorkflow.RunAsync(b,j,p,e,original,target,kernel,CancellationToken.None)
                .GetAwaiter().GetResult();
        var noQualification = new Bench(original,id);
        Reject<UnauthorizedAccessException>(() => Execute(noQualification,new Journal(),
            policy with { Session = session with { RecoveryProcedureProven = false } },backup));
        Check(noQualification.Frames == 0 && noQualification.IdentityReads == 0,"Qualification required before IO");
        var wrongKernel = new Bench(original,id);
        Reject<InvalidDataException>(() => Execute(wrongKernel,new Journal(),
            policy with { ApprovedKernelSha256 = new string('0',64) },backup));
        Check(wrongKernel.Frames == 0 && wrongKernel.IdentityReads == 0,"Kernel digest checked before IO");
        var wrongIdentity = new Bench(original,id) { AlterSecondIdentity = true };
        Reject<InvalidDataException>(() => Execute(wrongIdentity,new Journal(),policy,backup));
        Check(wrongIdentity.Frames == 0,"Unstable identity prevents programming session");
        var mismatch = new Bench(original,id); mismatch.Memory[0x100] ^= 1;
        Reject<InvalidDataException>(() => Execute(mismatch,new Journal(),policy,backup));
        Check(mismatch.Erases == 0,"Full preflight mismatch outside planned sector prevents erase");
        using (var canceled = new CancellationTokenSource())
        {
            var lateRead = new Bench(original, id) { CancelAfterFlashRead = canceled };
            var lateJournal = new Journal();
            Reject<OperationCanceledException>(() =>
                NativeE39BenchWorkflow.RunAsync(lateRead, lateJournal, policy, backup,
                    original, target, kernel, canceled.Token).GetAwaiter().GetResult());
            Check(lateRead.Erases == 0 &&
                lateJournal.Events.All(e => e.Phase != NativeE39WriteSequence.Phase.Completed),
                "Late successful ECU read after cancellation cannot authorize erase or completion");
        }
        using (var canceledSupply = new CancellationTokenSource())
        {
            var lateSupply = new Bench(original, id) { CancelAfterRunningSupplyRead = canceledSupply };
            var supplyJournal = new Journal();
            Reject<OperationCanceledException>(() =>
                NativeE39BenchWorkflow.RunAsync(lateSupply, supplyJournal, policy, backup,
                    original, target, kernel, canceledSupply.Token).GetAwaiter().GetResult());
            Check(lateSupply.Erases == 0 &&
                supplyJournal.Events.All(e => e.Phase != NativeE39WriteSequence.Phase.Completed),
                "Late voltage observation after cancellation cannot authorize ECU erase");
        }
        var normal = new Bench(original,id); var normalJournal = new Journal();
        var result = Execute(normal,normalJournal,policy,backup);
        Check(result.SectorsWritten == 1 && normal.Memory.SequenceEqual(target),"Whole workflow programs target");
        Check(normalJournal.Events[^1].Phase == NativeE39WriteSequence.Phase.Completed &&
            normalJournal.Events[^1].Detail.Contains(Hash(target),StringComparison.Ordinal),
            "Completed durable event follows complete target readback");
        var corrupted = new Bench(original,id) { CorruptAfterWrite = true }; var journal = new Journal();
        Reject<InvalidDataException>(() => Execute(corrupted,journal,policy,backup));
        Check(corrupted.Erases == 1 && journal.Events[^1].Phase == NativeE39WriteSequence.Phase.RecoveryRequired &&
            journal.Events.All(e => e.Phase != NativeE39WriteSequence.Phase.Completed),
            "Untouched region corruption fails complete verification and requires recovery");
        // A diagnostic/security session must be exclusive BEFORE the first
        // identity read, seed/key exchange or RAM staging, not only at erase.
        using (var interrupt = new CancellationTokenSource())
        {
            var shared = new Bench(original, id);
            var entered = new TaskCompletionSource<bool>(
                TaskCreationOptions.RunContinuationsAsynchronously);
            shared.BeforeFirstIdentity = async token =>
            {
                entered.TrySetResult(true);
                await Task.Delay(Timeout.Infinite, token);
            };
            var holding = NativeE39BenchWorkflow.RunAsync(
                shared, new Journal(), policy, backup, original, target, kernel, interrupt.Token);
            entered.Task.GetAwaiter().GetResult();
            Reject<InvalidOperationException>(() =>
                NativeE39BenchWorkflow.RunAsync(shared, new Journal(), policy,
                    backup, original, target, kernel, CancellationToken.None)
                    .GetAwaiter().GetResult());
            Check(shared.IdentityReads == 0 && shared.Frames == 0 && shared.Erases == 0,
                "Competing workflow fails before identity, security and any ECU traffic");
            // The reservation is per physical transport object, never a
            // process-wide ban on independent adapters.
            var independent = new Bench(original, id);
            var independentResult = Execute(independent, new Journal(), policy, backup);
            Check(independentResult.SectorsWritten == 1,
                "Independent bench transport remains operational");
            interrupt.Cancel();
            Reject<OperationCanceledException>(() => holding.GetAwaiter().GetResult());
            var restart = Execute(shared, new Journal(), policy, backup);
            Check(restart.SectorsWritten == 1 && shared.Memory.SequenceEqual(target),
                "Aborted preflight releases transport for fresh fully verified workflow");
        }
        Console.WriteLine($"PASS: {checks} local E39 workflow hardware-free checks.");
        return checks;
    }
}
