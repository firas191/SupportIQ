import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

const W = 100;
const H = 30;

/**
 * Micro-courbe de tendance (sparkline).
 *
 * Ecrite en SVG a la main plutot qu'avec Chart.js : a cette taille on n'a
 * besoin ni d'axes, ni d'echelle, ni d'infobulle, ni de legende. Instancier un
 * moteur de graphique complet par carte KPI couterait un canvas, une boucle
 * d'animation et ~30 ko de traitement pour dessiner douze segments.
 *
 * Le trace est en coordonnees normalisees (100 x 30) et l'element s'etire via
 * `preserveAspectRatio="none"` : la courbe epouse la carte quelle que soit sa
 * largeur, sans recalcul au redimensionnement.
 *
 * Role dans l'interface : une valeur seule ne dit pas si elle est bonne. « 412
 * tickets » n'a de sens qu'accompagne de sa trajectoire. La sparkline apporte
 * ce contexte en occupant une place negligeable.
 */
@Component({
  selector: 'app-sparkline',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg
      [attr.viewBox]="'0 0 ' + W + ' ' + H"
      preserveAspectRatio="none"
      [attr.role]="ariaLabel() ? 'img' : null"
      [attr.aria-hidden]="ariaLabel() ? null : 'true'"
      [attr.aria-label]="ariaLabel() || null"
    >
      <defs>
        <linearGradient [attr.id]="gradientId" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" [attr.stop-color]="color()" stop-opacity="0.22" />
          <stop offset="100%" [attr.stop-color]="color()" stop-opacity="0" />
        </linearGradient>
      </defs>

      @if (path(); as d) {
        <path [attr.d]="areaPath()" [attr.fill]="'url(#' + gradientId + ')'" />
        <path
          [attr.d]="d"
          fill="none"
          [attr.stroke]="color()"
          stroke-width="1.6"
          stroke-linecap="round"
          stroke-linejoin="round"
          vector-effect="non-scaling-stroke"
        />
        <circle [attr.cx]="lastPoint().x" [attr.cy]="lastPoint().y" r="1.8" [attr.fill]="color()" />
      }
    </svg>
  `,
  styles: [
    `
      :host {
        display: block;
        width: 100%;
        height: 30px;
      }

      svg {
        width: 100%;
        height: 100%;
        overflow: visible;
      }

      path {
        animation: spark-draw var(--duration-slow) var(--ease-out) both;
      }

      @keyframes spark-draw {
        from { opacity: 0; }
        to { opacity: 1; }
      }
    `,
  ],
})
export class SparklineComponent {
  readonly data = input<number[]>([]);
  readonly color = input('var(--accent)');
  /** Libelle accessible, fourni par l'appelant (deja traduit). Vide = la
      courbe est purement decorative et sort de l'arbre d'accessibilite. */
  readonly ariaLabel = input('');

  protected readonly W = W;
  protected readonly H = H;
  /** Un identifiant par instance : deux degrades SVG ne peuvent pas partager un id. */
  protected readonly gradientId = `spark-${Math.random().toString(36).slice(2, 9)}`;

  private readonly points = computed(() => {
    const values = this.data();
    if (values.length < 2) {
      return [];
    }
    const min = Math.min(...values);
    const max = Math.max(...values);
    // Serie plate : on la centre au lieu de diviser par zero.
    const span = max - min || 1;
    const step = W / (values.length - 1);
    // 2 px de marge haute et basse pour que la ligne ne soit pas coupee.
    return values.map((v, i) => ({
      x: i * step,
      y: H - 2 - ((v - min) / span) * (H - 4),
    }));
  });

  protected readonly path = computed(() => {
    const pts = this.points();
    if (pts.length === 0) {
      return null;
    }
    return pts.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(2)},${p.y.toFixed(2)}`).join(' ');
  });

  protected readonly areaPath = computed(() => {
    const pts = this.points();
    if (pts.length === 0) {
      return '';
    }
    return `${this.path()} L${W},${H} L0,${H} Z`;
  });

  protected readonly lastPoint = computed(() => {
    const pts = this.points();
    return pts.length ? pts[pts.length - 1] : { x: 0, y: 0 };
  });
}
