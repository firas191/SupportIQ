import { TICKET_DETAIL, TICKET_DETAIL_CORRECTED } from '../../fixtures/api';

/**
 * Parcours 3 — la boucle humaine : corriger une analyse fausse (S8-J1).
 *
 * <p>C'est le parcours le plus important a garder vert, parce que c'est la reponse du projet a la
 * question « et quand le modele se trompe ? ». Un exemple non provoque existe : lors de la
 * verification S7, « Probleme de connexion » avait ete classe POS, et la correction en un clic a
 * produit la ligne `annotations(predicted=POS, corrected=NEG)`.
 *
 * <p>Le test verifie les deux moities de cette boucle : l'appel part avec le bon champ et la bonne
 * valeur (c'est lui qui alimentera le futur jeu de re-entrainement), <b>et</b> l'ecran reflete
 * immediatement la correction. Une correction tracee mais non appliquee laisserait l'agent penser
 * que son clic n'a rien fait, et il recommencerait.
 */
describe('Correction d’une analyse', () => {
  beforeEach(() => {
    cy.intercept('GET', '**/api/tickets/10020', { body: TICKET_DETAIL }).as('detail');
    // **Ouvrir une fiche cherche un brouillon existant.** Comportement decouvert par le filet
    // anti-appel-non-bouchonne : le panneau interroge `GET .../draft` au chargement pour ne pas
    // reproposer une generation deja faite. Un 404 est ici la reponse nominale — aucun brouillon
    // n'existe encore — et non une erreur a signaler.
    cy.intercept('GET', '**/api/tickets/10020/draft', { statusCode: 404, body: {} }).as('noDraft');
  });

  it('envoie la correction et met la fiche a jour', () => {
    cy.intercept('POST', '**/api/tickets/10020/annotations', {
      statusCode: 200,
      body: TICKET_DETAIL_CORRECTED,
    }).as('annotate');

    cy.visitAs('/tickets/10020', 'AGENT');
    cy.wait('@detail');

    cy.testid('correct-category-RECLAMATION').click();

    cy.wait('@annotate').then(({ request }) => {
      // Le contrat d'AnnotationRequest : { field, value }. Le verifier ici, c'est verifier que le
      // front et le serveur parlent encore la meme langue — la seule chose qu'une suite bouchonnee
      // puisse honnetement garantir.
      expect(request.body).to.deep.equal({ field: 'category', value: 'RECLAMATION' });
    });

    cy.testid('correct-category-RECLAMATION').should('have.attr', 'aria-pressed', 'true');
  });

  it('affiche les tickets similaires et signale le doublon probable', () => {
    // La liste des similaires vient d'un appel HTTP au service IA, cote serveur, qui **degrade en
    // liste vide** s'il est injoignable (S4-J4). Ici on verifie le cas nominal : quand elle est
    // remplie, le candidat a la fusion doit etre designe comme tel, pas noye dans les autres.
    cy.visitAs('/tickets/10020', 'AGENT');
    cy.wait('@detail');

    cy.contains('Debite deux fois sur la commande 4821').should('be.visible');
  });

  it('reste utilisable quand l’analyse n’existe pas encore', () => {
    // `analysis: null` est le cas d'un ticket qui vient d'arriver. L'ecran doit s'ouvrir malgre
    // tout : un ticket non analyse reste un ticket qu'un agent doit pouvoir lire.
    cy.intercept('GET', '**/api/tickets/10020', {
      body: { ...TICKET_DETAIL, analysis: null, similar: [] },
    }).as('raw');

    cy.visitAs('/tickets/10020', 'AGENT');
    cy.wait('@raw');

    cy.contains('Double debit sur ma commande').should('be.visible');
    cy.testid('correct-category-RECLAMATION').should('not.exist');
  });
});
