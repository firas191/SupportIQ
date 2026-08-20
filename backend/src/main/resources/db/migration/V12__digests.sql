-- V12 — Historique des synthèses hebdomadaires (S6-J4, rapport §9).
--
-- Cette table porte trois responsabilités qui justifient chacune une colonne, et une décision
-- d'architecture qui remplace un ordonnanceur.

CREATE TABLE digests (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- Lundi de la semaine couverte. **UNIQUE, et c'est le cœur du dispositif.**
    --
    -- Cette contrainte fait à elle seule trois choses qu'on confierait sinon à un ordonnanceur :
    --   1. **idempotence** — deux déclenchements pour la même semaine ne produisent qu'un digest ;
    --   2. **rattrapage** — si l'application était arrêtée lundi 8 h, il suffit de constater à
    --      n'importe quel démarrage qu'aucune ligne n'existe pour la semaine courante ;
    --   3. **sûreté multi-instance** — deux nœuds qui insèrent en même temps, un seul gagne, et
    --      le perdant reçoit une violation de contrainte au lieu d'envoyer un doublon.
    --
    -- C'est la raison pour laquelle Quartz n'est pas utilisé (voir `DigestScheduler`) : la
    -- persistance des déclencheurs qu'il apporte est déjà ici, dans une contrainte d'unicité.
    week_start    DATE         NOT NULL UNIQUE,

    -- Le texte de la synthèse. **Le PDF n'est pas stocké** : c'est un rendu du Markdown, dérivable
    -- à tout moment. Conserver un binaire dérivé obligerait à le migrer à chaque changement de
    -- mise en forme, et à répondre « lequel fait foi » le jour où les deux divergent.
    markdown      TEXT         NOT NULL,

    -- Chiffres bruts ayant servi à la rédaction. Conservés pour que le digest reste vérifiable :
    -- on peut recalculer et comparer sans rejouer la génération.
    stats         JSONB        NOT NULL DEFAULT '{}'::jsonb,

    generated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- Envoi séparé de la génération : un digest peut exister sans être parti (serveur SMTP
    -- injoignable). Confondre les deux ferait perdre le travail de génération à chaque échec
    -- d'envoi, et empêcherait de réessayer.
    sent_at       TIMESTAMPTZ,
    recipients    TEXT,
    -- Cause du dernier échec d'envoi. Un envoi raté doit être **visible** : sans cette colonne,
    -- personne ne sait que le digest de la semaine n'est jamais parti.
    send_error    TEXT
);

CREATE INDEX ix_digests_week ON digests (week_start DESC);

COMMENT ON TABLE digests IS
    'Synthese hebdomadaire. UNIQUE(week_start) porte idempotence, rattrapage et surete multi-instance.';
