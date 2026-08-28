// Charge sur le tableau de bord et la fiche ticket (S7-J5).
//
//   k6 run -e BASE=http://localhost:8080 -e EMAIL=... -e PASSWORD=... perf/k6/dashboard.js
//
// ----------------------------------------------------------------------------
// Deux mesures tres differentes dans le meme scenario, et c'est voulu
// ----------------------------------------------------------------------------
//
// `kpis` et `trends` sont **caches 60 secondes** cote serveur (Caffeine, S4-J1). Un tir de charge
// mesure donc essentiellement le cout d'un acces au cache — ce qui est exactement ce qu'un
// utilisateur subit, mais ne dit rien du cout reel des vues d'agregation.
//
// La ligne `dashboard_cold_duration` isole le **premier** appel de chaque palier : c'est lui qui
// traverse les vues. Sans cette distinction, on publierait un P95 de 5 ms en croyant avoir mesure
// des agregats sur 50 000 tickets.
//
// `detail` n'est pas cache et fait le travail le plus varie : ticket + analyse en une requete,
// citations rehydratees en un aller-retour (S5-J4), et un appel HTTP au service IA pour les
// tickets similaires — degradant en liste vide s'il est absent.

import http from 'k6/http';
import { check, group } from 'k6';
import { Trend } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://localhost:8080';
const EMAIL = __ENV.EMAIL || 'admin@supportiq.local';
const PASSWORD = __ENV.PASSWORD || 'firas';

const coldTrend = new Trend('dashboard_cold_duration', true);
const warmTrend = new Trend('dashboard_warm_duration', true);
const detailTrend = new Trend('detail_duration', true);

export const options = {
  scenarios: {
    steady: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '20s', target: 8 },
        { duration: '1m30s', target: 8 },
        { duration: '20s', target: 0 },
      ],
    },
  },
  thresholds: {
    'dashboard_warm_duration': ['p(95)<300'],
    'detail_duration': ['p(95)<300'],
    // Le premier appel traverse les vues d'agregation : on le **mesure** sans lui imposer le meme
    // seuil, parce qu'il n'arrive qu'une fois par minute et par instance.
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
    throw new Error(`Authentification impossible (${res.status})`);
  }

  // Un identifiant de ticket reel : demander /api/tickets/1 mesurerait un 404, pas une fiche.
  const list = http.get(`${BASE}/api/tickets?page=0&size=1`, {
    headers: { Authorization: `Bearer ${res.json('accessToken')}` },
  });
  const content = list.json('content');
  return {
    token: res.json('accessToken'),
    ticketId: content && content.length ? content[0].id : null,
  };
}

export default function (data) {
  const params = { headers: { Authorization: `Bearer ${data.token}` } };

  group('kpis', () => {
    const res = http.get(`${BASE}/api/dashboard/kpis`, params);
    // Premiere iteration de chaque VU : cache probablement froid.
    (__ITER === 0 ? coldTrend : warmTrend).add(res.timings.duration);
    check(res, { 'kpis 200': (r) => r.status === 200 });
  });

  group('trends', () => {
    const res = http.get(`${BASE}/api/dashboard/trends?days=30`, params);
    (__ITER === 0 ? coldTrend : warmTrend).add(res.timings.duration);
    check(res, { 'trends 200': (r) => r.status === 200 });
  });

  if (data.ticketId) {
    group('detail', () => {
      const res = http.get(`${BASE}/api/tickets/${data.ticketId}`, params);
      detailTrend.add(res.timings.duration);
      check(res, { 'detail 200': (r) => r.status === 200 });
    });
  }
}
