import { TranslationKey } from '../core/i18n/translations.fr';

/* =============================================================================
   SupportIQ — Vocabulaire produit
   -----------------------------------------------------------------------------
   Table de correspondance entre les valeurs techniques de l'API et ce que
   l'utilisateur voit : un libelle traduit, un ton (donc une couleur), parfois
   une icone.

   Depuis l'ajout du bilingue, ce fichier ne contient plus de texte : il ne
   contient que des **cles de traduction**. Le texte vit dans les dictionnaires,
   la semantique vit ici. Cette separation garantit que « NEG » a la meme
   couleur en francais et en anglais, et qu'ajouter une langue ne demande de
   toucher a aucune regle de presentation.

   Trois raisons de centraliser :
   1. **Coherence** — « NEG » s'affiche « Mecontent » / « Unhappy » partout :
      liste, fiche, graphique, filtre.
   2. **Le produit n'est pas la base de donnees** — `WEBHOOK`, `NEG`,
      `escalatedToLlm` sont des noms d'ingenieur. L'agent lit « Temps reel »,
      « Mecontent », « Analyse approfondie ».
   3. **Un seul point d'entree** pour ajouter une langue.
   ============================================================================= */

export type Tone = 'neutral' | 'accent' | 'info' | 'success' | 'warning' | 'danger';

export interface LabelDef {
  /** Cle de traduction du libelle court. */
  key: TranslationKey;
  tone: Tone;
  icon?: string;
  /** Cle de traduction d'une explication longue (infobulle). */
  hintKey?: TranslationKey;
}

/* -----------------------------------------------------------------------------
   Priorite
   -----------------------------------------------------------------------------
   **Aucune icone.** Les glyphes utilises auparavant (`priority_high` qui dessine
   un point d'exclamation, `drag_handle` deux barres, `expand_more` un chevron)
   se lisaient comme de la ponctuation parasite dans une colonne dense. Ils
   n'apportaient rien : le ton porte deja l'urgence, et le libelle la nomme.
   Le badge affiche desormais une pastille pleine de la couleur du ton — un
   point d'ancrage a position fixe, qui rend la colonne balayable d'un coup
   d'oeil sans la charger.
   -------------------------------------------------------------------------- */
export const PRIORITY_LABELS: Record<string, LabelDef> = {
  HIGH: { key: 'domain.priority.HIGH', tone: 'danger', hintKey: 'domain.priority.HIGH.hint' },
  MEDIUM: { key: 'domain.priority.MEDIUM', tone: 'warning', hintKey: 'domain.priority.MEDIUM.hint' },
  LOW: { key: 'domain.priority.LOW', tone: 'neutral', hintKey: 'domain.priority.LOW.hint' },
};

export const STATUS_LABELS: Record<string, LabelDef> = {
  NEW: { key: 'domain.status.NEW', tone: 'info', icon: 'fiber_new' },
  ANALYZED: { key: 'domain.status.ANALYZED', tone: 'accent', icon: 'auto_awesome' },
  IN_PROGRESS: { key: 'domain.status.IN_PROGRESS', tone: 'warning', icon: 'pending' },
  RESOLVED: { key: 'domain.status.RESOLVED', tone: 'success', icon: 'check_circle' },
  MERGED: { key: 'domain.status.MERGED', tone: 'neutral', icon: 'merge' },
};

/* Humeur : les icones sont conservees, ce sont de vrais pictogrammes (visages)
   et non des signes de ponctuation. Elles doublent l'information de couleur,
   ce qui rend la colonne lisible en cas de daltonisme. */
export const SENTIMENT_LABELS: Record<string, LabelDef> = {
  NEG: { key: 'domain.sentiment.NEG', tone: 'danger', icon: 'sentiment_dissatisfied' },
  NEU: { key: 'domain.sentiment.NEU', tone: 'neutral', icon: 'sentiment_neutral' },
  POS: { key: 'domain.sentiment.POS', tone: 'success', icon: 'sentiment_satisfied' },
};

/* Categorie : ton neutre volontaire. Elle classe, elle n'alerte pas ; lui
   donner une couleur vive volerait l'attention destinee a la priorite. La
   teinte d'identification passe par une pastille (voir --cat-*). */
