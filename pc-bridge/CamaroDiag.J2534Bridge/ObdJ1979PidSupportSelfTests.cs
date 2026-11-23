namespace CamaroDiag.J2534Bridge;

/// <summary>Pure offline protocol vectors. These tests do not communicate with a vehicle.</summary>
internal static class ObdJ1979PidSupportSelfTests
{
    internal static int Run()
    {
        var count = 0;
        void Check(bool condition, string name)
        {
            count++;
            if (!condition)
                throw new InvalidOperationException("J1979 PID support: " + name);
        }
        void Reject<T>(Action action, string name) where T : Exception
        {
            var thrown = false;
            try { action(); }
            catch (T) { thrown = true; }
            Check(thrown, name);
        }

        var request = ObdJ1979PidSupport.BuildRequest(0x01, 0x00);
        Check(request.SequenceEqual([0x01, 0x00]), "Mode 01 supported page query.");
        var page = ObdJ1979PidSupport.Decode(request,
            [0x41, 0x00, 0x80, 0x10, 0x00, 0x01]);
        Check(page.Mode == 1 && page.RequestedBase == 0 &&
            page.SupportedPids.SequenceEqual(new byte[] {0x01, 0x0C, 0x20}) &&
            page.HasNextPage,
            "MSB-first bit numbering and next page indicated only by PID 20.");
        var noNext = ObdJ1979PidSupport.Decode(
            [0x01, 0x20], [0x41, 0x20, 0x80, 0, 0, 0]);
        Check(noNext.SupportedPids.SequenceEqual(new byte[] {0x21}) &&
            !noNext.HasNextPage, "Next page not assumed when next-page bit is clear.");
        var freeze = ObdJ1979PidSupport.Decode(
            [0x02, 0x40, 0x00], [0x42, 0x40, 0x00, 0x40, 0, 0, 0]);
        Check(freeze.Mode == 2 && freeze.FreezeFrameNumber == 0 &&
            freeze.SupportedPids.SequenceEqual(new byte[] {0x42}),
            "Mode 02 page 40 uses the same support bitmap ordering.");
        Check(ObdJ1979PidSupport.BuildRequest(0x02, 0x40, 0x02)
            .SequenceEqual([0x02, 0x40, 0x02]),
            "Mode 02 request carries explicit freeze-frame index.");
        Reject<InvalidDataException>(() =>
            ObdJ1979PidSupport.Decode([0x02, 0x40, 0x02],
                [0x42, 0x40, 0x00, 0x40, 0, 0, 0]),
            "Mode 02 response must echo the requested freeze-frame index.");
        Reject<ArgumentException>(() =>
            ObdJ1979PidSupport.Decode([0x02, 0x40],
                [0x42, 0x40, 0, 0, 0, 0, 0]),
            "Mode 02 request without frame index rejected.");
        var empty = ObdJ1979PidSupport.Decode(
            [0x01, 0x60], [0x41, 0x60, 0, 0, 0, 0]);
        Check(empty.SupportedPids.Count == 0 && !empty.HasNextPage,
            "Empty support bitmap is valid and terminal.");
        Reject<ArgumentException>(() =>
            ObdJ1979PidSupport.BuildRequest(0x03, 0),
            "Mode without support bitmap is rejected.");
        Reject<ArgumentException>(() =>
            ObdJ1979PidSupport.BuildRequest(0x01, 0x21),
            "Unaligned page is rejected.");
        Reject<ArgumentException>(() =>
            ObdJ1979PidSupport.Decode([0x01, 0x00, 0], [0x41, 0, 0, 0, 0, 0]),
            "Additional request parameters are rejected.");
        Reject<InvalidDataException>(() =>
            ObdJ1979PidSupport.Decode([0x01, 0x00],
                [0x41, 0x20, 0, 0, 0, 0]),
            "Wrong echoed base PID is rejected.");
        Reject<InvalidDataException>(() =>
            ObdJ1979PidSupport.Decode([0x01, 0x00],
                [0x41, 0, 0, 0, 0]),
            "Truncated bitmap rejected.");
        Reject<InvalidDataException>(() =>
            ObdJ1979PidSupport.Decode([0x01, 0x00],
                [0x41, 0, 0, 0, 0, 0, 0]),
            "Excess bitmap bytes rejected.");
        Reject<InvalidDataException>(() =>
            ObdJ1979PidSupport.Decode([0x01, 0x00],
                [0x7F, 0x01, 0x12]),
            "Negative response cannot be mistaken for an empty page.");
        return count;
    }
}
