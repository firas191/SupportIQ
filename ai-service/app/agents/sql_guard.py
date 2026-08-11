"""Validation du SQL généré par l'agent Insight (S6-J1, rapport §5.3 et §11).

**Pourquoi un analyseur syntaxique et pas une liste de mots interdits.** Le réflexe est de chercher
`DROP`, `DELETE`, `;` dans la chaîne. Cette approche perd toujours, parce qu'elle raisonne sur des
caractères là où la base raisonne sur une grammaire : `DEL/**/ETE`, `dElEtE`, un mot-clé encodé, une
requête qui n'a aucun mot interdit mais lit `users` en sous-requête — chaque contournement demande
une nouvelle règle, et la liste n'est jamais finie.

`sqlglot` construit l'**arbre syntaxique** de la requête. On ne demande plus « contient-elle ce
mot ? » mais « que fait-elle ? ». La différence est celle qui sépare un filtre anti-spam par
mots-clés d'un antivirus qui exécute en bac à sable.

**Ce module ne fait pas confiance à lui-même.** Il constitue la première des deux barrières décrites
dans la migration V11 ; la seconde est le rôle PostgreSQL `insight_ro`, qui n'a physiquement pas le
droit de lire les tables brutes ni d'écrire. Si un contournement passe ici, la base refuse quand
même. C'est délibéré : `sqlglot` est un excellent analyseur, ce n'est pas une preuve formelle, et
une architecture qui parie sur l'exhaustivité d'une liste est une architecture fragile.

**Stratégie : liste blanche partout où c'est possible.** On n'énumère pas ce qui est interdit (fini
mal), on énumère ce qui est permis : un seul ordre, de type SELECT, sur trois vues nommées, avec des
fonctions connues. Tout le reste est refusé par défaut, y compris ce qu'on n'a pas imaginé.
"""
from __future__ import annotations

import logging

import sqlglot
from sqlglot import exp

logger = logging.getLogger(__name__)

DIALECT = "postgres"

#: Vues interrogeables. Doit rester **identique** aux GRANT de la migration V11 : les deux barrières
#: doivent autoriser exactement la même chose, sinon l'écart devient un bug difficile à diagnostiquer
#: (une requête acceptée ici et refusée par la base ressemble à une panne, pas à un refus).
ALLOWED_RELATIONS = frozenset(
    {
        "v_tickets",
        "v_daily_volume",
        "v_draft_activity",
        "v_ticket_stats",
        "v_category_trends",
        "v_hourly_load",
    }
)

#: Plafond de lignes. Une requête sans `LIMIT` en reçoit un ; une requête qui en demande plus est
#: ramenée à cette valeur. Motif : le résultat traverse un prompt en S6-J2, et 10 000 lignes
#: coûteraient plus cher en jetons que toute la campagne d'évaluation du S5-J5.
MAX_ROWS = 500

#: Fonctions refusées. Cette liste est un **filet**, pas la défense principale : elle attrape ce qui
#: est manifestement hostile même si la cible est une vue autorisée. La vraie protection reste le
#: rôle en lecture seule.
FORBIDDEN_FUNCTIONS = frozenset(
    {
        "pg_sleep",           # déni de service par épuisement de connexions
        "pg_read_file",       # lecture du système de fichiers du serveur
        "pg_read_binary_file",
        "pg_ls_dir",
        "lo_import",          # écriture/lecture de large objects
        "lo_export",
        "dblink",             # exfiltration vers un serveur distant
        "dblink_exec",
        "pg_terminate_backend",
        "pg_cancel_backend",
        "set_config",         # contournerait default_transaction_read_only
        "current_setting",    # peut révéler des paramètres de connexion
        "query_to_xml",       # exécute une requête arbitraire passée en chaîne
    }
)

#: Schémas système : jamais nécessaires à une question métier, très utiles à qui cartographie une
#: base avant de l'attaquer.
FORBIDDEN_SCHEMAS = frozenset({"pg_catalog", "information_schema", "pg_toast"})


