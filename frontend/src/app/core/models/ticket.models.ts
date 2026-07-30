export type TicketSource = 'FILE' | 'WEBHOOK' | 'EMAIL' | 'MANUAL';
export type TicketStatus = 'NEW' | 'ANALYZED' | 'IN_PROGRESS' | 'RESOLVED' | 'MERGED';

/** Vue liste d'un ticket (miroir de TicketSummaryResponse cote backend). */
export interface TicketSummary {
  id: number;
  externalRef: string | null;
  source: TicketSource;
  customerEmail: string | null;
  subject: string | null;
  excerpt: string | null;
  language: string | null;
  status: TicketStatus;
  slaDueAt: string | null;
  createdAt: string;

  /*
   * Resultat du classement automatique.
   *
   * `null` tant que le ticket n'a pas ete analyse : la vue liste utilise une
   * jointure externe, un ticket tout juste recu sort donc quand meme, sans ces
   * champs. C'est une information utile en soi — la liste affiche « en
   * attente » plutot que de masquer la ligne.
   */
  priority: TicketPriority | null;
  category: TicketCategory | null;
  sentiment: TicketSentiment | null;
}

/** Enveloppe de pagination (miroir de PageResponse cote backend). */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
}

/** Analyse IA affichee dans la fiche ticket (miroir de TicketDetailResponse.Analysis). */
export interface TicketAnalysis {
  priority: string;
  category: string;
  sentiment: string;
  keywords: string[];
  confidence: number | null;
  modelUsed: string | null;
  escalatedToLlm: boolean;
  createdAt: string | null;
}

/** Ticket proche (pgvector) ; `duplicate` = candidat a la fusion. */
export interface SimilarTicket {
  ticketId: number;
  subject: string | null;
  category: string | null;
  similarity: number | null;
  duplicate: boolean;
}

/** Fiche ticket complete (miroir de TicketDetailResponse). */
export interface TicketDetail {
  id: number;
  externalRef: string | null;
  source: TicketSource;
  customerEmail: string | null;
  subject: string | null;
  body: string | null;
  language: string | null;
  status: TicketStatus;
  slaDueAt: string | null;
  createdAt: string;
  mergedIntoId: number | null;
  analysis: TicketAnalysis | null;
  similar: SimilarTicket[];
}

export type TicketCategory = 'TECHNIQUE' | 'FACTURATION' | 'COMPTE' | 'RECLAMATION' | 'DEMANDE';
export type TicketPriority = 'LOW' | 'MEDIUM' | 'HIGH';
export type TicketSentiment = 'NEG' | 'NEU' | 'POS';

/** Parametres de la requete liste/recherche ; champs vides omis par le service. */
export interface TicketQuery {
  q?: string;
  status?: TicketStatus;
  source?: TicketSource;
  language?: string;
  category?: TicketCategory;
  priority?: TicketPriority;
  sentiment?: TicketSentiment;
  page?: number;
  size?: number;
  sort?: string;
  direction?: 'asc' | 'desc';
}
