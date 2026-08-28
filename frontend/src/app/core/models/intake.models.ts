/** Ingestion de documents non structures (S7-J4) — miroirs des DTO Spring. */

/**
 * Confiance **par champ**.
 *
 * Bien plus utile qu'un score global : en pratique le sujet et le corps sont
 * presque toujours bons, et c'est l'adresse du client qui manque ou qui est mal
 * recopiee. « 0,7 » ne dit pas quoi relire ; « adresse : 0,3 » le dit.
 */
export interface FieldConfidence {
  subject: number;
  body: number;
  customerEmail: number;
}

export interface ProposedTicket {
  subject: string | null;
  body: string | null;
  customerEmail: string | null;
  language: string | null;
  confidence: FieldConfidence;
}

export interface ExtractionResult {
  tickets: ProposedTicket[];
  pages: number;
  /** `native`, `ocr` ou `plain`. Un lot issu d'OCR merite une relecture plus attentive. */
  method: string;
}

export interface ConfirmResult {
  created: number;
  skipped: number;
  ticketIds: number[];
}