class SqlRejected(Exception):
    """SQL refusé par la garde. Le message est **destiné aux journaux et à la boucle de
    réparation** du S6-J2, pas à l'utilisateur : il décrit précisément ce qui a déclenché le refus,
    ce qui aiderait autant un attaquant qu'un développeur."""

    def __init__(self, reason: str, detail: str = ""):
        super().__init__(reason)
        self.reason = reason
        self.detail = detail


def validate(sql: str, max_rows: int = MAX_ROWS) -> str:
    """Valide et normalise une requête. Renvoie le SQL à exécuter, ou lève `SqlRejected`.

    La sortie est **régénérée depuis l'arbre**, pas la chaîne d'origine : ce qui est exécuté est
    exactement ce qui a été analysé. Sans cela, une astuce d'encodage pourrait faire diverger le
    texte validé du texte exécuté — la faille classique des validateurs qui laissent passer la
    chaîne brute après contrôle.
    """
    if not sql or not sql.strip():
        raise SqlRejected("empty_sql")

    # --- 1. Un seul ordre --------------------------------------------------
    #
    # `parse` renvoie une liste : deux éléments signifient un enchaînement par `;`, la forme
    # canonique de l'injection (« ... ; DROP TABLE users »). On refuse au lieu de garder le premier.
    try:
        statements = sqlglot.parse(sql, dialect=DIALECT)
    except Exception as exc:
        raise SqlRejected("unparseable", str(exc)[:200]) from exc

    statements = [s for s in statements if s is not None]
    if len(statements) != 1:
        raise SqlRejected("multiple_statements", f"{len(statements)} ordres")

    statement = statements[0]

    # --- 2. Un SELECT, et rien d'autre -------------------------------------
    #
    # Liste blanche de types de nœuds racine. `Select` couvre le cas courant, `Union` les
    # `UNION/INTERSECT/EXCEPT`, `Subquery` une requête entre parenthèses. Un `WITH` est porté par
    # l'expression elle-même (`statement.args["with"]`), pas par un type distinct.
    if not isinstance(statement, (exp.Select, exp.Union, exp.Subquery)):
        raise SqlRejected("not_a_select", type(statement).__name__)

    # --- 3. Aucun nœud d'écriture ou de commande, où qu'il soit ------------
    #
    # Le contrôle porte sur **tout** l'arbre, pas seulement la racine. Une CTE peut écrire en
    # PostgreSQL (`WITH x AS (DELETE FROM ... RETURNING *) SELECT * FROM x`) : la racine est un
    # SELECT parfaitement légitime, et c'est précisément ce qui rend cette forme dangereuse.
    for node in statement.walk():
        if isinstance(node, _FORBIDDEN_NODES):
            raise SqlRejected("write_or_command", type(node).__name__)

    # --- 4. Toutes les relations lues sont autorisées ----------------------
    _check_relations(statement)

    # --- 5. Aucune fonction interdite --------------------------------------
    _check_functions(statement)

    # --- 6. Plafond de lignes ----------------------------------------------
    statement = _apply_limit(statement, max_rows)

    # `comments=False` : la requête exécutée ne contient plus que de la structure. Deux raisons.
    #
    # La première est de principe : un commentaire est du texte libre issu du modèle, donc en
    # dernier ressort de la question de l'utilisateur. Il voyagera dans les journaux et, en S6-J2,
    # dans le prompt de synthèse. Rien n'oblige à le transporter.
    #
    # La seconde a été trouvée en écrivant les tests. sqlglot **conserve** les commentaires par
    # défaut, et convertit `-- ligne` en `/* bloc */` lors du rendu. C'est heureux : sans cette
    # conversion, un `-- ` en fin de requête aurait neutralisé le `LIMIT` ajouté juste après, et le
    # plafond de lignes aurait sauté sans que rien ne le signale. On ne veut pas dépendre d'un
    # détail d'implémentation d'une bibliothèque tierce pour une garantie de sécurité.
    return statement.sql(dialect=DIALECT, comments=False)


# ---------------------------------------------------------------------------
# Contrôles
# ---------------------------------------------------------------------------

