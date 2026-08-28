#!/usr/bin/env python3
"""Genere un CSV de tickets synthetiques (FR/EN) pour les tests de charge et d'import.

Usage : python scripts/generate_sample_csv.py [n_rows] [out_path] [prefix]
Defaut : 10000 lignes -> samples/tickets_10k.csv, prefixe TCK

Exemples :
    python scripts/generate_sample_csv.py 10000 samples/tickets_10k.csv         # import (S2-J1)
    python scripts/generate_sample_csv.py 50000 samples/tickets_50k.csv PERF    # charge (S7-J5)
    python scripts/generate_sample_csv.py 3000  samples/tickets_recent.csv RCT  # fenetre recente

------------------------------------------------------------------------------
Le prefixe de reference n'est pas un detail
------------------------------------------------------------------------------
`external_ref` etait construit sur la seule position (`TCK-000001`, `TCK-000002`…). Consequence :
**deux corpus generes a des moments differents portent les memes references**, et le second import
est integralement ignore par la deduplication — « 0 tickets crees, 3000 deja connus ».

Ce n'est pas un defaut de la deduplication, qui fait exactement son travail : deux tickets de meme
`external_ref` sont le meme ticket. C'est la generation qui mentait, en donnant la meme identite a
des donnees differentes. D'ou le prefixe, a changer a chaque corpus qu'on veut voir coexister.

------------------------------------------------------------------------------
Horodatages realistes (ajout S7-J5)
------------------------------------------------------------------------------
La version initiale ecrivait un ticket par minute, exactement. C'etait suffisant pour tester
l'import en flux, et **inutilisable** pour tout le reste :

  - le detecteur d'anomalies (S7-J2) desaisonnalise une serie horaire. Sur un flux parfaitement
    regulier, la dispersion est nulle : il refuse de conclure, ce qui est le comportement voulu
    mais rend la demonstration impossible ;
  - les vues horaires et la carte de charge du tableau de bord montrent une ligne plate ;
  - les mesures de charge portent sur une distribution que rien, dans la realite, ne produit.

Les tickets sont donc repartis avec un **rythme jour/nuit et semaine/week-end**, et un bruit
reproductible (graine fixe : deux executions donnent le meme fichier, sans quoi deux mesures de
performance ne seraient pas comparables).
"""
import csv
import random
import sys
from datetime import datetime, timedelta

SEED = 7

# ---------------------------------------------------------------------------
# Themes : sujets et corps a trous
# ---------------------------------------------------------------------------
#
# **Pourquoi des gabarits a trous plutot que des phrases figees.**
#
# La premiere version tirait dans deux listes de phrases completes : 9 sujets et 5 corps, soit une
# centaine de textes distincts au maximum, chacun repete une trentaine de fois sur un corpus de
# 3 000 lignes. Consequence constatee au S7 : des textes identiques donnent des vecteurs
# identiques, donc des amas de variance nulle parfaitement separes. Le regroupement y trouvait
# ~90 groupes *reels* et l'ecran des sujets emergents affichait sept fois « paiement en double ».
#
# Ce n'etait pas un defaut de l'algorithme, c'en etait un des donnees : un corpus de duplicatas
# exacts ne ressemble a rien de ce qu'une equipe support recoit, et ne permet donc de rien
# demontrer.
#
# Les gabarits ci-dessous portent des trous (numero de commande, montant, date, canal) remplis au
# tirage. Chaque ticket devient quasi unique, tout en restant rattache a un **theme** — ce qui est
# exactement la structure qu'un regroupement doit retrouver, et sur laquelle il peut echouer.

