import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnChanges,
  OnDestroy,
  SimpleChanges,
  input,
  viewChild,
} from '@angular/core';
import { Chart, ChartConfiguration, registerables } from 'chart.js';

// Enregistrement global des controleurs et echelles (une seule fois pour l'app).
Chart.register(...registerables);
Chart.defaults.font.family = 'Inter, sans-serif';

/**
 * Enveloppe minimale autour de Chart.js.
 *
 * Pourquoi maison plutot que `ng2-charts` : la version courante de ng2-charts
 * exige Angular 21, incompatible avec l'Angular 18 du projet. Chart.js seul n'a
 * aucune dependance Angular — ce composant d'une cinquantaine de lignes suffit
 * et evite une dependance fragile.
 *
 * Cycle de vie maitrise :
 *  - une instance de graphique par canvas ;
 *  - sur changement de donnees, `update()` plutot qu'une recreation : le
 *    graphique **anime** la transition au lieu de clignoter ;
 *  - `destroy()` a la destruction, sans quoi Chart.js garde une reference au
 *    canvas et une boucle d'animation vivante (fuite memoire classique des
 *    tableaux de bord a onglets).
 *
 * Le changement de theme est gere en amont : le composant parent recalcule la
 * configuration (couleurs resolues depuis les tokens) et la nouvelle reference
 * declenche `ngOnChanges`.
 */
@Component({
  selector: 'app-chart',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: '<canvas #canvas></canvas>',
  styles: [':host { display: block; position: relative; height: 100%; width: 100%; }'],
})
export class ChartComponent implements AfterViewInit, OnChanges, OnDestroy {
  readonly config = input.required<ChartConfiguration>();

  private readonly canvasRef = viewChild<ElementRef<HTMLCanvasElement>>('canvas');
  private chart?: Chart;
  /**
   * Type du graphique actuellement rendu. Memorise ici plutot que relu depuis
   * `chart.config` : ce dernier est une union de types dont la branche
   * « types personnalises par jeu de donnees » n'expose pas `type`.
   */
  private renderedType?: string;

  ngAfterViewInit(): void {
    this.render();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (!changes['config'] || !this.chart) {
      return;
    }
    const next = this.config();
    // Changer de type de graphique n'est pas une mise a jour : Chart.js ne
    // sait pas convertir un anneau en courbe, il faut reconstruire.
    if (next.type !== this.renderedType) {
      this.chart.destroy();
      this.render();
      return;
    }
    this.chart.data = next.data;
    this.chart.options = next.options ?? {};
    this.chart.update();
  }

  ngOnDestroy(): void {
    this.chart?.destroy();
  }

  private render(): void {
    const canvas = this.canvasRef()?.nativeElement;
    if (canvas) {
      const config = this.config();
      this.chart = new Chart(canvas, config);
      this.renderedType = config.type;
    }
  }
}
