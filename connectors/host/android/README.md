# Android — accès direct au prototype

Le client Java et l'adaptateur USB Host CDC sont dans src/main/java. Android 10–16 cible ; aucun PC ni serveur n'est nécessaire. L'application doit sélectionner explicitement le périphérique et les interfaces CDC contrôle/données compatibles, demander l'autorisation USB via UsbManager et lancer UsbDiscovery.verify hors du thread UI. Le client vérifie les trois réponses PING/CAPS/STATUS, la version, le CRC et la séquence puis libère les interfaces. Il ne sait pas authentifier cryptographiquement le boîtier, ne transmet aucune commande ECU et ne possède aucun pilote de programmation.

Seuls les descripteurs CDC ACM acceptant la configuration de ligne sont pris en charge dans ce prototype. Le mode USB matériel CDC/JTAG Espressif peut avoir des exigences distinctes : vérifier les descripteurs de la carte réelle et ne pas supposer de compatibilité. Tester sur carte isolée uniquement. Le firmware binaire doit être redémarré entre sessions de découverte ; pas de fallback ASCII. Le code n'est pas encore intégré à l'application CamaroDiag.

CI : tests Java portables, compilation contre Android SDK 35. Aucun test réel sur téléphone ou matériel ne découle de ces builds. Authentification TLS de référence hôte distincte ; intégration embarquée USB, appairage et permissions de programmation restent à réaliser (#8/#9).

## Native J1979 CU/1 (development / développement)

The communication module compiles the same Java sources. `CamaroDiagConnectorNativeCampaign.run` accepts an explicitly selected USB device/CDC interfaces, approved generation, documented physical CAN pair and locally provisioned credential; it preserves raw CJ/1 and a separate JSON diagnostic report in a private directory. Not connected to the functional-test screen yet. Do not run against discovery firmware; frame integrity alone is insufficient; the report separately verifies campaign semantics and never asserts physical vehicle provenance.

Le module communication réutilise ces sources ; l'entrée de campagne native conserve le flux brut et un JSON distinct. L'écran de recette reste à raccorder. Uniquement avec une carte et un firmware qualifiés.
