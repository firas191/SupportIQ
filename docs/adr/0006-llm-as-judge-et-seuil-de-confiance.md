# ADR-0006 — LLM-as-judge des brouillons et sort du seuil de faible confiance

- **Statut** : **accepté** (mesuré sur 50 tickets — voir Résultats)
- **Date** : S5-J5
- **Contexte** : rapport §9 Semaine 5 — « LLM-as-judge sur 50 brouillons (grille exactitude/
  complétude/ton), score stocké par brouillon, seuil "faible confiance" affiché ; ajout au harness CI »

---

## Pourquoi cet ADR est écrit *avant* la mesure

Deux épisodes de ce projet justifient cette précaution.

Au **S5-J2**, le reranking a été implémenté avec l'attente qu'il améliore le classement. Il l'a
dégradé. La conclusion a été acceptée parce que le critère — « garder si le MRR progresse » — avait
été posé avant. S'il ne l'avait pas été, il aurait été tentant de sauver le travail en changeant la
métrique après coup.

Au **S5-J3**, un correctif a introduit une régression invisible aux métriques : les indicateurs
étaient devenus bons pendant que le texte se dégradait. Une décision prise sur le seul agrégat
aurait validé une régression.

Les règles ci-dessous sont donc figées **maintenant**, sans connaître les chiffres. Elles sont
vérifiables : le rapport produit par `eval/judge_drafts.py` contient exactement les quantités
qu'elles invoquent.

---

## Décision 1 — Grille de notation

Trois critères, **niveaux 0-1-2 ancrés** sur des cas observables, plutôt qu'une note sur 5.

| Critère | 2 | 1 | 0 |
|---|---|---|---|
| **Exactitude** | toute affirmation figure dans les passages | une donnée secondaire absente | une affirmation contredit les passages ou en est absente |
| **Complétude** | toutes les questions traitées | la principale traitée, une secondaire en suspens | poli mais ne répond pas |
| **Ton** | registre demandé, ne promet rien au-delà des sources | acceptable mais maladroit ou creux | inapproprié ou sur-promesse |

*Justification du barème court* : une échelle fine sans définition partagée produit du bruit déguisé
en précision — le même brouillon reçoit 3 ou 4 selon l'appel. Trois niveaux définis par un cas
observable se reproduisent. On perd de la résolution, on gagne de la fiabilité.

**L'exactitude est un verrou, pas un tiers de la note.** La note globale vaut zéro dès que
l'exactitude est nulle. Une moyenne arithmétique donnerait 0,67 à un brouillon qui invente un délai
de remboursement mais reste bien écrit — un chiffre rassurant sur un texte à jeter. Une agrégation
doit encoder la hiérarchie des défauts, pas les diluer.

**Les abstentions ne sont pas notées.** Un brouillon disant « la documentation ne couvre pas cette
demande » obtiendrait complétude 0, ce qui pénaliserait exactement le comportement recherché.
L'agrégat mesurerait alors la couverture de la base de connaissances déguisée en qualité de
rédaction. Le taux d'abstention est reporté **à côté**, comme métrique de couverture.

**Le juge est un modèle différent du rédacteur** (70b contre 8b) : un modèle qui note sa propre
production se préfère. Même séparation qu'au S2-J5 pour le filtre d'accord du jeu de données.

---

## Décision 2 — Sort du bandeau « à relire » (pré-enregistrée)

L'auto-vérification du S5-J3 lève `low_confidence`, et l'interface du S5-J4 affiche un bandeau
d'avertissement en conséquence. **Personne n'a vérifié que ce drapeau prédit quoi que ce soit.**

Un avertissement qui se déclenche au hasard est pire qu'aucun avertissement : il apprend aux agents
à ignorer les avertissements, y compris les justifiés.

Soit `Δ = note moyenne des non-signalés − note moyenne des signalés`, sur les brouillons notés.

| Condition | Décision |
|---|---|
| `Δ ≥ 0,15` | Le drapeau discrimine. **Conservé tel quel.** |
| `0,05 ≤ Δ < 0,15` | Signal faible. Conservé, mais l'avertissement est **rétrogradé** en mention discrète — il informe sans alarmer. |
| `Δ < 0,05` | Le drapeau n'apprend rien. **Bandeau retiré** de l'interface ; le champ reste en base pour une mesure ultérieure sur un échantillon plus grand. |
| Un des deux groupes < 5 brouillons | **Aucune décision.** L'échantillon ne permet pas de trancher, et le dire est la seule lecture honnête. |

*Pourquoi 0,15* : la note vit sur `[0, 1]` avec un pas naturel de 1/6 ≈ 0,17 (un niveau de critère
sur un seul des trois). Un écart inférieur à 0,05 est inférieur au tiers de ce pas — indiscernable
du bruit sur quelques dizaines d'observations.

---

## Décision 3 — Ce qui entre en CI, et ce qui n'y entre pas

**N'y entre pas** : la campagne complète. Elle demande une base peuplée, une base de connaissances
indexée, des clés d'API et une centaine d'appels de modèle. La faire tourner à chaque `push`
consommerait le budget de jetons et rendrait la CI dépendante d'un fournisseur externe — elle
échouerait rouge sur un quota épuisé, ce qui est le pire signal possible : un rouge qui n'indique
aucun défaut du code apprend à ignorer les rouges. Même arbitrage qu'au S3-J5 pour
`evaluate_pipeline.py`.

