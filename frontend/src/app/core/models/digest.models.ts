/** Synthese hebdomadaire (miroir de Digest cote backend, S6-J4). */

export interface Digest {
  id: number;
  /** Lundi de la semaine couverte. */
  weekStart: string;
  markdown: string;
  generatedAt: string;
  /** `null` = jamais envoye (envoi non configure, ou echec). */
  sentAt: string | null;
  recipients: string | null;
  /**
   * Cause du dernier echec d'envoi. Une synthese qui n'est jamais partie doit se
   * voir : un courriel manquant en silence est pire qu'une erreur affichee.
   */
  sendError: string | null;
}

export interface DigestStatus {
  mailConfigured: boolean;
  recipients: string;
}
