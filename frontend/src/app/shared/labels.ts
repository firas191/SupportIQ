/* =============================================================================
   SupportIQ — Vocabulaire produit
   -----------------------------------------------------------------------------
   Couche de traduction unique entre les valeurs techniques de l'API et ce que
   l'utilisateur lit a l'ecran.

   Pourquoi centraliser ici plutot que d'ecrire le libelle dans chaque gabarit :

   1. **Coherence.** « NEG » doit s'afficher « Mecontent » partout — dans la
      liste, la fiche, le graphique, le filtre. Une seule table le garantit.

   2. **Le produit n'est pas la base de donnees.** `WEBHOOK`, `NEG`,
      `escalatedToLlm` sont des noms d'ingenieur. Un agent du support ne doit
      jamais les rencontrer : il lit « Temps reel », « Mecontent », « Analyse
      approfondie ». Le vocabulaire technique reste dans le code, pas a l'ecran.

   3. **Point d'entree d'une future internationalisation.** Le jour ou il faut
      une version anglaise, un seul fichier bouge.
   ============================================================================= */

export type Tone = 'neutral' | 'accent' | 'info' | 'success' | 'warning' | 'danger';

/** Ce qu'il faut pour afficher une valeur : un libelle, un ton, une icone. */
export interface LabelDef {
  label: string;
  tone: Tone;
  icon?: string;
  /** Description longue, utilisee en infobulle quand le libelle court ne suffit pas. */
  hint?: string;
}

const UNKNOWN: LabelDef = { label: '—', tone: 'neutral' };

/* -----------------------------------------------------------------------------
   Priorite
   Le ton suit l'urgence : rouge appelle une action, gris ne demande rien.
   -------------------------------------------------------------------------- */
export const PRIORITY_LABELS: Record<string, LabelDef> = {
  HIGH: { label: 'Urgente', tone: 'danger', icon: 'priority_high', hint: 'A traiter en premier' },
  MEDIUM: { label: 'Moyenne', tone: 'warning', icon: 'drag_handle', hint: 'Traitement standard' },
  LOW: { label: 'Basse', tone: 'neutral', icon: 'expand_more', hint: 'Peut attendre' },
};

/* -----------------------------------------------------------------------------
   Statut
   -------------------------------------------------------------------------- */
export const STATUS_LABELS: Record<string, LabelDef> = {
  NEW: { label: 'Nouveau', tone: 'info', icon: 'fiber_new' },
  ANALYZED: { label: 'Analyse', tone: 'accent', icon: 'auto_awesome' },
  IN_PROGRESS: { label: 'En cours', tone: 'warning', icon: 'pending' },
  RESOLVED: { label: 'Resolu', tone: 'success', icon: 'check_circle' },
  MERGED: { label: 'Fusionne', tone: 'neutral', icon: 'merge' },
};

/* -----------------------------------------------------------------------------
   Sentiment client
   « NEG / NEU / POS » ne veut rien dire pour un agent. Le libelle nomme
   directement l'etat du client, ce qui est l'information utile.
   -------------------------------------------------------------------------- */
export const SENTIMENT_LABELS: Record<string, LabelDef> = {
  NEG: { label: 'Mecontent', tone: 'danger', icon: 'sentiment_dissatisfied' },
  NEU: { label: 'Neutre', tone: 'neutral', icon: 'sentiment_neutral' },
  POS: { label: 'Satisfait', tone: 'success', icon: 'sentiment_satisfied' },
};

/* -----------------------------------------------------------------------------
   Categorie
   Ton neutre volontaire : la categorie classe, elle n'alerte pas. Lui donner
   une couleur vive volerait l'attention destinee a la priorite. La teinte
   d'identification passe par une simple pastille (voir --cat-*).
   -------------------------------------------------------------------------- */
export const CATEGORY_LABELS: Record<string, LabelDef> = {
  TECHNIQUE: { label: 'Technique', tone: 'neutral', icon: 'build' },
  FACTURATION: { label: 'Facturation', tone: 'neutral', icon: 'receipt_long' },
  COMPTE: { label: 'Compte', tone: 'neutral', icon: 'person' },
  RECLAMATION: { label: 'Reclamation', tone: 'neutral', icon: 'report' },
  DEMANDE: { label: 'Demande', tone: 'neutral', icon: 'help' },
  NON_ANALYSE: { label: 'Non classe', tone: 'neutral', icon: 'more_horiz' },
};

