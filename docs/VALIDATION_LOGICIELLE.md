# Fiabilité et validation de CamaroDiag

CamaroDiag fait l'objet de vérifications logicielles sur ses
différents composants : application Android, application Windows
et interfaces de communication.

Les versions de développement ne doivent pas être considérées
comme des versions prêtes pour une utilisation sur véhicule
simplement parce qu'elles compilent.

**Règle de validation de la PR #95 : aucun test sur voiture ou ECU réel
pendant le développement de cette PR.** Les développeurs vérifient
le code par tests automatisés et compilation Android, Windows et
connecteurs, puis corrigent les anomalies logicielles.

**La recette sur voiture et interfaces physiques est réservée à la Release
après clôture de la PR**, réalisée par l'utilisateur à l'aide du
[lot 8 — cahier de tests utilisateur](https://github.com/Hellorheaven/CamaroDiag/issues/48).
Une fonction qui passe les tests logiciels reste « non testée sur véhicule »
jusqu'à l'exécution et l'enregistrement de sa recette sur Release.

Les fonctionnalités sensibles, notamment les commandes et
écritures sur calculateur, exigent des vérifications adaptées au
véhicule, au calculateur et à l'interface.

Les résultats de compilation et de tests publics sont
consultables dans les [actions du projet](https://github.com/Hellorheaven/CamaroDiag/actions).
Un résultat logiciel ne certifie pas à lui seul la compatibilité
avec un véhicule ou l'absence de risque matériel.
