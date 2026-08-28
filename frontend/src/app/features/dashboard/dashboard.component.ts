import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { ChartConfiguration } from 'chart.js';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { DashboardService } from '../../core/dashboard/dashboard.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { TranslatePipe } from '../../core/i18n/t.pipe';
import { TranslationKey } from '../../core/i18n/translations.fr';
import { TicketSummary } from '../../core/models/ticket.models';
import { TicketsService } from '../../core/tickets/tickets.service';
import { CountByLabel, Kpi, Trends } from '../../core/models/dashboard.models';
import { ThemeService } from '../../core/theme/theme.service';
import { CATEGORY_LABELS, PRIORITY_LABELS, SENTIMENT_LABELS } from '../../shared/labels';
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
import { BadgeComponent } from '../../shared/ui/badge.component';
import { RelativeTimePipe } from '../../shared/pipes/relative-time.pipe';
import { SkeletonComponent } from '../../shared/ui/skeleton.component';
import { StatCardComponent } from '../../shared/ui/stat-card.component';
import { AlertsPanelComponent } from '../alerts/alerts-panel.component';

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
    RouterLink,
    TranslatePipe,
    RelativeTimePipe,
    PageHeaderComponent,
    StatCardComponent,
    ChartComponent,
    IconComponent,
    BadgeComponent,
    SkeletonComponent,
    EmptyStateComponent,
    AlertsPanelComponent,
  ],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
})
export class DashboardComponent implements OnInit {
  private readonly dashboard = inject(DashboardService);
  private readonly theme = inject(ThemeService);
  private readonly tickets = inject(TicketsService);
  private readonly i18n = inject(I18nService);
  private readonly auth = inject(AuthService);

  protected readonly kpi = signal<Kpi | null>(null);
  protected readonly trends = signal<Trends | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal(false);
  protected readonly days = signal(30);

  /** Derniers tickets recus — alimente le fil d'activite. */
  protected readonly recent = signal<TicketSummary[]>([]);
  protected readonly recentLoading = signal(true);

  private readonly periodDefs: { value: number; key: TranslationKey }[] = [
    { value: 7, key: 'dashboard.days7' },
    { value: 30, key: 'dashboard.days30' },
    { value: 90, key: 'dashboard.days90' },
  ];

  protected readonly periods = computed(() =>
    this.periodDefs.map((p) => ({ value: p.value, label: this.i18n.t(p.key) })),
  );

  protected readonly periodLabel = computed(
    () => this.periods().find((p) => p.value === this.days())?.label ?? '',
  );

  /**
   * Actions rapides. Elles ne creent rien : ce sont des **raccourcis vers une
   * file deja filtree**. Un responsable qui lit « 12 % d'urgences » veut
   * ensuite les voir — sans avoir a rouvrir la liste puis a reconstruire le
   * filtre a la main. Les parametres d'URL sont ceux que la liste sait relire.
   */
  protected readonly quickActions: {
    key: TranslationKey;
    icon: string;
    link: string;
    params?: Record<string, string>;
    adminOnly?: boolean;
  }[] = [
    { key: 'dashboard.goToQueue', icon: 'inbox', link: '/tickets' },
    { key: 'dashboard.goToUrgent', icon: 'priority_high', link: '/tickets', params: { priority: 'HIGH', status: 'NEW' } },
    { key: 'dashboard.goToUnhappy', icon: 'sentiment_dissatisfied', link: '/tickets', params: { sentiment: 'NEG' } },
    { key: 'dashboard.goToImports', icon: 'upload_file', link: '/imports', adminOnly: true },
  ];

  ngOnInit(): void {
    this.load();
    this.loadRecent();
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
    this.loadRecent();
  }

