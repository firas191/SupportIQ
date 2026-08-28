-- V15 — Sujets émergents (S7-J1, rapport §9).
--
-- **Un instantané par exécution, pas une identité qui persiste.**
--
-- La tentation était de donner à chaque sujet une identité stable et de suivre son évolution jour
-- après jour. C'est impossible à garantir : le clustering est **non supervisé**. Rien n'assure que
-- le groupe « échec de paiement mobile » de mardi soit le même objet que celui de mercredi — il
-- peut s'être scindé, fusionné avec un voisin, ou disparaître sous le seuil de densité. Les
-- apparier par ressemblance de libellé produirait un historique inventé.
--
-- On enregistre donc un **instantané complet** à chaque exécution, et la croissance est calculée
-- *à l'intérieur* de la fenêtre analysée (voir `recent_count` / `previous_count`). Un sujet dont
-- les deux tiers des tickets sont arrivés dans la seconde moitié de la fenêtre est émergent, et
-- cette affirmation ne dépend d'aucune exécution précédente.

CREATE TABLE topics (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- Toutes les lignes d'une même exécution partagent cet horodatage : c'est la clé de lecture
    -- « donne-moi le dernier instantané ».
    computed_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    window_days     SMALLINT     NOT NULL,

    -- Libellé rédigé par un modèle à partir des tickets les plus centraux du groupe.
    label           TEXT         NOT NULL,

    size            INTEGER      NOT NULL,
    -- Répartition dans la fenêtre : seconde moitié contre première. C'est de leur rapport que naît
    -- la croissance, sans jamais comparer deux exécutions entre elles.
    recent_count    INTEGER      NOT NULL,
    previous_count  INTEGER      NOT NULL,
    -- NULL quand la première moitié est vide : le sujet est nouveau, et « +∞ % » n'est pas un
    -- chiffre. Même choix qu'au digest (S6-J4).
    growth          NUMERIC(6,1),

    -- Quelques tickets représentatifs, pour que le libellé soit vérifiable d'un clic. Un tableau
    -- et non une table de liaison : on ne requête jamais « quels sujets contiennent ce ticket »,
    -- et l'instantané est remplacé en bloc.
    sample_ticket_ids BIGINT[]   NOT NULL DEFAULT '{}',

    -- Catégorie dominante du groupe, quand elle existe. Sert à colorer et à recouper le libellé
    -- avec le classement automatique — deux lectures indépendantes du même corpus.
    top_category    VARCHAR(32)
);

-- Lecture principale : le dernier instantané, sujets les plus gros d'abord.
CREATE INDEX ix_topics_snapshot ON topics (computed_at DESC, size DESC);

COMMENT ON TABLE topics IS
    'Instantane des sujets emergents. Une execution = un lot de lignes partageant computed_at.';
