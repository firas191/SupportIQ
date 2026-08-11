import { ChartOptions } from 'chart.js';

/**
 * Pont entre les tokens CSS et Chart.js.
 *
 * Chart.js dessine dans un `<canvas>` : il ne comprend ni les custom
 * properties, ni la cascade CSS. Impossible de lui passer
 * `var(--cat-technique)` — il faut lui donner une couleur resolue.
 *
 * Ce module fait donc deux choses :
 *  1. lire la valeur reelle d'un token au moment du rendu ;
 *  2. produire un jeu d'options communes (grille, axes, infobulle) accorde au
 *     theme courant.
 *
 * Consequence : les graphiques suivent la bascule clair/sombre comme le reste
 * de l'interface, sans qu'aucune couleur ne soit dupliquee en TypeScript.
 */

/** Resout un token CSS (`--accent`) en sa valeur calculee (`#5d51d8`). */
export function token(name: string, fallback = '#888888'): string {
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return value || fallback;
}

/** Couleur de categorie, avec repli neutre pour une valeur inconnue. */
export function categoryColor(category: string): string {
  const map: Record<string, string> = {
    TECHNIQUE: '--cat-technique',
    FACTURATION: '--cat-facturation',
    COMPTE: '--cat-compte',
    RECLAMATION: '--cat-reclamation',
    DEMANDE: '--cat-demande',
    NON_ANALYSE: '--cat-unknown',
  };
  return token(map[category] ?? '--cat-unknown');
}

export function sentimentColor(sentiment: string): string {
  const map: Record<string, string> = { NEG: '--danger', NEU: '--text-tertiary', POS: '--success' };
  return token(map[sentiment] ?? '--cat-unknown');
}

export function priorityColor(priority: string): string {
  const map: Record<string, string> = { HIGH: '--danger', MEDIUM: '--warning', LOW: '--success' };
  return token(map[priority] ?? '--cat-unknown');
}

/**
 * Options communes a tous les graphiques.
 *
 * Choix de lisibilite plutot que d'esthetique :
 *  - **pas de grille verticale** : elle n'aide jamais a lire une valeur, elle
 *    ne fait qu'ajouter du bruit ;
 *  - **grille horizontale tres pale** : elle guide l'oeil vers l'axe sans
 *    devenir un motif ;
 *  - **bordure d'axe supprimee** : le graphique flotte dans la carte au lieu
 *    d'etre enferme dans un cadre ;
 *  - **infobulle au survol de la colonne entiere** (`intersect: false`) : on
 *    n'a pas a viser precisement un point de 3 px.
 */
export function baseChartOptions(): ChartOptions {
  const text = token('--text-tertiary');
  const grid = token('--border-subtle');
  const surface = token('--bg-surface');
  const border = token('--border-default');
  const strong = token('--text-primary');

  return {
    responsive: true,
    maintainAspectRatio: false,
    // Chart.js formate ses nombres avec `Intl` et prend par defaut la langue du
    // **navigateur**, pas celle de l'application : un utilisateur au navigateur
    // anglais lisait « 15,000 » sur un ecran en francais. On lit la langue sur
    // `<html>`, que le service d'internationalisation tient a jour — meme
    // mecanique que `token()` juste au-dessus, qui lit les couleurs depuis le
    // DOM. Aucun appelant n'a a passer la locale, et la bascule FR/EN recalcule
    // deja les configurations.
    locale: document.documentElement.lang === 'fr' ? 'fr-FR' : 'en-GB',
    animation: { duration: 420, easing: 'easeOutQuart' },
    interaction: { mode: 'index', intersect: false },
    layout: { padding: { top: 4 } },
    plugins: {
      legend: {
        display: false,
      },
      tooltip: {
        backgroundColor: surface,
        titleColor: strong,
        bodyColor: text,
        borderColor: border,
        borderWidth: 1,
        padding: 10,
        cornerRadius: 8,
        boxPadding: 4,
        usePointStyle: true,
        titleFont: { family: 'Inter, sans-serif', size: 12, weight: 600 },
        bodyFont: { family: 'Inter, sans-serif', size: 12 },
        displayColors: true,
      },
    },
    scales: {
      x: {
        grid: { display: false },
        border: { display: false },
        ticks: {
          color: text,
          font: { family: 'Inter, sans-serif', size: 11 },
          maxRotation: 0,
          autoSkipPadding: 16,
        },
      },
      y: {
        beginAtZero: true,
        grid: { color: grid },
        border: { display: false },
        ticks: {
          color: text,
          font: { family: 'Inter, sans-serif', size: 11 },
          precision: 0,
          // Cinq graduations suffisent a situer une valeur. Au-dela, l'axe
          // devient une echelle de mesure et vole l'attention a la courbe.
          maxTicksLimit: 5,
        },
      },
    },
  };
}