**Y entre** : les parties **déterministes** du juge — analyse du verdict, agrégation, verrou
d'exactitude, exclusion des abstentions. Ce sont elles qui peuvent régresser silencieusement lors
d'un remaniement, et elles se testent sans le moindre appel réseau. C'est la même ligne de partage
que pour l'agent au S5-J3 : *une garantie qu'on ne peut pas tester sans clé d'API n'en est pas une*.

---

## Résultats

Campagne du S5-J5 sur **50 tickets** (échantillon stratifié, ton `formal`).
Rapport détaillé : `eval/results/judge_s5j5.md`.

| Mesure | Valeur |
|---|---|
| Brouillons notés | 34 (68 %) |
| Taux d'abstention | **16 / 50 — 32 %** |
| Exactitude moyenne (0-2) | **1,71** |
| Complétude moyenne (0-2) | **1,03** |
| Ton moyen (0-2) | **2,00** |
| **Note globale moyenne** | **0,78** (médiane 0,83) |
| **Brouillons inutilisables** (exactitude = 0) | **1 / 34 — 3 %** |
| `Δ` faible confiance | **+0,10** (18 signalés à 0,73 / 16 non signalés à 0,83) |
| **Décision 2 appliquée** | bande intermédiaire → **avertissement rétrogradé** |

### Lecture

**L'exactitude tient, la complétude non.** 1,71 sur 2 en exactitude signifie que le brouillon
s'appuie réellement sur les passages : la contrainte de citation fait son travail. 1,03 en
complétude signifie qu'un brouillon sur deux ne traite qu'une partie de la demande. C'est le
principal défaut de la chaîne, et il est **de recherche, pas de rédaction** : les cinq pires cas
sont des tickets à deux sujets (« ma livraison est en retard *et* ma facture est fausse »), pour
lesquels les cinq passages remontés se concentrent tous sur le sujet dominant. Le rédacteur ne peut
pas traiter ce qu'on ne lui a pas donné.

Piste identifiée, **non implémentée** : décomposer le ticket en sous-questions et chercher pour
chacune. Cela change la forme du nœud `retrieve` et mérite d'être mesuré séparément — ce n'est pas
un correctif à glisser en fin de semaine.

**Le ton ne discrimine pas.** 2,00 sur 34 brouillons, soit une variance nulle. Un critère qui ne
varie jamais n'apporte aucune information : il ajoute mécaniquement 0,33 à chaque note. La moyenne
affichée de 0,78 est donc en partie du remplissage — sur les deux critères qui varient, elle vaut
**0,69**. Deux lectures possibles, que cette campagne ne départage pas : soit la consigne de ton
fonctionne réellement (plausible, c'est la tâche la plus facile pour un modèle), soit le juge ne
sait pas la noter. Il faudrait lui soumettre des brouillons volontairement mal tonalisés pour
trancher. **Les campagnes suivantes reporteront exactitude et complétude séparément** plutôt que de
les diluer dans un agrégat que le ton gonfle.

**3 % de brouillons inutilisables** est le chiffre à citer pour un déploiement : sans boucle de
validation humaine, trois réponses sur cent contiendraient une affirmation absente des sources.
C'est l'argument chiffré en faveur du S5-J4.

**32 % d'abstention** est une mesure de **couverture**, pas de qualité : la base de démonstration
compte quatre documents. À noter, l'échantillon le montre franchement — 0 abstention parmi les
tickets analysés (écrits autour des sujets de la FAQ) contre 15 sur 42 parmi les tickets importés.
C'est un artefact de sélection autant qu'un résultat, et il faut le dire avant qu'on le demande.

### Décision 2, appliquée

`Δ = +0,10` tombe dans la bande **[0,05 ; 0,15[**, avec 18 et 16 brouillons par groupe (au-dessus du
minimum de 5). La règle écrite avant la mesure impose donc de **rétrograder l'avertissement en
mention discrète** : le drapeau porte un signal réel mais faible — il ne justifie pas un bandeau
d'alerte, qui banaliserait les alertes.

C'est la valeur du pré-enregistrement : la règle a choisi une option intermédiaire que je n'aurais
probablement pas retenue en regardant les chiffres après coup, où la tentation aurait été de garder
le bandeau (« +0,10, ça marche ») ou de le supprimer (« +0,10, c'est du bruit »).

Concrètement (`draft-panel.component`) : le bandeau `banner--warning` devient une ligne d'information
en gris, sans fond coloré ni icône d'alerte.

---

## Conséquences

- Chaque brouillon porte désormais une note (`draft_responses.judge_score`), donc le taux de
  brouillons utilisables devient une métrique de tableau de bord et non une impression.
- Le protocole est rejouable : après tout changement du prompt de rédaction, de la recherche ou du
  corpus, la même commande produit un chiffre comparable. C'est ce qui manquait pour savoir si une
  modification améliore quelque chose.
- La note **ne remplace pas** la validation humaine. Elle mesure la qualité moyenne d'une
  proposition ; elle ne garantit rien sur un brouillon particulier, et rien dans la plateforme ne
  permet d'envoyer une réponse sans qu'un humain ait tranché (S5-J4).

## Limites assumées

- Le juge n'est pas un client. Il vérifie la cohérence entre un texte et des passages, pas la
  satisfaction.
- Tickets synthétiques et base de connaissances écrite pour eux : la couverture mesurée est plus
  favorable qu'elle ne le serait sur un corpus réel. À dire en soutenance avant qu'on le demande.
- Une seule note par brouillon : la **stabilité** du juge n'est pas mesurée. Il faudrait noter deux
  fois le même brouillon et comparer. Non fait faute de budget de jetons — c'est la première chose
  à ajouter si le protocole doit servir à des décisions plus fines.
