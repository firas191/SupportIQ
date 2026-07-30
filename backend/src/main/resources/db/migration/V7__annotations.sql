-- V7 — Corrections humaines des analyses IA (S4-J4, rapport §4 : boucle d'active learning).
--
-- Chaque ligne est la trace d'une correction : ce que le modele avait predit (`predicted`) et ce
-- que l'humain a corrige (`corrected`), pour un champ donne. On garde l'historique complet (pas
-- d'UPDATE en place) : c'est ce jeu de donnees qui sera exporte en JSONL pour re-entrainer (S8),
-- et qui permet de mesurer le taux de correction par champ.

CREATE TABLE annotations (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id    BIGINT       NOT NULL,
    field        VARCHAR(16)  NOT NULL,
    predicted    VARCHAR(32),
    corrected    VARCHAR(32)  NOT NULL,
    corrected_by BIGINT       NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_annotations_ticket FOREIGN KEY (ticket_id)    REFERENCES tickets (id) ON DELETE CASCADE,
    CONSTRAINT fk_annotations_user   FOREIGN KEY (corrected_by) REFERENCES users (id),
    CONSTRAINT ck_annotations_field  CHECK (field IN ('priority', 'category', 'sentiment'))
);

CREATE INDEX ix_annotations_ticket ON annotations (ticket_id);
-- Pour la mesure « quel champ est le plus corrige ? » (indicateur de qualite du modele).
CREATE INDEX ix_annotations_field ON annotations (field, created_at);
