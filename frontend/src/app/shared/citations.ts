/**
 * Decoupage d'un brouillon en texte et marqueurs de citation (S5-J4).
 *
 * Le modele ecrit ses sources sous la forme `[1]`, `[2]`. Pour les rendre
 * cliquables il faut couper le texte autour de ces marqueurs.
 *
 * **Pourquoi une fonction pure et pas un `innerHTML`.** La solution courte
 * serait `content.replace(/\[(\d+)\]/g, '<button>…</button>')` puis
 * `[innerHTML]`. Elle ouvrirait une injection : le brouillon est produit par un
 * modele a partir du **corps du ticket**, qui vient du client. Un client qui
 * ecrit `<img onerror=…>` dans sa demande obtiendrait son script execute dans le
 * navigateur de l'agent. Angular assainit `[innerHTML]`, mais s'appuyer sur
 * l'assainissement quand on peut simplement ne pas produire de HTML est un pari
 * inutile.
 *
 * Ici on produit des **donnees**, le gabarit produit des **noeuds**. Il n'y a
 * jamais de chaine interpretee comme du balisage.
 *
 * Second benefice : la fonction est testable sans Angular, sans DOM et sans pile
 * d'inference — comme les briques deterministes de l'agent cote Python.
 */

export type DraftSegment =
  | { readonly kind: 'text'; readonly text: string }
  | { readonly kind: 'marker'; readonly marker: number };

/** Meme forme que `_MARKER` cote agent : un a deux chiffres entre crochets. */
const MARKER = /\[(\d{1,2})\]/g;

/**
 * Coupe `text` autour des marqueurs.
 *
 * @param known marqueurs pour lesquels une source existe reellement. Un
 *   marqueur inconnu reste **du texte** : le rendre cliquable promettrait une
 *   source qu'on ne peut pas montrer, ce qui est pire que de ne rien promettre.
 *   Le cas se produit apres une auto-verification degradee, ou si le modele
 *   invente un numero.
 */
export function splitCitations(text: string, known: ReadonlySet<number>): DraftSegment[] {
  const segments: DraftSegment[] = [];
  let cursor = 0;

  // `lastIndex` est un etat porte par l'objet regex : on le remet a zero, sinon
  // un second appel repartirait du milieu du texte precedent.
  MARKER.lastIndex = 0;

  for (let match = MARKER.exec(text); match !== null; match = MARKER.exec(text)) {
    const marker = Number(match[1]);
    if (!known.has(marker)) {
      continue;
    }
    if (match.index > cursor) {
      segments.push({ kind: 'text', text: text.slice(cursor, match.index) });
    }
    segments.push({ kind: 'marker', marker });
    cursor = match.index + match[0].length;
  }

  if (cursor < text.length) {
    segments.push({ kind: 'text', text: text.slice(cursor) });
  }
  return segments;
}
