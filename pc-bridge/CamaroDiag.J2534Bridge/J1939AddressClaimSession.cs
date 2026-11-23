namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Explicit SAE J1939-81 address-claim observation on an already opened
/// raw CAN or CAN-FD channel. No automatic NAME creation or random address
/// selection: the caller must supply the correct 64-bit NAME and address.
/// This implementation covers the initial contention interval only, not
/// persistent network management after the caller closes the channel.
/// </summary>
internal static class J1939AddressClaimSession
{
    internal const int ClaimObservationMilliseconds = 250;

    internal enum Outcome
    {
        Claimed,
        LostToHigherPriorityName,
        DuplicateName
    }

    internal static async Task<Outcome> ClaimAsync(
        IsoTpCanFdSession.ICanLink link, byte desiredAddress,
        ulong localName, bool canFd, CancellationToken token)
    {
        ArgumentNullException.ThrowIfNull(link);
        if (desiredAddress >= 0xFE || localName == ulong.MaxValue)
            throw new ArgumentException(
                "J1939 address claim needs an available source and a real unique NAME.");
        token.ThrowIfCancellationRequested();

        var frame = J1939AddressClaim.Build(desiredAddress, localName);
        if (canFd) frame = frame with { IsFd = true };
        // A new identity must be announced before transmitting application
        // PGNs. This is an explicit operator action, not an automatic claim.
        await link.SendAsync(frame, token);
        var stopwatch = System.Diagnostics.Stopwatch.StartNew();
        var reasserted = false;
        while (stopwatch.ElapsedMilliseconds < ClaimObservationMilliseconds)
        {
            token.ThrowIfCancellationRequested();
            var remaining = TimeSpan.FromMilliseconds(
                ClaimObservationMilliseconds - stopwatch.Elapsed.TotalMilliseconds);
            if (remaining <= TimeSpan.Zero) break;

            IsoTpCanFdSession.CanFrame observed;
            try
            {
                observed = await link.ReceiveAsync(remaining, token);
            }
            catch (TimeoutException)
            {
                // Fake links may time out immediately. The full contention
                // period must still elapse before declaring success.
                remaining = TimeSpan.FromMilliseconds(
                    ClaimObservationMilliseconds - stopwatch.Elapsed.TotalMilliseconds);
                if (remaining > TimeSpan.Zero)
                    await Task.Delay(remaining, token);
                break;
            }
            catch (InvalidDataException)
            {
                continue; // Unrelated/malformed traffic is not a claim.
            }
            if (observed.IsFd != canFd || observed.Id > 0x1FFFFFFF ||
                observed.Data is null || observed.Data.Length != 8)
                continue;
            var id = StandardVehicleProtocols.ParseJ1939Id(observed.Id);
            if (id.Pgn != J1939AddressClaim.AddressClaimPgn ||
                id.Destination != 0xFF || id.Source != desiredAddress)
                continue;

            J1939AddressClaim.Identity peer;
            try
            {
                peer = J1939AddressClaim.Decode(observed.Id, observed.Data);
            }
            catch (InvalidDataException)
            {
                continue;
            }
            var collision = J1939AddressClaim.Compare(
                peer, desiredAddress, localName);
            if (collision == J1939AddressClaim.ClaimConflict.DuplicateName)
            {
                // Raw CAN adapters may echo our own transmitted frame;
                // an identical (address, NAME) can be that local echo. The
                // adapter offers no independent bus-origin identification.
                // Do not treat it as proof of another physical node.
                continue;
            }
            if (collision == J1939AddressClaim.ClaimConflict.PeerNameWins)
            {
                // The numeric lower NAME wins. With no other configured
                // address, cease using this source and announce Cannot Claim.
                var cannot = J1939AddressClaim.Build(0xFE, localName);
                await link.SendAsync(canFd ?
                    cannot with { IsFd = true } : cannot, token);
                return Outcome.LostToHigherPriorityName;
            }
            if (collision == J1939AddressClaim.ClaimConflict.LocalNameWins &&
                !reasserted)
            {
                // Reannounce once to defend the lower-name claimant,
                // without entering an unbounded ping-pong on a noisy bus.
                await link.SendAsync(frame, token);
                reasserted = true;
            }
        }
        return Outcome.Claimed;
    }
}
