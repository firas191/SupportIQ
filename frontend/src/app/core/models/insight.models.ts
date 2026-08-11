/** Chat Insight (miroir de InsightAnswer cote backend, S6-J3). */

/**
 * Graphique deduit du resultat **par le code**, pas par le modele (S6-J2).
 *
 * `none` est une valeur normale : `reason` dit pourquoi, ce qui permet d'ecrire
 * « une seule valeur, pas de graphique » plutot que d'afficher un cadre vide —
 * lequel se lit comme une panne.
 */
export interface InsightChart {
  type: 'bar' | 'line' | 'none';
  x: string | null;
  y: string | null;
  reason: string;
}

/**
 * Une cellule de resultat. Type ferme plutot que `unknown` : le gabarit doit
 * pouvoir interpoler la valeur, et `strictTemplates` refuse `unknown`.
 */
export type InsightValue = string | number | boolean | null;

export interface InsightAnswer {
  question: string;
  /** Requete executee. Affichee en clair : c'est le « mode transparent » du rapport §9. */
  sql: string;
  /** Synthese en une ou deux phrases. Vide si le service de generation etait indisponible. */
  answer: string;
  chart: InsightChart;
  columns: string[];
  rows: InsightValue[][];
  rowCount: number;
  /** Le plafond de lignes a tronque : le total affiche n'est pas le total reel. */
  truncated: boolean;
}

/*
 * L'echange affiche dans le fil (question + etat + reponse) n'est PAS declare
 * ici : c'est un objet d'interface, pas un contrat d'API. Ce fichier ne decrit
 * que ce qui traverse le reseau. Le melange des deux est ce qui finit par faire
 * voyager des champs d'affichage jusqu'au serveur.
 */