  /** Role courant, pour masquer l'action rapide reservee aux administrateurs. */
  protected readonly isAdmin = computed(() => this.auth.role() === 'ADMIN');

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
        raw: d.label,
        label: this.translateOr(CATEGORY_LABELS, d.label),
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
            label: this.translateOr(CATEGORY_LABELS, category),
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
        labels: sorted.map((d) => this.translateOr(PRIORITY_LABELS, d.label)),
        datasets: [
          {
            label: this.i18n.t('dashboard.ticketsLabel'),
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
            label: this.i18n.t('dashboard.receivedLabel'),
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
    labels: Record<string, { key: TranslationKey }>,
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
        labels: data.map((d) => this.translateOr(labels, d.label)),
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

  /** Libelle traduit d'une valeur metier, avec repli sur la valeur brute. */
  private translateOr(table: Record<string, { key: TranslationKey }>, value: string): string {
    const def = table[value];
    return def ? this.i18n.t(def.key) : value;
  }

  protected num(value: number): string {
    return value.toLocaleString(this.i18n.locale());
  }

  /**
   * Lecture automatique des chiffres.
   *
   * Un tableau de bord montre des donnees ; il ne dit pas ce qu'il faut en
   * penser. Cette section franchit ce pas : elle transforme les series en
   * deux ou trois phrases exploitables — la tendance, le premier motif de
   * contact, les seuils depasses.
   *
   * Les seuils (25 % d'urgences, 30 % de messages negatifs) sont des reperes
   * de bon sens, pas des valeurs apprises : ils sont ici pour rendre la lecture
   * actionnable, et se regleront avec un vrai historique.
   *
   * Si rien ne sort, on le dit explicitement plutot que de masquer la section :
   * « aucun signal d'alerte » est une information, une carte vide n'en est pas.
   */
  protected readonly insights = computed<{ icon: string; tone: string; text: string }[]>(() => {
    const out: { icon: string; tone: string; text: string }[] = [];
    const kpi = this.kpi();
    const trend = this.volumeTrend();

    if (trend !== null) {
      const abs = Math.abs(Math.round(trend));
      if (abs < 5) {
        out.push({ icon: 'trending_flat', tone: 'neutral', text: this.i18n.t('dashboard.insightVolumeStable') });
      } else {
        out.push({
          icon: trend > 0 ? 'trending_up' : 'trending_down',
          tone: trend > 0 ? 'warning' : 'success',
          text: this.i18n.t(trend > 0 ? 'dashboard.insightVolumeUp' : 'dashboard.insightVolumeDown', { n: abs }),
        });
      }
    }

    const top = this.rankedCategories()[0];
    if (top && top.share >= 25) {
      out.push({
        icon: 'donut_large',
        tone: 'neutral',
        text: this.i18n.t('dashboard.insightTopCategory', { category: top.label, n: top.share }),
      });
    }

    if (kpi && kpi.highPriorityRate >= 25) {
      out.push({
        icon: 'priority_high',
        tone: 'danger',
        text: this.i18n.t('dashboard.insightUrgent', { n: Math.round(kpi.highPriorityRate) }),
      });
    }

    if (kpi && kpi.negativeRate >= 30) {
      out.push({
        icon: 'sentiment_dissatisfied',
        tone: 'warning',
        text: this.i18n.t('dashboard.insightUnhappy', { n: Math.round(kpi.negativeRate) }),
      });
    }

    const hourly = this.trends()?.hourly ?? [];
    if (hourly.length > 0) {
      const peak = hourly.reduce((a, b) => (b.count > a.count ? b : a));
      if (peak.count > 0) {
        out.push({
          icon: 'schedule',
          tone: 'neutral',
          text: this.i18n.t('dashboard.insightPeak', { hour: peak.hour }),
        });
      }
    }

    if (out.length === 0) {
      out.push({ icon: 'check_circle', tone: 'success', text: this.i18n.t('dashboard.insightHealthy') });
    }
    return out;
  });

  /**
   * Derniers tickets recus. Aucun nouvel appel d'API : on reutilise la liste
   * paginee, page 0, taille 5, triee par date — exactement ce que l'ecran
   * Tickets demande deja.
   */
  private loadRecent(): void {
    this.recentLoading.set(true);
    this.tickets
      .list({ page: 0, size: 5, sort: 'createdAt', direction: 'desc' })
      .subscribe({
        next: (page) => {
          this.recent.set(page.content);
          this.recentLoading.set(false);
        },
        error: () => this.recentLoading.set(false),
      });
  }

  private shortDay(iso: string): string {
    const date = new Date(iso);
    return Number.isNaN(date.getTime())
      ? iso
      : date.toLocaleDateString(this.i18n.locale(), { day: 'numeric', month: 'short' });
  }

  private load(): void {
    this.loading.set(true);
    this.error.set(false);

    this.dashboard.kpis().subscribe({
      next: (k) => this.kpi.set(k),
      error: () => this.error.set(true),
    });

    this.dashboard.trends(this.days()).subscribe({
      next: (t) => {
        this.trends.set(t);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }
}
