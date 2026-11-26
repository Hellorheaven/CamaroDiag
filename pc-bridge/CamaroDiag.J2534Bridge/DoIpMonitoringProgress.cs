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


/// <summary>Structured, provisional progress for a confirmed DoIP UDS sequence.
/// Completion is permitted only after socket and journal disposal.</summary>
internal sealed class DoIpSequenceProgress
{
    private readonly string vin;
    private readonly string host;
    private readonly ushort gateway;
    private readonly ushort tester;
    private readonly ushort ecu;
    private readonly string transport;
    internal int RequestedCommands { get; }
    internal int CommandsCompleted { get; private set; }

    internal DoIpSequenceProgress(string vin, string host, ushort gateway, ushort tester,
        ushort ecu, bool tls, int requestedCommands)
    {
        if (!DoIpVehicleDiscovery.IsVehicleVin(vin) || string.IsNullOrWhiteSpace(host) ||
            tester == ecu || gateway == tester || requestedCommands is < 1 or > 16)
            throw new ArgumentException("Invalid DoIP sequence route or command count.");
        this.vin = vin;
        this.host = host;
        this.gateway = gateway;
        this.tester = tester;
        this.ecu = ecu;
        transport = tls ? "TLS" : "TCP";
        RequestedCommands = requestedCommands;
    }

    internal string RecordVerified(int index, byte[] response)
    {
        ArgumentNullException.ThrowIfNull(response);
        if (index != CommandsCompleted + 1 || index > RequestedCommands ||
            response.Length is < 1 or > 4091)
            throw new InvalidDataException("Invalid DoIP sequence reply progress.");
        var json = System.Text.Json.JsonSerializer.Serialize(new
        {
            sequence_progress = new
            {
                vin, gateway_host = host, gateway_logical_address = gateway,
                tester, ecu, transport, service_protocol = "UDS",
                requested_commands = RequestedCommands, commands_completed = index,
                index, response = Convert.ToHexString(response),
                partial = true, complete = false
            }
        });
        CommandsCompleted = index;
        return json;
    }

    internal string Summary(bool complete)
    {
        if (complete && CommandsCompleted != RequestedCommands)
            throw new InvalidDataException("Cannot complete an incomplete DoIP sequence.");
        return System.Text.Json.JsonSerializer.Serialize(new
        {
            sequence_summary = new
            {
                vin, gateway_host = host, gateway_logical_address = gateway,
                tester, ecu, transport, service_protocol = "UDS",
                requested_commands = RequestedCommands, commands_completed = CommandsCompleted,
                sequence_complete = complete, complete
            }
        });
    }
}
