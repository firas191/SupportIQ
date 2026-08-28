/** Alertes d'anomalie (S7-J2) — miroirs des DTO Spring. */

export type AlertSeverity = 'INFO' | 'WARNING' | 'CRITICAL';

/** Chiffres de la mesure. Le contenu depend du type d'alerte, d'ou les champs optionnels. */
export interface AlertPayload {
  observed?: number;
  expected?: number;
  score?: number;
  /** `stl` ou `seasonal_median` : un resultat obtenu par le repli ne vaut pas l'autre. */
  method?: string;
}

export interface Alert {
  id: number;
  type: string;
  severity: AlertSeverity;
  /** Objet concerne — aujourd'hui une categorie de tickets. */
  scope: string;
  /** Heure sur laquelle porte la mesure, distincte de `createdAt` (l'instant du calcul). */
  bucketStart: string;
  payload: AlertPayload;
  acknowledgedBy: number | null;
  acknowledgedByEmail: string | null;
  acknowledgedAt: string | null;
  createdAt: string;
}
