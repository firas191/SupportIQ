/**
 * Base de connaissances (S5-J1) — miroirs des DTO Spring.
 *
 * L'unite manipulee cote interface est le **document**, pas le fragment : un administrateur
 * raisonne en « j'ai charge la FAQ facturation ». Les fragments n'apparaissent que dans les
 * resultats de recherche, ou ils sont precisement ce qu'on veut voir.
 */

/** Document indexe, agrege depuis ses fragments. */
export interface KbDocument {
  source: string;
  title: string;
  /** Nombre de fragments issus du decoupage semantique. */
  chunks: number;
  /**
   * Fragments effectivement vectorises. Un ecart avec `chunks` signale des passages stockes mais
   * **invisibles a la recherche** — c'est ce qui declenche une reindexation.
   */
  indexed: number;
  updatedAt: string | null;
  fullyIndexed: boolean;
}

export interface KbOverview {
  documents: KbDocument[];
  totalDocuments: number;
  totalChunks: number;
}

/** Fragment retrouve. `source` et `heading` formeront la citation de l'agent Resolution (S5-J3). */
export interface KbChunk {
  id: number;
  title: string;
  source: string;
  chunkIndex: number;
  heading: string | null;
  content: string;
  /** Cosinus dans l'espace des embeddings : 1 = identique. */
  similarity: number;
}

export interface KbIngestResult {
  source: string;
  title: string;
  chunks: number;
  indexed: number;
  characters: number;
}

/**
 * Regime de recherche (S5-J2).
 *
 * `vector` : embeddings seuls — c'est ce qui a ete livre au J1, conserve comme point de
 * comparaison. `hybrid` : BM25 + vecteurs, fusion par rang reciproque, puis reclassement par
 * cross-encodeur.
 */
export type KbSearchMode = 'vector' | 'hybrid';
