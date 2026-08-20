/**
 * Brouillon de reponse assiste (miroir de DraftView cote backend, S5-J4).
 */

export type DraftStatus = 'PROPOSED' | 'EDITED' | 'SENT' | 'REJECTED';

/** Deux registres suffisent : le courant, et celui des clients mecontents. */
export type DraftTone = 'formal' | 'empathetic';

/**
 * Source d'une affirmation du brouillon.
 *
 * `content` porte le passage complet, pas un extrait : verifier une affirmation
 * sur une source tronquee, c'est verifier a moitie. `stale` signale un passage
 * dont le document a ete reimporte depuis — le texte affiche est alors la copie
 * conservee au moment de la redaction, plus la source vivante.
 */
export interface DraftCitation {
  marker: number;
  chunkId: number | null;
  source: string | null;
  heading: string | null;
  content: string;
  stale: boolean;
}

export interface Draft {
  id: number;
  ticketId: number;
  /** Sortie du modele. Ne change jamais — c'est elle qu'on mesurera. */
  content: string;
  /** Version corrigee par un humain. `null` = personne n'a touche au texte. */
  finalContent: string | null;
  citations: DraftCitation[];
  status: DraftStatus;
  tone: DraftTone;
  /** L'auto-verification n'a pas converge : a lire avec attention. */
  lowConfidence: boolean;
  /**
   * La documentation ne couvre pas la demande. Ce n'est **pas** une alerte :
   * c'est une reponse correcte a une question hors perimetre, et l'interface
   * doit le presenter comme tel.
   */
  abstained: boolean;
  issues: string[];
  attempts: number;
  createdAt: string;
  reviewedAt: string | null;
  reviewedBy: string | null;

  /**
   * Livraison au client — **distincte de la validation**. `status = 'SENT'` avec
   * `deliveredAt = null` signifie « validée, mais jamais partie ». C'est un état
   * réel : sans lui, un agent croirait le client répondu.
   */
  deliveredAt: string | null;
  deliveredTo: string | null;
  deliveryError: string | null;

  /**
   * L'envoi au client est actif sur ce serveur.
   *
   * Décide d'un seul libellé — « Valider » ou « Valider et envoyer ». Cette
   * distinction n'est pas cosmétique : jusqu'au S6, la plateforme n'avait aucun
   * canal de sortie, et le bouton disait « Valider » parce que promettre un
   * envoi inexistant aurait été un mensonge. Il ne doit pas le devenir dans
   * l'autre sens.
   */
  replyEnabled: boolean;
}
