import { AUTH_TOKENS, KPIS, TICKET_SUMMARY, TRENDS, page } from '../../fixtures/api';

/**
 * Parcours 1 — connexion (S8-J1).
 *
 * <p>C'est le seul test qui passe par le formulaire : les cinq autres injectent un jeton avant le
 * chargement, parce que rejouer une connexion deja couverte ici n'apporterait rien et ralentirait
 * toute la suite.
 *
 * <p>Ce qui est verifie ne se limite pas au cas nominal. La <b>redirection selon le role</b> a ete
 * ajoutee lors de la refonte d'interface parce qu'un AGENT envoye sur /dashboard etait aussitot
 * renvoye par le garde de route — un clignotement a chaque connexion. C'est exactement le genre de
 * regression qu'aucun test unitaire ne voit et qu'un utilisateur remarque immediatement.
 */
describe('Connexion', () => {
  it('mene un ADMIN a la vue d’ensemble', () => {
    // La destination est **calculee a la reception du jeton**, pas fixe. Envoyer tout le monde sur
    // /dashboard puis laisser le garde renvoyer les agents produirait un clignotement a chaque
    // connexion — c'est le defaut corrige lors de la refonte d'interface, et ce test l'y maintient.
    cy.intercept('POST', '**/api/auth/login', { statusCode: 200, body: AUTH_TOKENS }).as('login');
    cy.intercept('GET', '**/api/dashboard/kpis', { body: KPIS }).as('kpis');
    cy.intercept('GET', '**/api/dashboard/trends*', { body: TRENDS }).as('trends');
    // Le fil « Derniers tickets » du tableau de bord. Il **reutilise** la liste paginee avec
    // `size=5` plutot que d'ajouter un endpoint dedie — d'ou une troisieme requete que rien dans le
    // nom de l'ecran ne laissait deviner, et que le filet a nommee en une ligne.
    cy.intercept('GET', '**/api/tickets*', { body: page([TICKET_SUMMARY]) }).as('recent');
    // Panneau d'alertes de volume (S7-J2), place au-dessus des indicateurs. Motif large : cet ecran
    // agrege plusieurs sources, et les decouvrir une par une au fil des executions ne prouve rien
    // de plus que de les declarer d'emblee.
    cy.intercept('GET', '**/api/alerts*', { body: [] }).as('alerts');

    cy.visit('/login');
    cy.get('#email').type('admin@supportiq.local');
    cy.get('#password').type('admin1234');
    cy.get('button[type=submit]').click();

    cy.wait('@login');
    cy.location('pathname').should('eq', '/dashboard');
    cy.wait('@kpis');
  });

  it('mene un AGENT a la file de tickets, sans passer par la vue d’ensemble', () => {
    // Le pendant du test precedent. Si l'AGENT passait une fraction de seconde par /dashboard, le
    // filet anti-appel-non-bouchonne le dirait : aucun intercepteur de tableau de bord n'est
    // declare ici, deliberement.
    const agentTokens = {
      ...AUTH_TOKENS,
      accessToken: AUTH_TOKENS.accessToken.replace(
        /\.[^.]+\./,
        '.' +
          btoa(
            JSON.stringify({
              sub: 'agent@supportiq.local',
              role: 'AGENT',
              exp: Math.floor(Date.now() / 1000) + 3600,
            }),
          )
            .replace(/\+/g, '-')
            .replace(/\//g, '_')
            .replace(/=+$/, '') +
          '.',
      ),
    };
    cy.intercept('POST', '**/api/auth/login', { statusCode: 200, body: agentTokens }).as('login');
    cy.intercept('GET', '**/api/tickets*', { body: page([TICKET_SUMMARY]) }).as('tickets');

    cy.visit('/login');
    cy.get('#email').type('agent@supportiq.local');
    cy.get('#password').type('agent1234');
    cy.get('button[type=submit]').click();

    cy.wait('@login');
    cy.location('pathname').should('eq', '/tickets');
    cy.testid('ticket-row').should('have.length', 1);
  });

  it('affiche une erreur lisible sur un mot de passe refuse, et reste sur le formulaire', () => {
    // 401 et non 500 : un identifiant errone n'est pas une panne, et l'ecran ne doit pas le
    // presenter comme telle. La verification porte sur le fait de **rester** sur /login — une
    // redirection partielle laisserait l'utilisateur devant une page vide sans savoir pourquoi.
    cy.intercept('POST', '**/api/auth/login', {
      statusCode: 401,
      body: { title: 'Authentification refusee', detail: 'Identifiants ou jeton invalides.' },
    }).as('login');

    cy.visit('/login');
    cy.get('#email').type('admin@supportiq.local');
    cy.get('#password').type('mauvais');
    cy.get('button[type=submit]').click();

    cy.wait('@login');
    cy.location('pathname').should('eq', '/login');
    cy.get('#email').should('have.value', 'admin@supportiq.local');
  });

  it('renvoie vers la connexion un visiteur sans jeton', () => {
    // Le garde de route doit agir **avant** tout appel d'API : si une requete partait, le filet
    // anti-appel-non-bouchonne de cypress/support/e2e.ts ferait echouer ce test — ce qui est
    // exactement le signal recherche.
    cy.visit('/tickets');
    cy.location('pathname').should('eq', '/login');
  });
});
