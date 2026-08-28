// Charge sur la file de tickets — l'ecran ou un agent passe sa journee (S7-J5, rapport §9).
//
//   k6 run -e BASE=http://localhost:8080 -e EMAIL=... -e PASSWORD=... perf/k6/search.js
//
// ----------------------------------------------------------------------------
// Ce qui est mesure, et pourquoi ces requetes-la
// ----------------------------------------------------------------------------
//
// Quatre profils, choisis parce qu'ils sollicitent des chemins d'execution **differents** et non
// parce qu'ils sont nombreux :
//
//   1. `list`      — aucun filtre, tri par date. Le chemin par defaut, celui qui s'ouvre au
//                    lancement de l'application. Index sur `created_at`.
//   2. `filtered`  — onglet de statut + filtre de categorie. Passe par la jointure `analyses`.
//   3. `fulltext`  — recherche libre. Index GIN + `ts_rank` : tri par pertinence, pas par colonne.
//   4. `deepPage`  — page lointaine. **Le cas volontairement defavorable** : `OFFSET` oblige
//                    PostgreSQL a parcourir puis jeter toutes les lignes precedentes, et c'est la
//                    limite connue de la pagination par offset. On le mesure pour savoir ou elle
//                    fait mal, pas pour la faire passer.
//
// Le jeton est obtenu **une fois** dans `setup()` et partage : mesurer le login a chaque iteration
// mesurerait BCrypt (cout 12, deliberement lent) au lieu de la recherche.

import http from 'k6/http';
import { check, group } from 'k6';
import { Trend } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://localhost:8080';
const EMAIL = __ENV.EMAIL || 'admin@supportiq.local';
const PASSWORD = __ENV.PASSWORD || 'firas';

const listTrend = new Trend('list_duration', true);
const filteredTrend = new Trend('filtered_duration', true);
const fulltextTrend = new Trend('fulltext_duration', true);
const deepPageTrend = new Trend('deep_page_duration', true);

export const options = {
  scenarios: {
    steady: {
      executor: 'ramping-vus',
      startVUs: 1,
      // Montee progressive : un palier brutal mesure surtout le remplissage du pool de connexions
      // et le rechauffement du cache de plans, pas le regime etabli.
      stages: [
        { duration: '30s', target: 10 },
        { duration: '2m', target: 10 },
        { duration: '30s', target: 0 },
      ],
    },
  },
  thresholds: {
    // Objectif du rapport §9 : P95 < 300 ms sur les endpoints critiques.
    'http_req_duration{expected_response:true}': ['p(95)<300'],
    'list_duration': ['p(95)<300'],
    'filtered_duration': ['p(95)<300'],
    'fulltext_duration': ['p(95)<300'],
    // La page lointaine n'a **pas** de seuil : elle est mesuree pour documenter la limite de la
    // pagination par offset, pas pour la faire tenir. Lui imposer 300 ms conduirait a « optimiser »
    // un cas que personne n'atteint en usage reel.
    'checks': ['rate>0.99'],
  },
};

export function setup() {
  const res = http.post(
    `${BASE}/api/auth/login`,
    JSON.stringify({ email: EMAIL, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  if (res.status !== 200) {
    throw new Error(`Authentification impossible (${res.status}) — verifiez EMAIL/PASSWORD`);
  }
  return { token: res.json('accessToken') };
}

export default function (data) {
  const params = {
    headers: { Authorization: `Bearer ${data.token}` },
    tags: { endpoint: 'tickets' },
  };

  group('list', () => {
    const res = http.get(`${BASE}/api/tickets?page=0&size=20`, params);
    listTrend.add(res.timings.duration);
    check(res, { 'list 200': (r) => r.status === 200 });
  });

  group('filtered', () => {
    const res = http.get(
      `${BASE}/api/tickets?status=NEW&category=FACTURATION&page=0&size=20`,
      params,
    );
    filteredTrend.add(res.timings.duration);
    check(res, { 'filtered 200': (r) => r.status === 200 });
  });

  group('fulltext', () => {
    // Termes tires du corpus genere : une recherche qui ne trouve rien ne mesure que le parcours
    // de l'index, pas le tri par pertinence sur un ensemble de resultats.
    const terms = ['remboursement', 'livraison', 'refund', 'paiement', 'account'];
    const q = terms[Math.floor(Math.random() * terms.length)];
    const res = http.get(`${BASE}/api/tickets?q=${q}&page=0&size=20`, params);
    fulltextTrend.add(res.timings.duration);
    check(res, { 'fulltext 200': (r) => r.status === 200 });
  });

  group('deepPage', () => {
    const res = http.get(`${BASE}/api/tickets?page=500&size=20`, params);
    deepPageTrend.add(res.timings.duration);
    check(res, { 'deep page 200': (r) => r.status === 200 });
  });
}
