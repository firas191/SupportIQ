/**
 * Suite de fumee — contre la **pile reelle** (S8-J1).
 *
 *   docker compose up -d
 *   npm start                # dans un autre terminal
 *   npm run e2e:smoke
 *
 * <p>Elle n'entre <b>pas</b> en CI, et ce n'est pas une facilite : elle a besoin de PostgreSQL, de
 * RabbitMQ, du backend, du service IA et de donnees en base. Une suite qui exige tout cela serait
 * rouge pour dix raisons sans rapport avec le code, et *un rouge qui n'indique aucun defaut apprend
 * a ignorer les rouges*.
 *
 * <p>Son role est different : verifier avant une demonstration que la chaine complete repond. Elle
 * s'arrete deliberement la ou commence le non-deterministe — elle ne juge aucune sortie de modele,
 * seulement que les donnees traversent.
 */
describe('Pile reelle', () => {
  const email = Cypress.env('ADMIN_EMAIL') ?? 'admin@supportiq.local';
  const password = Cypress.env('ADMIN_PASSWORD') ?? 'admin1234';

  it('connexion, file, fiche — avec de vraies donnees', () => {
    cy.visit('/login');
    cy.get('#email').type(email);
    cy.get('#password').type(password, { log: false });
    cy.get('button[type=submit]').click();

    cy.location('pathname', { timeout: 15000 }).should('eq', '/tickets');

    // Au moins un ticket : si la base est vide, ce test n'a rien prouve, et il doit le dire
    // plutot que de passer au vert sur une liste vide.
    cy.get('[data-testid="ticket-row"]', { timeout: 15000 }).should('have.length.greaterThan', 0);

    cy.get('[data-testid="ticket-row"]').first().click();
    cy.location('pathname').should('match', /^\/tickets\/\d+$/);

    // La fiche a bien charge des donnees venues de la base, et pas seulement une coquille.
    cy.get('h1, h2').first().should('not.be.empty');
  });

  it('l’API de sante du service IA repond', () => {
    // Appel direct, hors interface : c'est le seul moyen de distinguer « le front n'affiche rien »
    // de « le plan de calcul est absent ». Les deux se ressemblent a l'ecran.
    cy.request({ url: 'http://localhost:8001/health/ready', failOnStatusCode: false }).then((res) => {
      expect(res.status, 'service IA joignable').to.eq(200);
      expect(res.body.database, 'base atteignable depuis le service IA').to.eq('up');
    });
  });
});
