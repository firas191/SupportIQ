import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ChartConfiguration } from 'chart.js';
import { AuthService } from '../../core/auth/auth.service';
import { DashboardService } from '../../core/dashboard/dashboard.service';
import { Kpi, Trends } from '../../core/models/dashboard.models';
import { ChartComponent } from '../../shared/chart/chart.component';

/** Palette stable : une couleur par label, pour qu'une categorie garde la meme couleur partout. */
const CATEGORY_COLORS: Record<string, string> = {
  TECHNIQUE: '#42a5f5',
  FACTURATION: '#7e57c2',
  COMPTE: '#26a69a',
  RECLAMATION: '#ef5350',
  DEMANDE: '#ffa726',
  NON_ANALYSE: '#bdbdbd',
};
const SENTIMENT_COLORS: Record<string, string> = {
  NEG: '#ef5350',
  NEU: '#90a4ae',
  POS: '#66bb6a',
};
const PRIORITY_COLORS: Record<string, string> = {
  HIGH: '#ef5350',
  MEDIUM: '#ffa726',
  LOW: '#66bb6a',
};

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [
    MatCardModule,
    MatIconModule,
    MatButtonToggleModule,
    MatProgressBarModule,
    ChartComponent,
  ],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
})
export class DashboardComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly dashboard = inject(DashboardService);

  readonly user = this.auth.user;
  readonly role = this.auth.role;

  readonly kpi = signal<Kpi | null>(null);
  readonly trends = signal<Trends | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly days = signal(30);

  ngOnInit(): void {
    this.load();
  }

  onPeriodChange(days: number): void {
    this.days.set(days);
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.error.set(null);

    this.dashboard.kpis().subscribe({
      next: (k) => this.kpi.set(k),
      error: () => this.error.set('Impossible de charger les indicateurs (rôle MANAGER requis).'),
    });

    this.dashboard.trends(this.days()).subscribe({
      next: (t) => {
        this.trends.set(t);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Impossible de charger les tendances (rôle MANAGER requis).');
        this.loading.set(false);
      },
    });
  }

  // --- Configurations Chart.js derivees des donnees (computed = recalcul automatique) ----

  /** Evolution du volume par jour, une courbe par categorie. */
  readonly dailyChart = computed<ChartConfiguration | null>(() => {
    const t = this.trends();
    if (!t || t.daily.length === 0) {
      return null;
    }
    const days = [...new Set(t.daily.map((p) => p.day))].sort();
    const categories = [...new Set(t.daily.map((p) => p.category))];
    return {
      type: 'line',
      data: {
        labels: days,
        datasets: categories.map((cat) => ({
          label: cat,
          data: days.map((d) => t.daily.find((p) => p.day === d && p.category === cat)?.count ?? 0),
          borderColor: CATEGORY_COLORS[cat] ?? '#9e9e9e',
          backgroundColor: CATEGORY_COLORS[cat] ?? '#9e9e9e',
          tension: 0.3,
        })),
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { position: 'bottom' } },
        scales: { y: { beginAtZero: true, ticks: { precision: 0 } } },
      },
    };
  });

  /** Repartition par categorie (doughnut). */
  readonly categoryChart = computed<ChartConfiguration | null>(() =>
    this.doughnut(this.trends()?.byCategory, CATEGORY_COLORS));

  /** Repartition par sentiment (doughnut). */
  readonly sentimentChart = computed<ChartConfiguration | null>(() =>
    this.doughnut(this.trends()?.bySentiment, SENTIMENT_COLORS));

  /** Repartition par priorite (barres horizontales). */
  readonly priorityChart = computed<ChartConfiguration | null>(() => {
    const data = this.trends()?.byPriority;
    if (!data || data.length === 0) {
      return null;
    }
    return {
      type: 'bar',
      data: {
        labels: data.map((d) => d.label),
        datasets: [{
          label: 'Tickets',
          data: data.map((d) => d.count),
          backgroundColor: data.map((d) => PRIORITY_COLORS[d.label] ?? '#9e9e9e'),
        }],
      },
      options: {
        indexAxis: 'y',
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { display: false } },
        scales: { x: { beginAtZero: true, ticks: { precision: 0 } } },
      },
    };
  });

  /** Charge horaire 0-23 : a quelle heure les tickets arrivent-ils ? */
  readonly hourlyChart = computed<ChartConfiguration | null>(() => {
    const data = this.trends()?.hourly;
    if (!data || data.length === 0) {
      return null;
    }
    const byHour = new Map(data.map((h) => [h.hour, h.count]));
    const hours = Array.from({ length: 24 }, (_, i) => i);
    const counts = hours.map((h) => byHour.get(h) ?? 0);
    const max = Math.max(...counts, 1);
    return {
      type: 'bar',
      data: {
        labels: hours.map((h) => `${h}h`),
        datasets: [{
          label: 'Tickets',
          data: counts,
          // Intensite proportionnelle au volume : effet "heatmap" sur des barres.
          backgroundColor: counts.map((c) => `rgba(63, 81, 181, ${0.25 + 0.75 * (c / max)})`),
        }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { display: false } },
        scales: { y: { beginAtZero: true, ticks: { precision: 0 } } },
      },
    };
  });

  private doughnut(
    data: { label: string; count: number }[] | undefined,
    palette: Record<string, string>,
  ): ChartConfiguration | null {
    if (!data || data.length === 0) {
      return null;
    }
    return {
      type: 'doughnut',
      data: {
        labels: data.map((d) => d.label),
        datasets: [{
          data: data.map((d) => d.count),
          backgroundColor: data.map((d) => palette[d.label] ?? '#9e9e9e'),
        }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { position: 'bottom' } },
      },
    };
  }
}
