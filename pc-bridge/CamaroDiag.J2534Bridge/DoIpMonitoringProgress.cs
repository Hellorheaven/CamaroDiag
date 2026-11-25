namespace CamaroDiag.J2534Bridge;

/// <summary>Counts published identity-verified samples, not attempted reads.
/// A run is complete only after its transport and trace checks have finished.</summary>
internal sealed class DoIpMonitoringProgress
{
    internal int RequestedSamples { get; }
    internal int VerifiedSamples { get; private set; }
    internal bool Complete { get; private set; }

    internal DoIpMonitoringProgress(int requestedSamples)
    {
        if (requestedSamples is < 1 or > 3600)
            throw new ArgumentOutOfRangeException(nameof(requestedSamples));
        RequestedSamples = requestedSamples;
    }

    internal void RecordVerified(int index)
    {
        if (Complete || index != VerifiedSamples + 1 || index > RequestedSamples)
            throw new InvalidDataException("Invalid DoIP monitoring sample sequence.");
        VerifiedSamples = index;
    }

    internal void MarkComplete()
    {
        if (VerifiedSamples != RequestedSamples)
            throw new InvalidDataException("Cannot mark an incomplete DoIP capture complete.");
        Complete = true;
    }
}