/** Teinte d'identification par categorie (pastilles, graphiques). */
export const CATEGORY_COLOR_VAR: Record<string, string> = {
  TECHNIQUE: 'var(--cat-technique)',
  FACTURATION: 'var(--cat-facturation)',
  COMPTE: 'var(--cat-compte)',
  RECLAMATION: 'var(--cat-reclamation)',
  DEMANDE: 'var(--cat-demande)',
  NON_ANALYSE: 'var(--cat-unknown)',
};

/* -----------------------------------------------------------------------------
   Origine du ticket
   « WEBHOOK » et « FILE » decrivent le transport. L'utilisateur, lui, veut
   savoir si le ticket est arrive tout seul ou s'il a ete verse en lot.
   -------------------------------------------------------------------------- */
export const SOURCE_LABELS: Record<string, LabelDef> = {
  WEBHOOK: { label: 'Temps reel', tone: 'accent', icon: 'bolt', hint: 'Recu automatiquement depuis un canal connecte' },
  FILE: { label: 'Import', tone: 'neutral', icon: 'table_view', hint: 'Verse depuis un fichier' },
  EMAIL: { label: 'E-mail', tone: 'neutral', icon: 'mail' },
  MANUAL: { label: 'Manuel', tone: 'neutral', icon: 'edit_note' },
};

/* -----------------------------------------------------------------------------
   Roles
   -------------------------------------------------------------------------- */
export const ROLE_LABELS: Record<string, LabelDef> = {
  ADMIN: { label: 'Administrateur', tone: 'accent', icon: 'shield_person', hint: 'Acces complet, gestion des comptes et des imports' },
  MANAGER: { label: 'Responsable', tone: 'info', icon: 'insights', hint: 'Acces aux indicateurs et a toute la file' },
  AGENT: { label: 'Agent', tone: 'neutral', icon: 'headset_mic', hint: 'Traitement des tickets' },
};

export const LANGUAGE_LABELS: Record<string, LabelDef> = {
  fr: { label: 'Francais', tone: 'neutral' },
  en: { label: 'Anglais', tone: 'neutral' },
};

/* -----------------------------------------------------------------------------
   Acces generique
   -------------------------------------------------------------------------- */

export function labelOf(table: Record<string, LabelDef>, key: string | null | undefined): LabelDef {
  if (!key) {
    return UNKNOWN;
  }
  return table[key] ?? { label: key, tone: 'neutral' };
}

export function textOf(table: Record<string, LabelDef>, key: string | null | undefined): string {
  return labelOf(table, key).label;
}

/* -----------------------------------------------------------------------------
   Qualite de l'analyse — reformulation
   -----------------------------------------------------------------------------
   La plateforme classe chaque ticket automatiquement et, lorsque le premier
   passage n'est pas assez sur, en relance un second, plus pousse.

   C'est une information **produit** legitime : elle explique pourquoi certains
   tickets mettent quelques secondes de plus, et elle sert d'indicateur de cout
   pour un responsable. Ce qui ne doit pas remonter a l'ecran, c'est la
   mecanique : nom du modele, seuil de confiance, fournisseur. On expose donc
   le fait, pas l'implementation.
   -------------------------------------------------------------------------- */

export const ANALYSIS_DEPTH = {
  instant: {
    label: 'Analyse instantanee',
    tone: 'success' as Tone,
    icon: 'bolt',
    hint: 'Classe des la reception, sans traitement supplementaire.',
  },
  deep: {
    label: 'Analyse approfondie',
    tone: 'accent' as Tone,
    icon: 'auto_awesome',
    hint: 'Le premier passage manquait de certitude : une seconde lecture, plus poussee, a ete lancee.',
  },
};

export function analysisDepth(escalated: boolean) {
  return escalated ? ANALYSIS_DEPTH.deep : ANALYSIS_DEPTH.instant;
}

/** Niveau de fiabilite d'une analyse, pour colorer l'indicateur. */
export function reliabilityLevel(confidence: number | null | undefined): 'high' | 'medium' | 'low' | null {
  if (confidence == null) {
    return null;
  }
  const pct = confidence <= 1 ? confidence * 100 : confidence;
  return pct >= 80 ? 'high' : pct >= 55 ? 'medium' : 'low';
}

export const RELIABILITY_LABELS: Record<'high' | 'medium' | 'low', LabelDef> = {
  high: { label: 'Fiabilite elevee', tone: 'success' },
  medium: { label: 'Fiabilite moyenne', tone: 'warning' },
  low: { label: 'A verifier', tone: 'danger', hint: 'Le classement automatique est incertain : une relecture humaine est recommandee.' },
};
