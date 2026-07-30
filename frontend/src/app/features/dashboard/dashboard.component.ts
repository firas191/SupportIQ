import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { ChartConfiguration } from 'chart.js';
import { DashboardService } from '../../core/dashboard/dashboard.service';
import { CountByLabel, Kpi, Trends } from '../../core/models/dashboard.models';
import { ThemeService } from '../../core/theme/theme.service';
import { CATEGORY_LABELS, PRIORITY_LABELS, SENTIMENT_LABELS, textOf } from '../../shared/labels';
import {
  baseChartOptions,
  categoryColor,
  priorityColor,
  sentimentColor,
  token,
} from '../../shared/chart/chart-theme';
import { ChartComponent } from '../../shared/chart/chart.component';
import { EmptyStateComponent } from '../../shared/ui/empty-state.component';
import { IconComponent } from '../../shared/ui/icon.component';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import { StatCardComponent } from '../../shared/ui/stat-card.component';

/**
 * Vue d'ensemble.
 *
 * Refonte guidee par une question : **que vient chercher un responsable ?**
 * Pas un inventaire, mais une reponse a « est-ce que ca va ? ». La version
 * precedente ouvrait sur « Tickets au total » — un chiffre qui ne declenche
 * aucune decision — et mettait au meme niveau une metrique d'ingenierie.
 *
 * Le nouvel ordre de lecture suit la valeur d'action :
 *   1. **A traiter** — la charge de travail immediate ;
 *   2. **Urgences** — ce qui risque de deraper ;
 *   3. **Clients mecontents** — le risque de reputation ;
 *   4. **Resolus** — la contrepartie positive, pour ne pas ne montrer que des
 *      problemes.
 * Les graphiques viennent apres : ils expliquent les chiffres, ils ne les
 * remplacent pas.
 *
 * Vocabulaire : le taux d'« escalade LLM » devient « analyses approfondies »,
 * la « confiance moyenne » devient « fiabilite ». L'information — combien de
 * tickets ont demande un second passage, plus couteux — reste exposee, parce
 * qu'elle est utile a un responsable. C'est le jargon qui disparait, pas la
 * donnee.
 *
 * Les couleurs des graphiques sont resolues depuis les tokens **a chaque
 * recalcul**, et les `computed` dependent du signal de theme : la bascule
 * clair/sombre repeint les graphiques comme le reste de la page.
 */
@Component({
  selector: 'app-dashboard',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    PageHeaderComponent,
    StatCardComponent,
    ChartComponent,
    IconComponent,
    EmptyStateComponent,
  ],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
})
export class DashboardComponent implements OnInit {
  private readonly dashboard = inject(DashboardService);
  private readonly theme = inject(ThemeService);

  protected readonly kpi = signal<Kpi | null>(null);
  protected readonly trends = signal<Trends | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly days = signal(30);

  protected readonly periods = [
    { value: 7, label: '7 jours' },
    { value: 30, label: '30 jours' },
    { value: 90, label: '90 jours' },
  ];

  protected readonly periodLabel = computed(
    () => this.periods.find((p) => p.value === this.days())?.label ?? '',
  );

  ngOnInit(): void {
    this.load();
  }

  protected setPeriod(days: number): void {
    if (days === this.days()) {
      return;
    }
    this.days.set(days);
    this.load();
  }

  protected reload(): void {
    this.load();
  }

  /* =========================================================================
     Series derivees
     ========================================================================= */

  /** Volume total par jour — alimente la micro-courbe de la premiere tuile. */
  private readonly dailyTotals = computed<number[]>(() => {
    const daily = this.trends()?.daily ?? [];
    if (daily.length === 0) {
      return [];
    }
    const byDay = new Map<string, number>();
    for (const point of daily) {
      byDay.set(point.day, (byDay.get(point.day) ?? 0) + point.count);
    }
    return [...byDay.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([, count]) => count);
  });

