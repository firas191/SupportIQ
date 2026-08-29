/// <reference types="cypress" />

/**
 * Commandes partagees par les deux suites (S8-J1).
 */

export type TestRole = 'AGENT' | 'MANAGER' | 'ADMIN';

/**
 * Fabrique un jeton d'acces accepte par le front.
 *
 * <p>Le front **ne verifie pas la signature** : il decode le corps du JWT pour en tirer `sub` et
 * `role` (`AuthService.decodeUser`). La verification est faite par le serveur a chaque appel, ce qui
 * est le bon endroit — un front qui validerait lui-meme une signature se donnerait une garantie
 * qu'il ne peut pas tenir.
 *
 * <p>Consequence pratique : un jeton fabrique ici suffit a placer l'application dans l'etat
 * « connecte en tant que X », sans passer par le formulaire ni par le serveur. C'est ce qui permet
 * aux cinq autres parcours de commencer la ou ils ont quelque chose a prouver, au lieu de rejouer
 * une connexion deja couverte par son propre test.
 */
function fakeJwt(email: string, role: TestRole): string {
  const encode = (o: unknown) =>
    btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const header = encode({ alg: 'HS256', typ: 'JWT' });
  // Expiration lointaine : un jeton perime rendrait la suite dependante de l'heure a laquelle on la
  // lance, ce qui est la definition d'un test instable.
  const payload = encode({ sub: email, role, exp: Math.floor(Date.now() / 1000) + 3600 });
  return `${header}.${payload}.signature-non-verifiee-cote-front`;
}

/**
 * Place l'application dans l'etat connecte, **avant le chargement de la page**.
 *
 * <p>`cy.visit` avec `onBeforeLoad` et non un `cy.window()` apres coup : Angular lit le jeton au
 * moment ou `AuthService` est construit, donc l'ecrire apres l'ouverture de la page arriverait trop
 * tard et le garde de route aurait deja redirige vers /login.
 */
Cypress.Commands.add('visitAs', (path: string, role: TestRole = 'ADMIN') => {
  const email = `${role.toLowerCase()}@supportiq.local`;
  cy.visit(path, {
    onBeforeLoad(win) {
      win.localStorage.setItem('supportiq.accessToken', fakeJwt(email, role));
      win.localStorage.setItem('supportiq.refreshToken', 'refresh-de-test');
      // Langue **figee**. L'interface est bilingue et retombe sinon sur `navigator.language`, donc
      // la meme suite lirait des libelles differents selon le poste ou la CI. Les assertions de
      // texte deviennent alors reproductibles.
      win.localStorage.setItem('supportiq.lang', 'fr');
      // Theme fige pour la meme raison : les captures d'ecran d'echec sont comparables.
      win.localStorage.setItem('supportiq.theme', 'light');
    },
  });
});

/** Raccourci de lecture : `cy.testid('ticket-row')`. */
Cypress.Commands.add('testid', (id: string) => cy.get(`[data-testid="${id}"]`));

declare global {
  // eslint-disable-next-line @typescript-eslint/no-namespace
  namespace Cypress {
    interface Chainable {
      visitAs(path: string, role?: TestRole): Chainable<void>;
      testid(id: string): Chainable<JQuery<HTMLElement>>;
    }
  }
}

export {};
