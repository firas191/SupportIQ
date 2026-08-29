import { IMPORT_CONFIRMED, IMPORT_PREVIEW } from '../../fixtures/api';

/**
 * Parcours 6 — import d’un fichier de tickets (S8-J1).
 *
 * <p>L’import est en <b>deux temps separes par un humain</b> : on depose, on regarde ce que le
 * serveur a compris, puis seulement on confirme. Ce test verifie que la seconde etape ne peut pas
 * etre sautee — c'est la garantie qui evite de creer des milliers de tickets a partir d'un mauvais
 * mapping de colonnes.
 */
describe('Import de tickets', () => {
  beforeEach(() => {
    cy.intercept('POST', '**/api/imports', { statusCode: 200, body: IMPORT_PREVIEW }).as('upload');
  });

  it('depose, montre l’apercu, puis confirme', () => {
    cy.intercept('POST', '**/api/imports/7/confirm', {
      statusCode: 200,
      body: IMPORT_CONFIRMED,
    }).as('confirm');

    cy.visitAs('/imports', 'ADMIN');

    cy.testid('import-file').selectFile(
      {
        contents: Cypress.Buffer.from(
          'reference,email,sujet,message\nCSV-1,a@example.com,Colis perdu,Rien recu\n',
        ),
        fileName: 'tickets.csv',
        mimeType: 'text/csv',
      },
      { force: true }, // le champ natif est masque : c'est le bouton qui porte l'apparence
    );

    cy.wait('@upload');

    // L'apercu doit montrer les **vraies** lignes du fichier. Un apercu decoratif ne permettrait
    // pas de detecter un mapping de colonnes errone, ce qui est sa seule raison d'exister.
    //
    // `scrollIntoView()` avant `be.visible`, et ce n'est pas une formalite.
    //
    // L'apercu se trouve **sous la ligne de flottaison** : l'ecran affiche d'abord l'association des
    // colonnes. Or `should('be.visible')` ne fait <b>pas</b> defiler la page, contrairement aux
    // actions comme `click`, qui amenent leur cible dans le cadre avant d'agir. Une assertion de
    // visibilite sur un element hors cadre echoue donc, alors que l'element est parfaitement rendu.
    //
    // J'ai perdu trois executions a supposer la cause — d'abord un debordement horizontal, puis le
    // tableau entier — avant de regarder la capture d'echec, qui l'a dit en une seconde. Les
    // captures sont conservees exactement pour cela ; s'en passer, c'est theoriser sur une trace
    // partielle.
    cy.contains('td', 'Colis perdu').scrollIntoView().should('be.visible');

    cy.testid('import-confirm').click();
    cy.wait('@confirm');
  });

  it('ne cree rien tant que l’humain n’a pas confirme', () => {
    // Le filet anti-appel-non-bouchonne de cypress/support/e2e.ts fait echouer ce test si le front
    // appelait /confirm de lui-meme : aucun intercepteur ne le declare ici, deliberement.
    cy.visitAs('/imports', 'ADMIN');

    cy.testid('import-file').selectFile(
      {
        contents: Cypress.Buffer.from('reference,email,sujet,message\nCSV-1,a@x.fr,Sujet,Corps\n'),
        fileName: 'tickets.csv',
        mimeType: 'text/csv',
      },
      { force: true },
    );

    cy.wait('@upload');
    cy.testid('import-confirm').should('be.visible');
  });

  it('est refuse a un MANAGER', () => {
    // Importer un fichier de tickets est de l'administration, pas du traitement — contrairement au
    // depot d'un document client (/intake), ouvert AGENT+.
    cy.intercept('GET', '**/api/tickets*', { body: { content: [], page: 0, size: 25, totalElements: 0, totalPages: 1, last: true } });

    cy.visitAs('/imports', 'MANAGER');
    cy.location('pathname').should('eq', '/tickets');
  });
});