  /**
   * Variation du volume : moyenne de la seconde moitie de la periode comparee
   * a la premiere. Une comparaison « dernier jour contre premier jour » serait
   * a la merci d'un week-end ou d'un jour ferie ; comparer deux moyennes lisse
   * ce bruit sans masquer une vraie tendance.
   *
   * `null` si la periode est trop courte — mieux vaut ne rien afficher qu'un
   * chiffre non significatif.
   */
  protected readonly volumeTrend = computed<number | null>(() => {
    const series = this.dailyTotals();
    if (series.length < 4) {
      return null;
    }
    const mid = Math.floor(series.length / 2);
    const avg = (xs: number[]) => xs.reduce((a, b) => a + b, 0) / (xs.length || 1);
    const before = avg(series.slice(0, mid));
    const after = avg(series.slice(mid));
    if (before === 0) {
      return null;
    }
    return ((after - before) / before) * 100;
  });

  /** Categories classees par volume : le classement est l'information utile. */
  protected readonly rankedCategories = computed(() => {
    const data = this.trends()?.byCategory ?? [];
    const total = data.reduce((sum, d) => sum + d.count, 0) || 1;
    return [...data]
      .sort((a, b) => b.count - a.count)
      .map((d) => ({
        label: textOf(CATEGORY_LABELS, d.label),
        count: d.count,
        share: Math.round((d.count / total) * 100),
        color: categoryColor(d.label),
      }));
  });

  protected readonly hasTrends = computed(() => (this.trends()?.daily.length ?? 0) > 0);

  /* =========================================================================
     Configurations Chart.js
     Chaque `computed` lit `theme.theme()` : c'est ce qui cree la dependance et
     force le recalcul (donc le repeint) a la bascule de theme.
     ========================================================================= */

  /** Volume quotidien empile par categorie. */
  protected readonly volumeChart = computed<ChartConfiguration | null>(() => {
    this.theme.theme();
    const trends = this.trends();
    if (!trends || trends.daily.length === 0) {
      return null;
    }

    const days = [...new Set(trends.daily.map((p) => p.day))].sort();
    const categories = [...new Set(trends.daily.map((p) => p.category))];

    return {
      type: 'line',
      data: {
        labels: days.map((d) => this.shortDay(d)),
        datasets: categories.map((category) => {
          const color = categoryColor(category);
          return {
            label: textOf(CATEGORY_LABELS, category),
            data: days.map(
              (day) => trends.daily.find((p) => p.day === day && p.category === category)?.count ?? 0,
            ),
            borderColor: color,
            backgroundColor: this.alpha(color, 0.14),
            // Aires empilees : la hauteur totale donne le volume global, chaque
            // bande donne la part d'une categorie. Deux lectures en un dessin.
            fill: true,
            tension: 0.35,
            borderWidth: 2,
            pointRadius: 0,
            pointHoverRadius: 4,
            pointHoverBorderWidth: 2,
            pointHoverBackgroundColor: token('--bg-surface'),
            pointHoverBorderColor: color,
          };
        }),
      },
      options: {
        ...baseChartOptions(),
        scales: {
          ...baseChartOptions().scales,
          y: { ...baseChartOptions().scales?.['y'], stacked: true },
          x: { ...baseChartOptions().scales?.['x'], stacked: true },
        },
      },
    };
  });

  /** Humeur des clients — anneau : trois parts d'un tout, lecture immediate. */
  protected readonly sentimentChart = computed<ChartConfiguration | null>(() => {
    this.theme.theme();
    return this.doughnut(this.trends()?.bySentiment, SENTIMENT_LABELS, sentimentColor);
  });

  /** Priorite — barres horizontales : trois libelles lisibles a l'horizontale. */
  protected readonly priorityChart = computed<ChartConfiguration | null>(() => {
    this.theme.theme();
    const data = this.trends()?.byPriority;
    if (!data || data.length === 0) {
      return null;
    }
    const order = ['HIGH', 'MEDIUM', 'LOW'];
    const sorted = [...data].sort((a, b) => order.indexOf(a.label) - order.indexOf(b.label));
    const base = baseChartOptions();

    return {
      type: 'bar',
      data: {
        labels: sorted.map((d) => textOf(PRIORITY_LABELS, d.label)),
        datasets: [
          {
            label: 'Tickets',
            data: sorted.map((d) => d.count),
            backgroundColor: sorted.map((d) => priorityColor(d.label)),
            borderRadius: 5,
            borderSkipped: false,
            barThickness: 22,
          },
        ],
      },
      options: {
        ...base,
        indexAxis: 'y',
        scales: {
          x: { ...base.scales?.['y'], grid: { color: token('--border-subtle') } },
          y: { ...base.scales?.['x'] },
        },
      },
    };
  });