export const CATEGORY_LABELS: Record<string, LabelDef> = {
  TECHNIQUE: { key: 'domain.category.TECHNIQUE', tone: 'neutral' },
  FACTURATION: { key: 'domain.category.FACTURATION', tone: 'neutral' },
  COMPTE: { key: 'domain.category.COMPTE', tone: 'neutral' },
  RECLAMATION: { key: 'domain.category.RECLAMATION', tone: 'neutral' },
  DEMANDE: { key: 'domain.category.DEMANDE', tone: 'neutral' },
  NON_ANALYSE: { key: 'domain.category.NON_ANALYSE', tone: 'neutral' },
};

export const CATEGORY_COLOR_VAR: Record<string, string> = {
  TECHNIQUE: 'var(--cat-technique)',
  FACTURATION: 'var(--cat-facturation)',
  COMPTE: 'var(--cat-compte)',
  RECLAMATION: 'var(--cat-reclamation)',
  DEMANDE: 'var(--cat-demande)',
  NON_ANALYSE: 'var(--cat-unknown)',
};

export const SOURCE_LABELS: Record<string, LabelDef> = {
  WEBHOOK: { key: 'domain.source.WEBHOOK', tone: 'accent', icon: 'bolt', hintKey: 'domain.source.WEBHOOK.hint' },
  FILE: { key: 'domain.source.FILE', tone: 'neutral', icon: 'table_view', hintKey: 'domain.source.FILE.hint' },
  EMAIL: { key: 'domain.source.EMAIL', tone: 'neutral', icon: 'mail' },
  MANUAL: { key: 'domain.source.MANUAL', tone: 'neutral', icon: 'edit_note' },
};

export const ROLE_LABELS: Record<string, LabelDef> = {
  ADMIN: { key: 'domain.role.ADMIN', tone: 'accent', icon: 'shield_person', hintKey: 'domain.role.ADMIN.hint' },
  MANAGER: { key: 'domain.role.MANAGER', tone: 'info', icon: 'insights', hintKey: 'domain.role.MANAGER.hint' },
  AGENT: { key: 'domain.role.AGENT', tone: 'neutral', icon: 'headset_mic', hintKey: 'domain.role.AGENT.hint' },
};

export const LANGUAGE_LABELS: Record<string, LabelDef> = {
  fr: { key: 'domain.language.fr', tone: 'neutral' },
  en: { key: 'domain.language.en', tone: 'neutral' },
};

/** Definition associee a une valeur, ou `null` si la valeur est inconnue/absente. */
export function labelOf(table: Record<string, LabelDef>, key: string | null | undefined): LabelDef | null {
  return key ? (table[key] ?? null) : null;
}

/* -----------------------------------------------------------------------------
   Qualite de l'analyse
   -----------------------------------------------------------------------------
   La plateforme classe chaque ticket automatiquement et, lorsque le premier
   passage n'est pas assez sur, en relance un second, plus pousse.

   C'est une information **produit** legitime : elle explique pourquoi certains
   tickets mettent quelques secondes de plus, et sert d'indicateur de cout pour
   un responsable. Ce qui ne remonte pas a l'ecran, c'est la mecanique : nom du
   modele, seuil, fournisseur. On expose le fait, pas l'implementation.
   -------------------------------------------------------------------------- */

export const ANALYSIS_DEPTH = {
  instant: { key: 'quality.instant', hintKey: 'quality.instantHint', tone: 'success', icon: 'bolt' },
  deep: { key: 'quality.deep', hintKey: 'quality.deepHint', tone: 'accent', icon: 'auto_awesome' },
} as const;

export function analysisDepth(escalated: boolean) {
  return escalated ? ANALYSIS_DEPTH.deep : ANALYSIS_DEPTH.instant;
}

export type ReliabilityLevel = 'high' | 'medium' | 'low';

export function reliabilityLevel(confidence: number | null | undefined): ReliabilityLevel | null {
  if (confidence == null) {
    return null;
  }
  const pct = confidence <= 1 ? confidence * 100 : confidence;
  return pct >= 80 ? 'high' : pct >= 55 ? 'medium' : 'low';
}

export const RELIABILITY_LABELS: Record<ReliabilityLevel, LabelDef> = {
  high: { key: 'quality.high', tone: 'success' },
  medium: { key: 'quality.medium', tone: 'warning' },
  low: { key: 'quality.low', tone: 'danger', hintKey: 'quality.lowHint' },
};