THEMES: list[dict] = [
    {
        "name": "double_debit",
        "fr": {
            "subjects": ["Double debit sur ma carte", "Paiement preleve deux fois",
                         "Montant debite en double", "Erreur de facturation carte"],
            "bodies": [
                "Bonjour, le paiement de la commande {order} a ete preleve deux fois sur ma carte, "
                "pour {amount} euros a chaque fois. Le releve du {date} le montre clairement.",
                "J'ai ete debite de {amount} euros deux fois pour la commande {order}. "
                "Merci de rembourser le second prelevement au plus vite.",
                "Un double prelevement de {amount} euros apparait le {date} sur mon compte, "
                "alors que je n'ai valide qu'une seule fois la commande {order}.",
            ],
        },
        "en": {
            "subjects": ["Charged twice", "Duplicate payment on my card",
                         "Double charge on order", "Payment taken twice"],
            "bodies": [
                "Hello, order {order} was charged twice on my card, {amount} euros each time. "
                "My statement from {date} shows both.",
                "I was billed {amount} euros twice for order {order}. Please refund the duplicate.",
                "There is a duplicate charge of {amount} euros dated {date} for order {order}, "
                "which I only confirmed once.",
            ],
        },
    },
    {
        "name": "livraison",
        "fr": {
            "subjects": ["Colis jamais recu", "Livraison en retard", "Suivi bloque depuis {date}",
                         "Commande {order} non livree"],
            "bodies": [
                "Le suivi de la commande {order} indique une livraison le {date}, mais je n'ai "
                "rien recu et le gardien n'a aucun colis a mon nom.",
                "Ma commande {order} devait arriver le {date}. Le suivi n'a pas bouge depuis "
                "et personne ne repond au numero du transporteur.",
                "Aucune nouvelle du colis {order} depuis le {date}. Puis-je avoir un point "
                "precis sur sa localisation ?",
            ],
        },
        "en": {
            "subjects": ["Parcel never arrived", "Late delivery", "Tracking stuck since {date}",
                         "Order {order} not delivered"],
            "bodies": [
                "Tracking for order {order} says delivered on {date}, but nothing arrived and "
                "the concierge has no parcel under my name.",
                "Order {order} was due on {date}. Tracking has not moved since and the carrier "
                "does not answer.",
                "No news about parcel {order} since {date}. Could you tell me where it is?",
            ],
        },
    },
    {
        "name": "compte",
        "fr": {
            "subjects": ["Probleme de connexion", "Acces au compte bloque", "Mot de passe oublie",
                         "Connexion impossible depuis {channel}"],
            "bodies": [
                "Je n'arrive plus a acceder a mon compte depuis le {date}. Le message d'erreur "
                "parle d'identifiants invalides alors que je n'ai rien change.",
                "Depuis la mise a jour du {date}, la connexion echoue systematiquement sur "
                "{channel}. Mon identifiant est {order}.",
                "Le lien de reinitialisation de mot de passe recu le {date} ne fonctionne pas, "
                "il indique que la demande a expire.",
            ],
        },
        "en": {
            "subjects": ["Login issue", "Account access blocked", "Forgot password",
                         "Cannot sign in from {channel}"],
            "bodies": [
                "I can no longer access my account since {date}. The error mentions invalid "
                "credentials although nothing changed on my side.",
                "Since the {date} update, signing in from {channel} always fails. "
                "My reference is {order}.",
                "The password reset link I received on {date} does not work, it says the "
                "request has expired.",
            ],
        },
    },
    {
        "name": "facture",
        "fr": {
            "subjects": ["Facture incorrecte", "Montant non conforme a l'abonnement",
                         "Erreur sur la facture {order}", "Facturation inattendue de {amount} euros"],
            "bodies": [
                "La facture {order} du {date} indique {amount} euros alors que mon abonnement "
                "en prevoit un autre. Pouvez-vous m'expliquer l'ecart ?",
                "Une ligne de {amount} euros apparait sur la facture du {date} sans que je "
                "comprenne a quoi elle correspond.",
                "Mon abonnement a change en cours de mois mais la facture {order} applique "
                "encore l'ancien tarif de {amount} euros.",
            ],
        },
        "en": {
            "subjects": ["Wrong invoice", "Amount does not match subscription",
                         "Error on invoice {order}", "Unexpected charge of {amount} euros"],
            "bodies": [
                "Invoice {order} dated {date} shows {amount} euros while my plan states a "
                "different price. Could you explain the difference?",
                "A {amount} euro line appears on the {date} invoice and I cannot tell what "
                "it corresponds to.",
                "My plan changed mid-month but invoice {order} still applies the old "
                "{amount} euro rate.",
            ],
        },
    },
    {
        "name": "technique",
        "fr": {
            "subjects": ["Application qui plante", "Erreur au chargement", "Panne sur {channel}",
                         "Fonctionnalite indisponible depuis {date}"],
            "bodies": [
                "L'application se ferme seule des que j'ouvre l'historique, depuis la version "
                "installee le {date}. Cela arrive aussi sur {channel}.",
                "Une erreur s'affiche a chaque tentative de validation sur {channel} depuis le "
                "{date}. Le numero de session est {order}.",
                "La page reste blanche pendant plusieurs minutes puis affiche une erreur, "
                "constate le {date} sur {channel}.",
            ],
        },
        "en": {
            "subjects": ["App keeps crashing", "Error while loading", "Outage on {channel}",
                         "Feature unavailable since {date}"],
            "bodies": [
                "The app closes by itself whenever I open the history, since the build "
                "installed on {date}. It also happens on {channel}.",
                "An error shows on every submit from {channel} since {date}. "
                "Session id is {order}.",
                "The page stays blank for minutes then errors out, seen on {date} on {channel}.",
            ],
        },
    },
    {
        "name": "remboursement",
        "fr": {
            "subjects": ["Remboursement demande", "Retour non rembourse",
                         "Ou en est mon remboursement ?", "Avoir non recu pour {order}"],
            "bodies": [
                "J'ai renvoye la commande {order} le {date} et le remboursement de {amount} "
                "euros n'est toujours pas arrive.",
                "Le retour du {date} a bien ete receptionne d'apres le suivi, mais je n'ai "
                "aucune trace du remboursement de {amount} euros.",
                "On m'a annonce un avoir de {amount} euros pour la commande {order}, sans "
                "nouvelle depuis le {date}.",
            ],
        },
        "en": {
            "subjects": ["Refund request", "Return not refunded",
                         "Where is my refund?", "Credit note missing for {order}"],
            "bodies": [
                "I returned order {order} on {date} and the {amount} euro refund has still "
                "not arrived.",
                "The {date} return was received according to tracking, but there is no sign "
                "of the {amount} euro refund.",
                "I was promised a {amount} euro credit for order {order}, with no news "
                "since {date}.",
            ],
        },
    },
]

