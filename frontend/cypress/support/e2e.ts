import './commands';

/**
 * Reglages communs aux specs (S8-J1).
 */

/**
 * **Tout appel d'API non bouchonne fait echouer le test, immediatement et explicitement.**
 *
 * <p>Sans ce filet, une requete oubliee part vers le proxy de `ng serve`, qui tente de joindre le
 * backend sur le port 8080. Deux issues, toutes deux mauvaises : soit le backend tourne sur le poste
 * et le test passe **pour une raison qu'on n'a pas ecrite** — c'est exactement le defaut trouve la
 * veille dans `AnalysisRecoveryIntegrationTest`, dont le verdict dependait d'un courtier qui se
 * trouvait la ; soit il ne tourne pas, et le test echoue six secondes plus tard sur un delai
 * expire, message qui ne dit pas ou est le probleme.
 *
 * <p>Ici, l'echec nomme la requete manquante. Une suite bouchonnee doit declarer **tout** ce qu'elle
 * attend du serveur : c'est aussi ce qui en fait, accessoirement, une documentation exacte du
 * contrat que le front consomme.
 *
 * <p>Il repose sur une regle de Cypress qu'il faut connaitre pour lire ce fichier : les
 * intercepteurs sont evalues <b>du plus recent au plus ancien</b>. Declare ici, dans un
 * `beforeEach`, celui-ci est donc systematiquement supplante par les intercepteurs specifiques que
 * chaque test declare ensuite — et ne recoit que ce que personne n'a reclame.
 */
beforeEach(() => {
  if (Cypress.spec.relative.includes('smoke')) {
    return; // la suite de fumee parle au vrai serveur, par construction
  }
  cy.intercept('**/api/**', (req) => {
    throw new Error(
      `Appel d'API non bouchonne : ${req.method} ${req.url}\n` +
        `Ajoutez un cy.intercept pour cette route dans le test, ou retirez l'action qui la declenche.`,
    );
  });
});

/**
 * Les erreurs non rattrapees de l'application **ne sont pas ignorees**.
 *
 * <p>Cypress propose de les taire (`uncaught:exception` renvoyant `false`), et c'est la premiere
 * chose que l'on desactive quand une suite devient penible. On garde le comportement par defaut :
 * une exception dans la console pendant un parcours critique est un defaut, meme si l'ecran a l'air
 * correct. C'est d'ailleurs par une console silencieuse qu'on aurait pu passer a cote du
 * `(ngSubmit)` sans directive du S5-J1 — un ecouteur DOM inexistant, aucune erreur, et un
 * formulaire qui rechargeait la page.
 */
