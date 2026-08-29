import { defineConfig } from 'cypress';

/**
 * Tests de bout en bout (S8-J1).
 *
 * DEUX SUITES, DELIBEREMENT SEPAREES
 *
 * `cypress/e2e/stubbed/` — les six parcours critiques, avec toutes les reponses d'API simulees par
 * `cy.intercept`. Aucun backend, aucune base, aucun appel de modele : la suite est **deterministe**
 * et tourne en CI en deux minutes. Elle verifie ce dont l'interface est reellement responsable —
 * navigation, garde de roles, etats de chargement, d'erreur et de vide, et la forme des donnees
 * qu'elle sait consommer.
 *
 * `cypress/e2e/smoke/` — un parcours court contre la **pile reelle**, lance a la main avant une
 * demonstration. Il ne verifie pas ce que le modele repond (ce serait instable par construction),
 * mais que la chaine complete repond.
 *
 * POURQUOI LES SEPARER PLUTOT QUE TOUT LANCER CONTRE LE VRAI
 *
 * Deux des six parcours — brouillon de reponse et agent Insight — passent par un LLM. Leur sortie
 * varie d'un appel a l'autre (constate au S6-J2 : onze verdicts sur trente basculaient entre deux
 * executions, avant qu'on ne fixe la temperature). Une suite qui en dependrait serait rouge un jour
 * sur trois sans qu'aucun defaut n'existe — et *un rouge qui n'indique aucun defaut apprend a
 * ignorer les rouges*.
 *
 * C'est le meme partage que partout ailleurs dans ce projet : les parties deterministes entrent en
 * CI, les campagnes qui dependent d'un fournisseur externe se lancent a la main (S5-J5, S6-J2).
 */
export default defineConfig({
  e2e: {
    baseUrl: process.env['CYPRESS_BASE_URL'] ?? 'http://localhost:4200',

    // Le motif par defaut prendrait les deux suites. On veut l'inverse : `npm run e2e` ne lance
    // que le deterministe, et la suite de fumee demande un choix explicite (`npm run e2e:smoke`).
    specPattern: 'cypress/e2e/stubbed/**/*.cy.ts',
    supportFile: 'cypress/support/e2e.ts',

    // Aucune capture video : sur cette suite elles ne servent qu'a alourdir les artefacts de CI.
    // Les captures d'ecran d'echec, elles, sont gardees — c'est la seule trace utile d'un test
    // rouge en integration continue.
    video: false,
    screenshotOnRunFailure: true,

    viewportWidth: 1440,
    viewportHeight: 900,

    // Delais serres **a dessein** : rien n'est reellement asynchrone dans la suite bouchonnee, donc
    // une attente longue ne masquerait qu'un defaut. Un test qui met huit secondes a echouer est un
    // test qu'on finit par ne plus lancer.
    defaultCommandTimeout: 6000,
    requestTimeout: 6000,
    responseTimeout: 6000,

    retries: { runMode: 1, openMode: 0 },
  },
});
