import { DRAFT, DRAFT_ABSTAINED, TICKET_DETAIL } from '../../fixtures/api';

/**
 * Parcours 4 — brouillon de reponse et boucle de validation (S8-J1).
 *
 * <p>Ce que ce test <b>ne</b> verifie pas : la qualite du texte genere. Elle depend d'un LLM, elle
 * varie d'un appel a l'autre, et elle est mesuree ailleurs — par le juge du S5-J5, hors ligne, sur
 * un echantillon stratifie. L'y verifier ici rendrait la suite rouge un jour sur trois sans qu'aucun
 * defaut n'existe.
 *
 * <p>Ce qui est verifie, c'est ce dont l'interface repond : les citations sont cliquables et
 * ouvrent leur source en place, et surtout <b>une abstention ne se presente pas comme un incident</b>.
 */
describe('Brouillon de reponse', () => {
  beforeEach(() => {
    cy.intercept('GET', '**/api/tickets/10020', { body: TICKET_DETAIL }).as('detail');
    // Le panneau interroge un brouillon existant a l'ouverture de la fiche. 404 = il n'y en a pas
    // encore, ce qui est l'etat de depart de tous les tests de ce fichier.
    cy.intercept('GET', '**/api/tickets/10020/draft', { statusCode: 404, body: {} }).as('noDraft');
  });

  it('genere un brouillon et rend ses sources consultables', () => {
    cy.intercept('POST', '**/api/tickets/10020/draft', { statusCode: 200, body: DRAFT }).as('gen');

    cy.visitAs('/tickets/10020', 'AGENT');
    cy.wait('@detail');
    cy.testid('draft-generate').click();
    cy.wait('@gen');

    cy.testid('draft-text').should('contain.text', '7 jours ouvres');

    // La citation ouvre le passage **en place**. Un lien vers l'ecran de base de connaissances
    // serait refuse a un AGENT (reserve ADMIN), et verifier une source ne doit pas couter de
    // quitter ce qu'on est en train de lire.
    cy.testid('draft-text').find('button.cite').first().click();
    cy.contains('Facturation et paiements > Double debit').should('be.visible');
  });

  it('presente une abstention comme un resultat, pas comme une alerte', () => {
    cy.intercept('POST', '**/api/tickets/10020/draft', {
      statusCode: 200,
      body: DRAFT_ABSTAINED,
    }).as('gen');

    cy.visitAs('/tickets/10020', 'AGENT');
    cy.wait('@detail');
    cy.testid('draft-generate').click();
    cy.wait('@gen');

    // Aucun bandeau d'avertissement : colorer une abstention en alerte apprendrait a l'agent que
    // « pas de reponse » est un incident, et il finirait par ignorer les vraies alertes.
    cy.get('.banner--warning').should('not.exist');
    // Et surtout, rien a valider : valider une abstention reviendrait a envoyer au client un texte
    // disant qu'on n'a pas trouve. Le serveur le refuse en 409, l'interface ne le propose pas.
    cy.contains('button', /^Valider/).should('not.exist');
  });

  it('signale une panne de generation sans casser la fiche', () => {
    cy.intercept('POST', '**/api/tickets/10020/draft', { statusCode: 503, body: {} }).as('down');

    cy.visitAs('/tickets/10020', 'AGENT');
    cy.wait('@detail');
    cy.testid('draft-generate').click();
    cy.wait('@down');

    // Le ticket reste lisible : la generation est un service en plus, pas une condition d'affichage.
    cy.contains('Double debit sur ma commande').should('be.visible');
    cy.testid('draft-generate').should('not.be.disabled');
  });
});
