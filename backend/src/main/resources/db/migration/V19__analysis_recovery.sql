-- Rattrapage des tickets jamais analyses (S8-J1).
--
-- POURQUOI CETTE TABLE EXISTE
--
-- Le rapport de charge du S7-J5 annoncait, comme risque theorique : « un ticket cree pendant une
-- coupure du courtier n'est jamais analyse ; le rattrapage reste a faire ». Le cas s'est produit,
-- par un autre chemin -- perte des messages a la recreation du conteneur RabbitMQ, qui n'avait
-- aucun volume -- et a l'echelle de **60 016 tickets sur 63 057**.
--
-- Le trou n'est pas que ces tickets manquent d'analyse. C'est que **rien ne le signalait** : la
-- publication est best-effort et posterieure au commit (pour que l'operation metier ne depende
-- jamais du courtier), donc un message perdu ne produit aucune erreur, nulle part.
--
-- POURQUOI UNE TABLE, ET PAS UNE SIMPLE REQUETE
--
-- « Les tickets sans analyse » se trouvent en une jointure externe. Republier ceux-la suffirait --
-- et serait une **boucle de rejeu infinie**. Un ticket que l'analyse ne peut pas traiter (message
-- empoisonne parti en DLQ, contenu qui fait echouer le pipeline) serait retrouve a chaque passage,
-- republie indefiniment, consommant du quota LLM tout en ayant l'air de fonctionner.
--
-- Il faut donc se souvenir des tentatives. D'ou cette table, qui porte trois roles :
--   1. borner les tentatives (au-dela, on ABANDONNE et on **signale** au lieu de rejouer) ;
--   2. espacer les tentatives, sinon un arriere important se ferait republier en boucle avant
--      meme d'avoir ete consomme ;
--   3. servir de liste d'exclusion explicite (statut HORS_PERIMETRE).
--
-- POURQUOI UNE TABLE DEDIEE ET PAS DEUX COLONNES SUR `tickets`
--
-- (a) `tickets` est la table la plus ecrite du projet. Un UPDATE par tentative y creerait autant de
--     nouvelles versions de ligne -- exactement le gonflement qui a fausse les mesures du S7-J5,
--     ou un UPDATE de masse a fait passer la recherche plein texte de 7,5 ms a 17,3 ms.
-- (b) Seuls les tickets ayant reellement pose probleme ont une ligne ici. La table **est** la liste
--     des anomalies : la compter repond a « combien de tickets echappent au pipeline ? », question
--     a laquelle on ne pouvait pas repondre jusqu'ici.
-- (c) Meme frontiere que `analyses`, `sla_risks`, `topics` : l'etat annexe vit a cote, jamais dans
--     la table metier.

CREATE TABLE analysis_recovery (
    ticket_id       BIGINT PRIMARY KEY REFERENCES tickets(id) ON DELETE CASCADE,

    -- PENDING : au moins une republication faite, on attend l'analyse.
    -- GIVEN_UP : plafond de tentatives atteint. Le ticket n'est plus republie -- il est **signale**.
    -- OUT_OF_SCOPE : ticket dont on a decide qu'il ne doit pas etre analyse (corpus de charge).
    status          TEXT NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'GIVEN_UP', 'OUT_OF_SCOPE')),

    attempts        INT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_attempt_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Index de selection : le balayage cherche les lignes republiables, c'est-a-dire PENDING et pas
-- retentees trop recemment. Les GIVEN_UP et OUT_OF_SCOPE -- qui deviendront la majorite des lignes
-- des la migration ci-dessous -- sortent donc de l'index.
CREATE INDEX ix_analysis_recovery_pending ON analysis_recovery (last_attempt_at)
    WHERE status = 'PENDING';

-- ---------------------------------------------------------------------------------------------
-- Corpus de charge marque hors perimetre
-- ---------------------------------------------------------------------------------------------
--
-- Les tickets 'PERF-' et 'KILL-' sont des donnees de test generees a partir de six gabarits, pour
-- les mesures du S7-J5. Les faire analyser couterait du quota LLM (~46 % d'escalade mesuree au
-- S3-J5) pour produire des analyses de textes synthetiques que personne ne lira.
--
-- Ils sont donc inscrits comme OUT_OF_SCOPE : c'est une **decision explicite et reversible**, et
-- surtout elle est ecrite quelque part. L'alternative -- filtrer sur le prefixe dans la requete du
-- balayage -- cacherait une regle metier dans du SQL applicatif, et le jour ou un vrai ticket
-- porterait ce prefixe il disparaitrait sans que personne ne sache pourquoi.
--
-- Pour les remettre dans le circuit : DELETE FROM analysis_recovery WHERE status = 'OUT_OF_SCOPE';
INSERT INTO analysis_recovery (ticket_id, status, attempts)
SELECT t.id, 'OUT_OF_SCOPE', 0
FROM tickets t
LEFT JOIN analyses a ON a.ticket_id = t.id
WHERE a.ticket_id IS NULL
  AND (t.external_ref LIKE 'PERF-%' OR t.external_ref LIKE 'KILL-%')
ON CONFLICT (ticket_id) DO NOTHING;

COMMENT ON TABLE analysis_recovery IS
    'Suivi des tickets echappes au pipeline d''analyse (S8-J1). Une ligne = une anomalie.';
