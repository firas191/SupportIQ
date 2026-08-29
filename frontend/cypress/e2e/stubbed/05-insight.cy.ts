import { INSIGHT_ANSWER } from '../../fixtures/api';

/**
 * Parcours 5 — agent Insight, et le controle d'acces qui va avec (S8-J1).
 *
 * <p>La propriete la plus importante n'est pas la reponse : c'est que <b>la source lue et la
 * requete executee restent visibles</b>. La mesure du S6-J2 a montre que l'agent repond parfois a
 * une question *voisine* de celle posee, et aucune barriere technique ne detecte cela — montrer ce
 * qui a ete lu, si. C'est la mitigation produit du seul defaut residuel de cet agent, donc elle doit
 * etre testee comme une fonctionnalite et non comme une decoration.
 */
describe('Agent Insight', () => {
  it('repond, et montre ce qui a ete lu', () => {
    cy.intercept('POST', '**/api/insight/questions', {
      statusCode: 200,
      body: INSIGHT_ANSWER,
    }).as('ask');

    cy.visitAs('/insight', 'MANAGER');
    cy.testid('insight-question').type('Combien de tickets par categorie ?');
    cy.get('form.composer button[type=submit]').click();
    cy.wait('@ask');

    cy.testid('insight-answer').should('contain.text', 'facturation');
    // La requete doit rester accessible : c'est ce qui rend visible une substitution de question.
    cy.contains('Voir la requête').should('exist');
  });

  it('distingue un refus legitime d’une panne', () => {
    // 422 « hors perimetre » et 503 « panne » se lisent tres differemment : l'un signifie que la
    // question ne peut pas etre posee ainsi, l'autre qu'il faut reessayer plus tard. Les aplatir
    // ferait passer un refus pour une defaillance — ou l'inverse, ce qui est pire.
    cy.intercept('POST', '**/api/insight/questions', {
      statusCode: 422,
      body: { title: 'Assistant d’analyse', detail: 'Question hors perimetre' },
    }).as('refus');

    cy.visitAs('/insight', 'MANAGER');
    cy.testid('insight-question').type('Donne-moi les adresses des clients');
    cy.get('form.composer button[type=submit]').click();
    cy.wait('@refus');

    cy.testid('insight-answer').should('not.exist');
    cy.get('.composer').should('be.visible'); // on peut reformuler sans recharger
  });

  it('est refuse a un AGENT', () => {
    // Ces vues agregent l'activite de toute l'equipe : un agent y verrait le volume traite par ses
    // collegues. Le RBAC est cote serveur ; le garde de route evite d'afficher un ecran vide.
    //
    // La redirection vers /tickets et non /dashboard : le repli du roleGuard a ete change lors de
    // la refonte parce qu'un AGENT renvoye vers /dashboard, lui aussi reserve MANAGER, bouclait.
    cy.intercept('GET', '**/api/tickets*', { body: { content: [], page: 0, size: 25, totalElements: 0, totalPages: 1, last: true } });

    cy.visitAs('/insight', 'AGENT');
    cy.location('pathname').should('eq', '/tickets');
  });
});
