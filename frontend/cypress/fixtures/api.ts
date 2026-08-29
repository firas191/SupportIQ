/**
 * Reponses d'API simulees (S8-J1).
 *
 * <p><b>Un seul endroit, et c'est le point principal de ce fichier.</b> Des fixtures recopiees dans
 * chaque spec divergent silencieusement du serveur : le jour ou un champ est renomme cote Java, la
 * suite reste verte et l'application casse en production. Ici, une divergence de contrat se corrige
 * a un seul endroit — et se voit, puisque tous les parcours echouent d'un coup.
 *
 * <p>Les formes sont <b>relevees sur les enregistrements Java</b> ({@code TicketSummaryResponse},
 * {@code TicketDetailResponse}, {@code PageResponse}, {@code DraftView}, {@code InsightAnswer}), pas
 * inventees. Une fixture inventee testerait un contrat qui n'existe pas.
 *
 * <p><b>Ce que cette suite ne peut pas garantir</b>, et qu'il faut dire tel quel : rien ici ne
 * verifie que le serveur emet vraiment cette forme. C'est le role des tests d'integration Spring, et
 * de la suite de fumee. Ces fixtures verifient que le front sait consommer la forme qu'il **croit**
 * recevoir — ce qui est deja ce qui casse le plus souvent, mais n'est pas la meme chose.
 */

export const TICKET_SUMMARY = {
  id: 10020,
  externalRef: 'WH-DEMO-1',
  source: 'WEBHOOK',
  customerEmail: 'client@example.com',
  subject: 'Double debit sur ma commande',
  excerpt: 'J’ai ete debite deux fois pour la meme commande, merci de regulariser.',
  language: 'fr',
  status: 'NEW',
  slaDueAt: '2030-01-01T10:00:00Z',
  createdAt: '2029-12-31T10:00:00Z',
  priority: 'HIGH',
  category: 'FACTURATION',
  sentiment: 'NEG',
  slaRisk: 0.42,
  slaRiskModel: 'rules',
  slaRiskAt: '2029-12-31T11:00:00Z',
};

/** Ticket non analyse : la liste doit afficher « en attente » et non un vide ambigu. */
export const TICKET_SUMMARY_UNANALYSED = {
  ...TICKET_SUMMARY,
  id: 10021,
  externalRef: 'WH-DEMO-2',
  subject: 'Question sur ma facture',
  priority: null,
  category: null,
  sentiment: null,
  slaRisk: null,
  slaRiskModel: null,
  slaRiskAt: null,
};

export function page(content: unknown[], total = content.length) {
  return {
    content,
    page: 0,
    size: 25,
    totalElements: total,
    totalPages: Math.max(1, Math.ceil(total / 25)),
    last: total <= 25,
  };
}

export const TICKET_DETAIL = {
  id: 10020,
  externalRef: 'WH-DEMO-1',
  source: 'WEBHOOK',
  customerEmail: 'client@example.com',
  subject: 'Double debit sur ma commande',
  body: 'Bonjour, j’ai ete debite deux fois pour la commande 4821. Merci de regulariser.',
  language: 'fr',
  status: 'NEW',
  slaDueAt: '2030-01-01T10:00:00Z',
  createdAt: '2029-12-31T10:00:00Z',
  mergedIntoId: null,
  analysis: {
    priority: 'HIGH',
    category: 'FACTURATION',
    sentiment: 'NEG',
    keywords: ['double debit', 'commande', 'remboursement'],
    confidence: 0.87,
    modelUsed: 'xlm-r-onnx',
    escalatedToLlm: false,
    createdAt: '2029-12-31T10:05:00Z',
  },
  similar: [
    {
      ticketId: 10021,
      subject: 'Debite deux fois sur la commande 4821',
      category: 'FACTURATION',
      similarity: 0.9806,
      duplicate: true,
    },
  ],
};

/** Le meme ticket apres correction de la categorie : c'est la reponse de POST /annotations. */
export const TICKET_DETAIL_CORRECTED = {
  ...TICKET_DETAIL,
  analysis: { ...TICKET_DETAIL.analysis, category: 'RECLAMATION' },
};

export const AUTH_TOKENS = {
  // Corps volontairement decodable : le front lit `sub` et `role` sans verifier la signature.
  accessToken:
    'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.' +
    btoa(
      JSON.stringify({
        sub: 'admin@supportiq.local',
        role: 'ADMIN',
        exp: Math.floor(Date.now() / 1000) + 3600,
      }),
    )
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/, '') +
    '.signature-non-verifiee-cote-front',
  refreshToken: 'refresh-de-test',
};

