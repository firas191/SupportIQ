-- V13 — Livraison effective de la réponse validée (S6, demi-journée hors planning).
--
-- Jusqu'ici, `SENT` signifiait « validé par un humain, bon pour envoi » : la plateforme n'avait
-- aucun canal de sortie (limite assumée et écrite au S5-J4). Spring Mail est arrivé avec le digest
-- (S6-J4), ce qui rend le canal disponible — et ce manque réparable.
--
-- **La décision humaine et la livraison sont deux faits distincts, donc deux colonnes.**
--
-- La tentation était de faire de `SENT` la preuve que le client a reçu la réponse. C'est faux, et
-- dangereusement : si le serveur SMTP refuse le message, un statut `SENT` ferait croire à l'agent
-- que le client a été répondu. Il passerait au ticket suivant.
--
-- On conserve donc : `reviewed_at` = quelqu'un a décidé (fait humain, définitif), `delivered_at` =
-- le message est parti (fait technique, susceptible d'échouer et d'être rejoué). Même séparation
-- que pour le digest (V12), et pour la même raison : un envoi qui échoue en silence est pire qu'une
-- erreur affichée.

ALTER TABLE draft_responses
    ADD COLUMN delivered_at   TIMESTAMPTZ,
    -- Adresse réellement servie. Conservée car l'adresse du ticket peut changer ensuite : sans
    -- elle, on ne saurait plus à qui la réponse est partie.
    ADD COLUMN delivered_to   TEXT,
    -- Cause du dernier échec. Une réponse client qui ne part pas doit se voir.
    ADD COLUMN delivery_error TEXT;

-- « Quelles réponses validées ne sont jamais parties ? » est la question à laquelle un responsable
-- doit pouvoir répondre en une requête.
CREATE INDEX ix_drafts_undelivered ON draft_responses (status)
    WHERE status = 'SENT' AND delivered_at IS NULL;

COMMENT ON COLUMN draft_responses.delivered_at IS
    'Envoi effectif au client. NULL avec status=SENT = valide mais jamais parti.';
