# ADR-0002 — Gestion d'état du frontend : signals + services (pas NgRx)

Date : S1-J4 · Statut : accepté

## Contexte
Le frontend Angular 18 doit gérer : l'état d'authentification (utilisateur courant, rôle,
jetons), des listes paginées côté serveur (tickets), des agrégats de dashboard, et à terme
des flux temps réel (WebSocket). La question posée par le rapport (§9 S1-J4) : **NgRx ou
signals ?**

La majorité de cet état est de l'**état serveur** (données récupérées via HTTP, rafraîchies),
pas de l'état client complexe partagé entre de nombreux composants distants.

## Décision
**Signals Angular 18 + services par feature**, sans NgRx.

- Chaque feature expose un service (`AuthService`, `TicketsService`…) qui encapsule les appels
  HTTP (RxJS/`HttpClient`) et publie l'état dérivé sous forme de **signals** (`signal`,
  `computed`) consommés par les composants.
- L'état d'auth (utilisateur courant, rôle) vit dans `AuthService` en signals ; les guards et
  le layout le lisent directement.
- L'état serveur (tickets, KPIs) reste requêté à la demande ; on ne le duplique pas dans un
  store global.

## Justification
- **Périmètre.** L'état est surtout du server-state + un peu d'état d'auth. NgRx
  (actions/reducers/effects/selectors) apporterait une cérémonie disproportionnée — de la
  **sur-ingénierie** au sens du rapport (« refuser la sur-ingénierie autant que les raccourcis »).
- **Natif & moderne.** Les signals sont intégrés à Angular 18, offrent une réactivité
  fine-grain sans boilerplate ni dépendance externe, et sont la direction recommandée du
  framework.
- **Vélocité.** Zéro fichier d'action/reducer à écrire pour, au J4, un simple flux
  login → état user → routing.

## Conséquences
+ Peu de boilerplate, courbe d'apprentissage faible, code lisible.
+ Réactivité fine et détection de changement optimisée (zoneless-friendly).
- Moins de structure imposée que NgRx : on la compense par une **convention stricte**
  (un service d'état par feature, pas d'état mutable partagé hors service).
- Pas de time-travel debugging / devtools NgRx.

## Porte de sortie
Si l'état client se complexifie (S5-S7 : brouillons RAG, chat Insight, alertes temps réel
partagées), on pourra adopter **`@ngrx/signals` (SignalStore)** de façon incrémentale, feature
par feature, sans réécrire l'existant — la migration signals → SignalStore est continue.

## Liens
- ADR-0001 (architecture générale). ADRs suivants : 0003 fine-tuning vs baseline, 0004 seuil
  routeur de confiance, 0005 retrieval hybride RRF, 0006 guardrails Text-to-SQL.
