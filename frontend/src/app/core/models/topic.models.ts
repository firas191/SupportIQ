/** Sujets emergents (S7-J1) — miroirs des DTO Spring. */

export interface Topic {
  id: number;
  computedAt: string;
  windowDays: number;
  label: string;
  size: number;
  /** Tickets de la seconde moitie de la fenetre. */
  recentCount: number;
  /** Tickets de la premiere moitie — la reference a laquelle `recentCount` se compare. */
  previousCount: number;
  /**
   * Croissance en pourcentage entre les deux moities de la fenetre.
   *
   * **`null` n'est pas zero.** Il signifie que rien n'existait avant : le sujet est apparu pendant
   * la fenetre. L'ecran en tire « nouveau », qui dit plus qu'un pourcentage n'aurait su le faire —
   * et afficher 0 ferait lire « stable » sur exactement l'inverse.
   */
  growth: number | null;
  /** Tickets les plus centraux du groupe, ceux qui justifient le libelle. */
  sampleTicketIds: number[];
  /** Categorie majoritaire, ou `null` si aucune ne l'emporte. */
  topCategory: string | null;
}

export interface TopicSnapshot {
  /** `null` = aucun instantane n'a jamais ete calcule, a distinguer de « calcule, rien trouve ». */
  computedAt: string | null;
  windowDays: number;
  topics: Topic[];
}