#: Types de nœuds qui écrivent, modifient le schéma, changent la session ou sortent du moteur.
#: Énumérés explicitement : `getattr` permet au module de rester importable si une version de
#: sqlglot renomme ou retire une classe, plutôt que de casser au chargement.
_FORBIDDEN_NODE_NAMES = (
    "Insert", "Update", "Delete", "Merge",
    "Drop", "Create", "Alter", "TruncateTable",
    "Grant", "Revoke",
    "Command",       # tout ordre que sqlglot ne modélise pas finement (COPY, VACUUM, CALL...)
    "Transaction", "Commit", "Rollback",
    "Set", "SetItem",
    "Copy",
    "Use",
    "Into",          # SELECT ... INTO nouvelle_table = une écriture déguisée en lecture
)
_FORBIDDEN_NODES = tuple(
    node for node in (getattr(exp, name, None) for name in _FORBIDDEN_NODE_NAMES) if node is not None
)


def _check_relations(statement: exp.Expression) -> None:
    """Toute table lue doit être une vue autorisée.

    Les CTE sont des noms locaux, pas des relations physiques : elles sont collectées d'abord et
    exclues du contrôle, sinon `WITH recent AS (SELECT ... FROM v_tickets) SELECT * FROM recent`
    serait refusé pour la lecture de « recent », qui n'existe nulle part.
    """
    cte_names = {
        cte.alias_or_name.lower()
        for cte in statement.find_all(exp.CTE)
        if cte.alias_or_name
    }

    tables = list(statement.find_all(exp.Table))
    if not tables:
        # Une requête qui ne lit aucune relation (`SELECT 1`, `SELECT pg_sleep(10)`) n'a aucune
        # raison d'être posée par un manager, et c'est la forme de sondage la plus courante.
        raise SqlRejected("no_relation")

    for table in tables:
        name = (table.name or "").lower()
        schema = (table.db or "").lower()

        if schema in FORBIDDEN_SCHEMAS:
            raise SqlRejected("system_schema", f"{schema}.{name}")
        if name in cte_names and not schema:
            continue
        if name not in ALLOWED_RELATIONS:
            # Couvre d'un coup `users`, `tickets`, `refresh_tokens`, `kb_documents` et tout ce qui
            # n'a pas encore été créé : la liste blanche n'a pas à être tenue à jour quand le schéma
            # évolue.
            raise SqlRejected("relation_not_allowed", f"{schema + '.' if schema else ''}{name}")


def _check_functions(statement: exp.Expression) -> None:
    """Refuse les fonctions dangereuses, quelle que soit la façon dont elles sont écrites.

    sqlglot modélise certaines fonctions par une classe dédiée et les autres par un nœud
    `Anonymous` portant leur nom. On regarde donc les deux, en minuscules — `PG_SLEEP` et
    `pg_sleep` sont la même chose pour PostgreSQL, elles doivent l'être ici aussi.
    """
    for node in statement.find_all(exp.Anonymous):
        if str(node.this or "").lower() in FORBIDDEN_FUNCTIONS:
            raise SqlRejected("forbidden_function", str(node.this))

    for node in statement.find_all(exp.Func):
        name = getattr(node, "sql_name", None)
        if callable(name) and str(name()).lower() in FORBIDDEN_FUNCTIONS:
            raise SqlRejected("forbidden_function", str(name()))


def _apply_limit(statement: exp.Expression, max_rows: int) -> exp.Expression:
    """Impose un plafond de lignes.

    Sans `LIMIT`, une requête sur 10 000 tickets renvoie 10 000 lignes qu'il faudra transporter,
    afficher et — en S6-J2 — faire relire par un modèle. Le plafond n'est pas une protection contre
    l'attaque mais contre l'imprudence, qui est bien plus fréquente.
    """
    existing = statement.args.get("limit")
    if existing is None:
        return statement.limit(max_rows)

    try:
        requested = int(existing.expression.this)
    except (AttributeError, TypeError, ValueError):
        # `LIMIT $1` ou une expression non littérale : on remplace par le plafond plutôt que
        # d'essayer d'interpréter.
        return statement.limit(max_rows)

    return statement if requested <= max_rows else statement.limit(max_rows)