CHANNELS_FR = ["l'application mobile", "le site web", "la tablette", "l'espace client"]
CHANNELS_EN = ["the mobile app", "the website", "the tablet", "the customer portal"]

#: Poids horaires : creux la nuit, deux pics en journee. Ce ne sont pas des mesures, ce sont des
#: proportions plausibles — elles servent a produire une saisonnalite, pas a la modeliser.
HOUR_WEIGHTS = [1, 1, 1, 1, 1, 2, 4, 8, 14, 18, 20, 16,
                12, 14, 18, 19, 16, 11, 7, 5, 4, 3, 2, 1]


def main() -> None:
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 10000
    out = sys.argv[2] if len(sys.argv) > 2 else "samples/tickets_10k.csv"
    prefix = sys.argv[3] if len(sys.argv) > 3 else "TCK"

    rng = random.Random(SEED)
    timestamps = _timestamps(n, rng)

    with open(out, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["external_ref", "customer_email", "subject", "body", "created_at", "language"])
        seen: set[str] = set()
        for i, ts in enumerate(timestamps, start=1):
            lang = rng.choice(["fr", "en"])
            subj, body = _compose(rng, lang)
            seen.add(body)
            w.writerow([f"{prefix}-{i:06d}", f"client{i}@example.com", subj, body,
                        ts.isoformat(), lang])

    span = (timestamps[-1] - timestamps[0]).days if timestamps else 0
    # Le taux de textes distincts est **affiche**, et pas seulement espere : c'est precisement le
    # chiffre dont l'absence a fait croire, au S7, a un defaut de l'algorithme de regroupement.
    print(f"{n} lignes ecrites -> {out} (prefixe {prefix}-, reparties sur {span} jours, "
          f"{len(seen)} corps distincts sur {n})")


