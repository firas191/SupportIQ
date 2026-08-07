-- V10 — Boucle de validation humaine des brouillons (S5-J4, rapport §5.2).
--
-- Trois colonnes, chacune pour une raison distincte. Elles sont ajoutées ici et non intégrées à V9
-- parce que V9 est déjà appliquée : une migration jouée ne se réécrit jamais (convention §3).

ALTER TABLE draft_responses

    -- 1. `final_content` — le texte tel que l'humain l'a corrigé.
    --
    -- La tentation était d'écraser `content`. C'est un piège : le LLM-as-judge du S5-J5 note la
    -- qualité **du modèle**. S'il lit un texte réécrit par un agent, il note l'agent. `content`
    -- devient donc immuable — c'est la sortie du modèle, point — et la version humaine vit à côté.
    --
    -- Bénéfice inattendu et supérieur au taux de rejet : la distance entre les deux mesure
    -- **combien** il a fallu corriger. « 80 % de brouillons validés » ne dit rien si les agents
    -- réécrivent la moitié de chaque phrase avant de valider.
    ADD COLUMN final_content TEXT,

    -- 2. `reviewed_at` — quand la décision a été prise.
    --
    -- `reviewed_by` (V9) dit qui, `created_at` dit quand le brouillon est né. Il manquait le délai
    -- entre proposition et décision : c'est lui qui répond à « l'assistance fait-elle gagner du
    -- temps ? », la seule question qui compte pour l'encadrant.
    ADD COLUMN reviewed_at TIMESTAMPTZ,

    -- 3. `abstained` — le modèle a reconnu que la documentation ne couvre pas la demande.
    --
    -- Sans cette colonne, l'abstention est indiscernable d'un brouillon ordinaire dès qu'on relit
    -- la ligne en base. L'interface proposerait alors « Approuver et envoyer » sur un texte qui dit
    -- « je n'ai pas trouvé d'information » — c'est-à-dire qu'elle inviterait à envoyer ça au
    -- client. Un défaut d'affichage qui produit une mauvaise réponse n'est pas cosmétique.
    ADD COLUMN abstained BOOLEAN NOT NULL DEFAULT FALSE;

-- Le panneau de la fiche demande « le dernier brouillon **non rejeté** de ce ticket » : un
-- brouillon rejeté reste en base pour la mesure, mais ne doit plus s'afficher. L'index de V9
-- (ticket_id, created_at DESC) couvre déjà ce tri ; rien à ajouter.

COMMENT ON COLUMN draft_responses.content IS
    'Sortie brute du modèle. Immuable : c''est elle que note le LLM-as-judge (S5-J5).';
COMMENT ON COLUMN draft_responses.final_content IS
    'Texte après correction humaine. NULL = validé sans modification.';
