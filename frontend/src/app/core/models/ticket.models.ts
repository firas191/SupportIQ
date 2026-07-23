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

/** Parametres de la requete liste ; champs vides omis par le service. */
export interface TicketQuery {
  q?: string;
  status?: TicketStatus;
  source?: TicketSource;
  language?: string;
  page?: number;
  size?: number;
  sort?: string;
  direction?: 'asc' | 'desc';
}
