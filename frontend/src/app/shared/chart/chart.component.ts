import {
  AfterViewInit,
  Component,
  ElementRef,
  Input,
  OnChanges,
  OnDestroy,
  SimpleChanges,
  ViewChild,
} from '@angular/core';
import { Chart, ChartConfiguration, registerables } from 'chart.js';

// Enregistrement global des controleurs/echelles Chart.js (une seule fois pour l'app).
Chart.register(...registerables);

/**
 * Wrapper minimal autour de Chart.js (S4-J2).
 *
 * Pourquoi maison plutot que `ng2-charts` : la version courante de ng2-charts exige Angular 21,
 * incompatible avec notre Angular 18 (ecart deja note en S1-J4). Chart.js seul n'a aucune
 * dependance Angular : ce composant de ~40 lignes suffit et evite une dependance fragile.
 *
 * Cycle de vie maitrise : une instance Chart par canvas, `update()` sur changement de donnees
 * (pas de recreation inutile), `destroy()` a la destruction (sinon fuite memoire + canvas fantome).
 */
@Component({
  selector: 'app-chart',
  standalone: true,
  template: '<canvas #canvas></canvas>',
  styles: [':host { display: block; position: relative; height: 100%; width: 100%; }'],
})
export class ChartComponent implements AfterViewInit, OnChanges, OnDestroy {
  @Input({ required: true }) config!: ChartConfiguration;

  @ViewChild('canvas') private canvasRef!: ElementRef<HTMLCanvasElement>;
  private chart?: Chart;

  ngAfterViewInit(): void {
    this.render();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['config'] && this.chart) {
      // Meme graphique, nouvelles donnees : on met a jour au lieu de recreer.
      this.chart.data = this.config.data;
      this.chart.options = this.config.options ?? {};
      this.chart.update();
    }
  }

  ngOnDestroy(): void {
    this.chart?.destroy();
  }

  private render(): void {
    if (!this.canvasRef) {
      return;
    }
    this.chart = new Chart(this.canvasRef.nativeElement, this.config);
  }
}
