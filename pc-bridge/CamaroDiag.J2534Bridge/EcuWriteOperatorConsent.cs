namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Two-step human confirmation for LOCAL CLI programming transactions.
/// This is a UX confirmation expressly requested by the owner, not an ECU key.
/// A warning acknowledgement alone must never authorize an ECU write.
/// </summary>
internal static class EcuWriteOperatorConsent
{
    internal static bool AskBeforeProgramming(Func<string?> input, Action<string> display,
        string ecuAndTarget)
    {
        display("AVERTISSEMENT : une écriture va avoir lieu sur un calculateur ou un élément connecté.");
        display("Une image erronée, une interruption ou un défaut de communication peut endommager");
        display("le calculateur ou le rendre inutilisable.");
        display("CamaroDiag décline toute responsabilité pour les conséquences de cette opération,");
        display("dans les limites prévues par la loi.");
        display("Élément cible : " + ecuAndTarget);
        display("Saisissez OK pour prendre connaissance de l'avertissement,");
        display("ou toute autre réponse pour ANNULER sans écrire.");
        if (!string.Equals(input()?.Trim(), "OK", StringComparison.OrdinalIgnoreCase))
            return false;

        display("CONFIRMATION : voulez-vous réellement écrire sur l'élément sélectionné ?");
        display("Saisissez OUI pour lancer l'écriture, ou NON pour annuler.");
        return string.Equals(input()?.Trim(), "OUI", StringComparison.OrdinalIgnoreCase);
    }
}