/**
 * Apercu d'import — forme relevee sur {@code ImportPreviewResponse}.
 *
 * <p>Ma premiere version de cette fixture etait **inventee** : `fileName` au lieu de `filename`,
 * `columns` au lieu de `headers`, et un apercu en liste d'objets la ou le serveur renvoie une liste
 * de listes. Le test echouait donc pour la bonne raison — il testait un contrat qui n'existe pas.
 * C'est precisement ce qu'une fixture centralisee doit rendre impossible d'oublier.
 */
export const IMPORT_PREVIEW = {
  importId: 7,
  filename: 'tickets.csv',
  fileType: 'CSV',
  charset: 'UTF-8',
  status: 'AWAITING_VALIDATION',
  totalRows: 3,
  errorCount: 0,
  headers: ['reference', 'email', 'sujet', 'message'],
  preview: [
    ['CSV-1', 'a@example.com', 'Colis perdu', 'Rien recu'],
    ['CSV-2', 'b@example.com', 'Remboursement', 'Toujours rien'],
    ['CSV-3', 'c@example.com', 'Mot de passe', 'Impossible'],
  ],
  errors: [],
};

/** Tableau de bord — le minimum pour que l'ecran s'affiche sans donnee manquante. */
export const KPIS = {
  totalTickets: 120,
  newTickets: 30,
  resolvedTickets: 80,
  analyzedTickets: 100,
  highPriority: 12,
  negativeSentiment: 25,
  escalatedToLlm: 46,
  highPriorityRate: 12,
  negativeRate: 25,
  escalationRate: 46,
  avgConfidence: 0.87,
};

export const TRENDS = {
  daily: [{ day: '2029-12-30', category: 'FACTURATION', count: 8 }],
  byCategory: [
    { label: 'FACTURATION', count: 41 },
    { label: 'TECHNIQUE', count: 22 },
  ],
  bySentiment: [{ label: 'NEG', count: 25 }],
  byPriority: [{ label: 'HIGH', count: 12 }],
  hourly: [{ hour: 9, count: 14 }],
};

export const IMPORT_CONFIRMED = { importId: 7, inserted: 3, skipped: 0, status: 'DONE' };

export const DRAFT = {
  id: 1,
  ticketId: 10020,
  content:
    'Je comprends votre frustration. Le double debit est rembourse sous 7 jours ouvres [1].',
  finalContent: null,
  citations: [
    {
      marker: 1,
      chunkId: 67,
      source: 'faq-facturation.md',
      heading: 'Facturation et paiements > Double debit',
      content:
        'En cas de double debit, le remboursement est automatique sous 7 jours ouvres apres constat.',
      stale: false,
    },
  ],
  status: 'PROPOSED',
  tone: 'empathetic',
  lowConfidence: false,
  abstained: false,
  issues: [],
  attempts: 1,
  createdAt: '2029-12-31T10:10:00Z',
  reviewedAt: null,
  reviewedBy: null,
  deliveredAt: null,
  deliveredTo: null,
  deliveryError: null,
  replyEnabled: false,
};

/**
 * Abstention : le modele a reconnu que la documentation ne couvre pas la demande.
 *
 * <p>C'est un **resultat correct**, pas un echec — d'ou `issues: []` et `lowConfidence: false`.
 * L'interface doit dire « rien a proposer » sur un ton neutre et masquer la validation : proposer
 * de valider un texte qui dit « je n'ai pas trouve » reviendrait a proposer de l'envoyer au client.
 */
export const DRAFT_ABSTAINED = {
  ...DRAFT,
  id: 2,
  content: '',
  citations: [],
  abstained: true,
};

export const INSIGHT_ANSWER = {
  question: 'Combien de tickets par categorie ?',
  sql: 'SELECT category, COUNT(*) AS nb_tickets FROM v_daily_volume GROUP BY category',
  answer: 'La facturation domine avec 41 tickets, devant la technique.',
  columns: ['category', 'nb_tickets'],
  rows: [
    ['FACTURATION', 41],
    ['TECHNIQUE', 22],
  ],
  rowCount: 2,
  attempts: 1,
  truncated: false,
  chart: { type: 'bar', x: 'category', y: 'nb_tickets', reason: null },
};