def _compose(rng: random.Random, lang: str) -> tuple[str, str]:
    """Un sujet et un corps, tires d'un theme puis remplis de details variables.

    Les trous (`{order}`, `{amount}`, `{date}`, `{channel}`) rendent chaque ticket quasi unique
    tout en le laissant rattache a son theme. C'est la structure qu'un regroupement doit
    retrouver — et sur laquelle il peut echouer, ce qu'un corpus de duplicatas exacts ne permet
    pas de constater.
    """
    theme = rng.choice(THEMES)[lang]
    fields = {
        "order": f"{rng.randrange(10000, 99999)}",
        "amount": f"{rng.randrange(9, 480)},{rng.randrange(0, 99):02d}",
        "date": f"{rng.randrange(1, 29):02d}/{rng.randrange(1, 13):02d}",
        "channel": rng.choice(CHANNELS_FR if lang == "fr" else CHANNELS_EN),
    }
    return (rng.choice(theme["subjects"]).format(**fields),
            rng.choice(theme["bodies"]).format(**fields))


def _timestamps(n: int, rng: random.Random) -> list[datetime]:
    """Horodatages tries, repartis sur une fenetre glissante qui se termine aujourd'hui.

    **Fenetre glissante et non date fixe** : les vues horaires, le detecteur d'anomalies et le
    calcul de risque SLA regardent tous « les N derniers jours ». Un corpus date de janvier 2026
    serait hors de toutes ces fenetres, et chacun de ces ecrans afficherait un vide qu'on prendrait
    pour une panne.
    """
    # Environ 300 tickets par jour : la fenetre s'ajuste au volume demande, avec un plancher de
    # deux semaines pour que la saisonnalite horaire ait de quoi s'estimer (S7-J2).
    days = max(14, n // 300)
    # `end` porte les minutes reelles : arrondir a l'heure ferait retomber tous les tickets du
    # jour courant sur les heures deja ecoulees seulement, ce qui est justement ce qu'on veut.
    end = datetime.now().replace(second=0, microsecond=0)

    out: list[datetime] = []
    for _ in range(n):
        # Decompte **depuis maintenant** et non depuis le debut de la fenetre.
        #
        # La version precedente tirait un decalage depuis `start`, dans [0, days-1] : le moment le
        # plus recent atteignable etait donc `end - 1 jour`, et **les vingt-quatre dernieres heures
        # etaient toujours vides**. Tout ecran regardant « les dernieres 24 h » s'affichait vide
        # juste apres une generation — carte de charge horaire, tickets recents, et surtout la
        # baseline du detecteur d'anomalies sur les heures ou l'on veut justement injecter un pic.
        offset_days = rng.randrange(days)
        moment = end - timedelta(days=offset_days)
        # Le week-end recoit environ un tiers du volume d'un jour ouvre : on retire des tickets
        # plutot que d'en ajouter en semaine, ce qui garde le total demande a peu pres exact.
        if moment.weekday() >= 5 and rng.random() > 0.35:
            moment = end - timedelta(days=(offset_days + 2) % days)

        hour = rng.choices(range(24), weights=HOUR_WEIGHTS)[0]
        stamp = moment.replace(hour=hour) + timedelta(minutes=rng.randrange(60),
                                                      seconds=rng.randrange(60))
        # Le jour le plus recent n'est ecoule qu'en partie : une heure tiree au-dela de l'heure
        # courante tomberait dans le futur. On la reporte a la veille plutot que de la rejeter,
        # ce qui fausserait la distribution horaire.
        if stamp > end:
            stamp -= timedelta(days=1)
        out.append(stamp)

    out.sort()
    return out


if __name__ == "__main__":
    main()