def schema_description() -> str:
    """Description des vues destinée au prompt de génération.

    Écrite à la main plutôt que lue dans `information_schema` : le modèle a besoin de savoir *à quoi
    sert* une colonne et *quelles valeurs* elle prend, pas seulement son type. « category VARCHAR »
    ne lui apprend rien ; « category — FACTURATION, COMPTE, TECHNIQUE, RECLAMATION, DEMANDE ou
    NON_ANALYSE » lui évite d'inventer une valeur qui ne renverra aucune ligne.
    """
    return """RÈGLE DE GRAIN — chaque vue a une granularité différente. Ne JAMAIS combiner deux vues
par UNION : leurs lignes ne représentent pas la même chose et les compter ensemble n'a aucun sens.
Choisir UNE vue, celle dont le grain correspond à la question.

VUES DÉJÀ AGRÉGÉES — v_daily_volume, v_category_trends et v_hourly_load contiennent déjà des
décomptes (colonnes `tickets` et `ticket_count`). Il faut les LIRE ou les SOMMER (SUM), jamais
COUNT(*) : COUNT(*) y compterait les lignes d'agrégat — le nombre d'heures, le nombre de jours —
et non le nombre de tickets. Seule v_tickets se compte avec COUNT(*).

Les tickets non analysés valent NULL dans v_tickets et 'NON_ANALYSE' dans v_daily_volume.

v_tickets — GRAIN : un ticket par ligne (aucune donnée personnelle)
  ticket_id, created_at (timestamptz), sla_due_at, subject (texte court)
  status: NEW | ANALYZED | IN_PROGRESS | RESOLVED | MERGED
  source: FILE | WEBHOOK | EMAIL | MANUAL
  language: fr | en
  category: TECHNIQUE | FACTURATION | COMPTE | RECLAMATION | DEMANDE (NULL si non analysé)
  priority: LOW | MEDIUM | HIGH (NULL si non analysé)
  sentiment: NEG | NEU | POS (NULL si non analysé)
  confidence (0-1), escalated_to_llm (bool), is_analysed (bool), is_merged (bool)
  age_hours (numérique) — ancienneté du ticket en heures

v_daily_volume — GRAIN : une ligne par (jour, catégorie, priorité, humeur, canal, langue)
  day (date), category, priority, sentiment, source, language, tickets (entier)
  `tickets` est DÉJÀ un décompte : utiliser SUM(tickets), jamais COUNT(*).
  Les colonnes non analysées valent 'NON_ANALYSE' / 'INCONNUE' / 'INCONNU', jamais NULL.

v_draft_activity — GRAIN : une réponse proposée par ligne
  day (date), status: PROPOSED | EDITED | SENT | REJECTED
  tone: formal | empathetic
  low_confidence (bool), abstained (bool), attempts (entier), judge_score (0-1, souvent NULL)
  was_edited (bool) — le texte a été RÉÉCRIT par un humain (retouché, corrigé, modifié)
  reviewed_by (email) — qui a tranché ; non NULL dès qu'une décision a été prise, même sans retouche
  review_delay_minutes (numérique) — délai entre la proposition et la décision

v_ticket_stats — GRAIN : une seule ligne, indicateurs globaux sur tout l'historique
  total_tickets, new_tickets, resolved_tickets, analyzed_tickets, high_priority,
  negative_sentiment, escalated_to_llm, avg_confidence

v_category_trends — GRAIN : une ligne par (jour, catégorie)
  day (date), category, ticket_count (entier, DÉJÀ un décompte — utiliser SUM, jamais COUNT(*))
v_hourly_load — GRAIN : une ligne par heure de la journée
  hour_of_day (0-23), ticket_count (entier, déjà un décompte)

EXEMPLES
Q: combien de tickets par catégorie ?
A: SELECT COALESCE(category, 'NON_ANALYSE') AS category, COUNT(*) AS nb_tickets
   FROM v_tickets GROUP BY 1 ORDER BY nb_tickets DESC
Q: quelle catégorie a le plus augmenté cette semaine ?
A: SELECT category, SUM(tickets) AS nb_tickets FROM v_daily_volume
   WHERE day >= CURRENT_DATE - INTERVAL '7 days' GROUP BY category ORDER BY nb_tickets DESC"""