  /** Affluence horaire — quand faut-il du monde en ligne ? */
  protected readonly hourlyChart = computed<ChartConfiguration | null>(() => {
    this.theme.theme();
    const data = this.trends()?.hourly;
    if (!data || data.length === 0) {
      return null;
    }

    const byHour = new Map(data.map((h) => [h.hour, h.count]));
    const hours = Array.from({ length: 24 }, (_, i) => i);
    const counts = hours.map((h) => byHour.get(h) ?? 0);
    const max = Math.max(...counts, 1);
    const accent = token('--accent');

    return {
      type: 'bar',
      data: {
        // Une graduation sur trois : 24 etiquettes se chevauchent.
        labels: hours.map((h) => (h % 3 === 0 ? `${h}h` : '')),
        datasets: [
          {
            label: 'Tickets reçus',
            data: counts,
            // Opacite proportionnelle au volume : les heures de pointe
            // ressortent avant meme de lire l'axe. Chart.js n'a pas de type
            // « carte de chaleur » natif ; le plugin matrix serait une
            // dependance de plus pour ce seul graphique.
            backgroundColor: counts.map((c) => this.alpha(accent, 0.18 + 0.8 * (c / max))),
            borderRadius: 3,
            borderSkipped: false,
          },
        ],
      },
      options: baseChartOptions(),
    };
  });

  /* =========================================================================
     Interne
     ========================================================================= */

  private doughnut(
    data: CountByLabel[] | undefined,
    labels: Record<string, { label: string }>,
    color: (key: string) => string,
  ): ChartConfiguration | null {
    if (!data || data.length === 0) {
      return null;
    }
    // Typage explicite : `cutout` n'existe que sur la configuration d'un
    // anneau, pas sur le type generique ChartConfiguration.
    const config: ChartConfiguration<'doughnut'> = {
      type: 'doughnut',
      data: {
        labels: data.map((d) => labels[d.label]?.label ?? d.label),
        datasets: [
          {
            data: data.map((d) => d.count),
            backgroundColor: data.map((d) => color(d.label)),
            // Un anneau fin (72 %) et un ecart entre les parts : la forme se
            // lit comme une jauge plutot que comme un camembert.
            borderWidth: 0,
            spacing: 3,
            hoverOffset: 5,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        cutout: '72%',
        plugins: {
          legend: {
            position: 'bottom',
            labels: {
              color: token('--text-secondary'),
              font: { family: 'Inter, sans-serif', size: 12 },
              usePointStyle: true,
              pointStyle: 'circle',
              boxWidth: 7,
              padding: 14,
            },
          },
          tooltip: baseChartOptions().plugins?.tooltip,
        },
      },
    };
    return config as ChartConfiguration;
  }

  /** Applique une opacite a une couleur resolue (#rrggbb ou rgb(...)). */
  private alpha(color: string, value: number): string {
    if (color.startsWith('#') && color.length === 7) {
      const r = parseInt(color.slice(1, 3), 16);
      const g = parseInt(color.slice(3, 5), 16);
      const b = parseInt(color.slice(5, 7), 16);
      return `rgba(${r}, ${g}, ${b}, ${value})`;
    }
    if (color.startsWith('rgb(')) {
      return color.replace('rgb(', 'rgba(').replace(')', `, ${value})`);
    }
    return color;
  }

  private shortDay(iso: string): string {
    const date = new Date(iso);
    return Number.isNaN(date.getTime())
      ? iso
      : date.toLocaleDateString('fr-FR', { day: 'numeric', month: 'short' });
  }

  private load(): void {
    this.loading.set(true);
    this.error.set(null);

    this.dashboard.kpis().subscribe({
      next: (k) => this.kpi.set(k),
      error: () => this.error.set('Les indicateurs n’ont pas pu être chargés.'),
    });

    this.dashboard.trends(this.days()).subscribe({
      next: (t) => {
        this.trends.set(t);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Les indicateurs n’ont pas pu être chargés.');
        this.loading.set(false);
      },
    });
  }
}
