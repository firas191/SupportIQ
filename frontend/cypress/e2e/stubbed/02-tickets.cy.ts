import { TICKET_SUMMARY, TICKET_SUMMARY_UNANALYSED, page } from '../../fixtures/api';

/**
 * Parcours 2 — la file de tickets, ecran ou un agent passe sa journee (S8-J1).
 *
 * <p>Les assertions portent sur trois proprietes qui ont chacune coute un defaut reel :
 * l'affichage d'un ticket <b>non analyse</b> (il doit rester visible et se declarer en attente,
 * pas disparaitre), le passage des filtres <b>dans l'URL</b> (une recherche doit etre partageable),
 * et l'etat <b>vide</b> distingue de l'etat <b>en erreur</b>.
 */
describe('File de tickets', () => {
  it('affiche les tickets, analyses ou non', () => {
    cy.intercept('GET', '**/api/tickets*', {
      body: page([TICKET_SUMMARY, TICKET_SUMMARY_UNANALYSED], 2),
    }).as('list');

    cy.visitAs('/tickets', 'AGENT');
    cy.wait('@list');

    cy.testid('ticket-row').should('have.length', 2);
    // Un ticket sans analyse **reste dans la liste**. Le faire disparaitre serait le pire
    // comportement possible : la file omettrait silencieusement les tickets les plus recents,
    // c'est-a-dire ceux qui comptent le plus.
    cy.testid('ticket-row').eq(1).should('contain.text', 'Question sur ma facture');
  });

  it('ecrit la recherche dans l’URL pour qu’elle soit partageable', () => {
    cy.intercept('GET', '**/api/tickets*', { body: page([TICKET_SUMMARY]) }).as('list');

    cy.visitAs('/tickets', 'AGENT');
    cy.wait('@list');

    cy.get('input[type=search]').type('remboursement');

    // La recherche est debouncee : on attend la requete plutot que de temporiser en dur. Un
    // `cy.wait(400)` passerait aujourd'hui et casserait le jour ou le delai change.
    cy.wait('@list').its('request.url').should('include', 'q=remboursement');
    cy.location('search').should('include', 'q=remboursement');
  });

  it('ouvre la fiche au clic sur une ligne', () => {
    cy.intercept('GET', '**/api/tickets?*', { body: page([TICKET_SUMMARY]) }).as('list');
    cy.intercept('GET', '**/api/tickets/10020', { body: { id: 10020, subject: 'x', similar: [] } });
    // La fiche cherche aussi un brouillon existant a l'ouverture : sans ce bouchon, le test
    // dependrait de la vitesse a laquelle l'assertion d'URL se resout avant que la requete ne
    // parte. Une course gagnee par hasard n'est pas un test qui passe.
    cy.intercept('GET', '**/api/tickets/10020/draft', { statusCode: 404, body: {} });

    cy.visitAs('/tickets', 'AGENT');
    cy.wait('@list');
    cy.testid('ticket-row').first().click();

    cy.location('pathname').should('eq', '/tickets/10020');
  });

  it('distingue « aucun resultat » de « le chargement a echoue »', () => {
    // Les deux affichent un ecran sans donnees, et les confondre est une faute d'interface
    // courante : l'un invite a elargir la recherche, l'autre a reessayer. Le test verifie qu'un
    // 500 ne se lit pas comme une file vide.
    //
    // Le libelle est ecrit **avec son accent** (« Réessayer »). Ma premiere version cherchait
    // `/reessayer/i` et ne trouvait rien : une expression reguliere insensible a la casse ne l'est
    // pas aux diacritiques. La langue est figee en francais par `visitAs`, donc viser la chaine
    // exacte est ici plus honnete qu'un motif approximatif.
    cy.intercept('GET', '**/api/tickets*', { body: page([], 0) }).as('empty');
    cy.visitAs('/tickets', 'AGENT');
    cy.wait('@empty');
    cy.testid('ticket-row').should('not.exist');
    cy.contains('button', /Réessayer/).should('not.exist');

    cy.intercept('GET', '**/api/tickets*', { statusCode: 500, body: {} }).as('boom');
    cy.reload();
    cy.wait('@boom');
    cy.contains('button', /Réessayer/).should('be.visible');
  });
});
