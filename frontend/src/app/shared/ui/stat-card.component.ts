import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Tone } from '../labels';
import { CountUpComponent } from './count-up.component';
import { IconComponent } from './icon.component';
import { SkeletonComponent } from './skeleton.component';
import { SparklineComponent } from './sparkline.component';

/**
 * Tuile d'indicateur.
 *
 * Ordre de lecture impose, du plus au moins important :
 *   1. **la valeur** — grande, chiffres tabulaires, c'est ce qu'on vient
 *      chercher ;
 *   2. **l'intitule** — petit, en capitales discretes, juste au-dessus ;
 *   3. **le contexte** — variation et micro-courbe, qui repondent a « est-ce
 *      normal ? » ;
 *   4. **le detail** — une ligne de precision, en gris.
 *
 * Une valeur sans contexte n'est pas un indicateur, c'est un nombre. « 412 »
 * ne dit rien ; « 412, +12 % sur la periode » declenche une decision. C'est
 * pour cela que la variation n'est pas optionnelle par confort mais par
 * disponibilite de la donnee.
 *
 * Le sens de la variation n'est pas toujours « monter = bien » : pour un taux
 * de mecontentement, la hausse est mauvaise. D'ou `invertTrend`.
 */
@Component({
  selector: 'app-stat-card',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent, CountUpComponent, SparklineComponent, SkeletonComponent],
  template: `
    <div class="stat" [class.stat--loading]="loading()">
      <div class="stat__top">
        <span class="stat__label">{{ label() }}</span>
        @if (icon()) {
          <span class="stat__icon" [attr.data-tone]="tone()">
            <app-icon [name]="icon()!" [size]="15" />
          </span>
        }
      </div>

      @if (loading()) {
        <app-skeleton width="72px" height="28px" radius="8px" />
        <app-skeleton width="110px" height="10px" [delay]="80" />
      } @else {
        <div class="stat__value">
          <app-count-up [value]="value()" [decimals]="decimals()" />
          @if (suffix()) {
            <span class="stat__suffix">{{ suffix() }}</span>
          }
        </div>

        <div class="stat__foot">
          @if (trend() !== null) {
            <span class="trend" [attr.data-dir]="trendDirection()">
              <app-icon [name]="trendIcon()" [size]="13" />
              {{ trendText() }}
            </span>
          }
          @if (hint()) {
            <span class="stat__hint">{{ hint() }}</span>
          }
        </div>
      }

      @if (spark().length > 1 && !loading()) {
        <div class="stat__spark">
          <app-sparkline [data]="spark()" [color]="sparkColor()" [ariaLabel]="label() + ' : tendance'" />
        </div>
      }
    </div>
  `,
  styles: [
    `
      :host { display: block; }

      .stat {
        position: relative;
        display: flex;
        flex-direction: column;
        gap: var(--space-2);
        padding: var(--space-4) var(--space-4) var(--space-3);
        min-height: 108px;
        background: var(--bg-surface);
        border: 1px solid var(--border-subtle);
        border-radius: var(--radius-lg);
        box-shadow: var(--shadow-xs);
        overflow: hidden;
        transition: border-color var(--duration-base) var(--ease-out),
          box-shadow var(--duration-base) var(--ease-out);
      }

      .stat:hover {
        border-color: var(--border-default);
        box-shadow: var(--shadow-sm);
      }

      .stat__top {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--space-2);
      }

      .stat__label {
        font-size: var(--text-xs);
        font-weight: var(--weight-semibold);
        letter-spacing: var(--tracking-wide);
        text-transform: uppercase;
        color: var(--text-tertiary);
      }

      /* Pastille d'icone teintee : identifie la nature de l'indicateur en
         peripherie du regard, sans lire l'intitule. */
      .stat__icon {
        display: grid;
        place-items: center;
        width: 26px;
        height: 26px;
        border-radius: var(--radius-sm);
        background: var(--neutral-bg);
        color: var(--text-secondary);
        flex: none;
      }
      .stat__icon[data-tone='danger'] { background: var(--danger-bg); color: var(--danger-fg); }
      .stat__icon[data-tone='warning'] { background: var(--warning-bg); color: var(--warning-fg); }
      .stat__icon[data-tone='success'] { background: var(--success-bg); color: var(--success-fg); }
      .stat__icon[data-tone='info'] { background: var(--info-bg); color: var(--info-fg); }
      .stat__icon[data-tone='accent'] { background: var(--accent-soft-bg); color: var(--accent-soft-fg); }

      .stat__value {
        display: flex;
        align-items: baseline;
        gap: 3px;
        font-size: var(--text-3xl);
        font-weight: var(--weight-bold);
        line-height: 1.05;
        letter-spacing: var(--tracking-tight);
        color: var(--text-primary);
      }

      .stat__suffix {
        font-size: var(--text-lg);
        font-weight: var(--weight-semibold);
        color: var(--text-tertiary);
      }

      .stat__foot {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        flex-wrap: wrap;
        min-height: 18px;
      }

      .stat__hint {
        font-size: var(--text-sm);
        color: var(--text-tertiary);
      }

      .trend {
        display: inline-flex;
        align-items: center;
        gap: 2px;
        font-size: var(--text-sm);
        font-weight: var(--weight-semibold);
        font-variant-numeric: tabular-nums;
        color: var(--text-tertiary);
      }
      .trend[data-dir='good'] { color: var(--success-fg); }
      .trend[data-dir='bad'] { color: var(--danger-fg); }

      /* La courbe est un fond, pas un contenu : elle est ancree en bas et
         legerement effacee pour ne jamais concurrencer le chiffre. */
      .stat__spark {
        position: absolute;
        left: 0;
        right: 0;
        bottom: 0;
        height: 30px;
        opacity: 0.55;
        pointer-events: none;
      }
    `,
  ],
})
export class StatCardComponent {
  readonly label = input.required<string>();
  readonly value = input.required<number>();
  readonly suffix = input<string | null>(null);
  readonly decimals = input(0);
  readonly hint = input<string | null>(null);
  readonly icon = input<string | null>(null);
  readonly tone = input<Tone>('neutral');
  readonly loading = input(false);
  readonly spark = input<number[]>([]);
  /** Variation en points de pourcentage sur la periode. `null` = donnee absente. */
  readonly trend = input<number | null>(null);
  /** Vrai quand une hausse est une mauvaise nouvelle (mecontentement, urgences). */
  readonly invertTrend = input(false);

  protected readonly trendDirection = computed<'good' | 'bad' | 'flat'>(() => {
    const t = this.trend();
    if (t == null || Math.abs(t) < 0.5) {
      return 'flat';
    }
    const rising = t > 0;
    return rising !== this.invertTrend() ? 'good' : 'bad';
  });

  protected readonly trendIcon = computed(() => {
    const t = this.trend() ?? 0;
    if (Math.abs(t) < 0.5) {
      return 'trending_flat';
    }
    return t > 0 ? 'trending_up' : 'trending_down';
  });

  protected readonly trendText = computed(() => {
    const t = this.trend();
    if (t == null) {
      return '';
    }
    if (Math.abs(t) < 0.5) {
      return 'stable';
    }
    return `${t > 0 ? '+' : ''}${t.toFixed(0)} %`;
  });

  protected readonly sparkColor = computed(() => {
    switch (this.tone()) {
      case 'danger':
        return 'var(--danger)';
      case 'warning':
        return 'var(--warning)';
      case 'success':
        return 'var(--success)';
      case 'info':
        return 'var(--info)';
      default:
        return 'var(--accent)';
    }
  });
}
