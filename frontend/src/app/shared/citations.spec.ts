import { splitCitations } from './citations';

/**
 * Le decoupage des marqueurs est la seule logique **non triviale** du panneau
 * brouillon, et la seule qui se teste sans navigateur : c'est une fonction pure.
 *
 * Ce qui est verifie ici n'est pas « ca marche » mais les trois manieres dont ca
 * pourrait silencieusement mal marcher : perdre du texte, promettre une source
 * inexistante, ou dependre de l'appel precedent.
 */
describe('splitCitations', () => {
  const kinds = (text: string, known: number[]) =>
    splitCitations(text, new Set(known)).map((s) =>
      s.kind === 'marker' ? `marker${s.marker}` : 'text',
    );

  it('coupe le texte autour des marqueurs connus', () => {
    expect(kinds('Bonjour [1] merci.', [1])).toEqual(['text', 'marker1', 'text']);
    expect(kinds('A [1] B [2].', [1, 2])).toEqual([
      'text',
      'marker1',
      'text',
      'marker2',
      'text',
    ]);
  });

  it('gere les marqueurs en tete et en fin de texte', () => {
    expect(kinds('[1] Debut.', [1])).toEqual(['marker1', 'text']);
    expect(kinds('Fin [2]', [2])).toEqual(['text', 'marker2']);
  });

  it('laisse en texte un marqueur sans source', () => {
    // Le rendre cliquable promettrait une source qu'on ne peut pas montrer.
    expect(kinds('A [7] B.', [1])).toEqual(['text']);
  });

  it('ignore les crochets non numeriques', () => {
    expect(kinds('voir [note] ici', [1])).toEqual(['text']);
  });

  it('ne perd aucun caractere', () => {
    const source = 'Debut [1] milieu [2] fin.';
    const rebuilt = splitCitations(source, new Set([1, 2]))
      .map((s) => (s.kind === 'marker' ? `[${s.marker}]` : s.text))
      .join('');
    expect(rebuilt).toBe(source);
  });

  it('donne le meme resultat a chaque appel', () => {
    // `lastIndex` est un etat porte par l'objet regex : sans remise a zero, le
    // second appel repartirait du milieu du texte precedent.
    const first = kinds('X [1] Y', [1]);
    expect(kinds('X [1] Y', [1])).toEqual(first);
  });
});
