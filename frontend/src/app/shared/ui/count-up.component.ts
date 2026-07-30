import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';

/**
 * Compteur anime.
 *
 * Interet reel, au-dela de l'effet : une valeur qui **monte** signale qu'elle
 * vient d'etre calculee et attire l'oeil une fraction de seconde. Sur un
 * tableau de bord charge, c'est ce qui fait remarquer un chiffre plutot que de
 * le laisser se fondre dans la page.
 *
 * Trois garde-fous, sans lesquels l'effet devient une nuisance :
 *  - courbe `easeOutExpo` : l'essentiel de la distance est parcouru dans le
 *    premier tiers du temps. Le chiffre est lisible presque tout de suite,
 *    l'animation finit de se poser ensuite ;
 *  - duree plafonnee a 700 ms — au-dela, on attend son propre tableau de bord ;
 *  - `prefers-reduced-motion` : valeur finale affichee immediatement.
 */
@Component({
  selector: 'app-count-up',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="t-num">{{ display() }}</span>`,
  styles: [':host { display: inline; }'],
})
export class CountUpComponent {
  readonly value = input.required<number>();
  readonly decimals = input(0);
  readonly duration = input(700);

  /** Valeur intermediaire pendant l'animation. */
  private readonly current = signal(0);
  private frame?: number;

  protected readonly display = computed(() =>
    this.current().toLocaleString('fr-FR', {
      minimumFractionDigits: this.decimals(),
      maximumFractionDigits: this.decimals(),
    }),
  );

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stop());

    // allowSignalWrites : l'effet pilote volontairement un signal (la valeur
    // intermediaire de l'animation). C'est l'usage legitime de l'option — un
    // effet de bord sur le temps, pas un calcul derive deguise.
    effect(() => this.animateTo(this.value() ?? 0), { allowSignalWrites: true });
  }

  private animateTo(target: number): void {
    this.stop();

    const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    const from = this.current();

    // Un ecart minuscule ne merite pas d'animation : on saute directement.
    if (reduced || Math.abs(target - from) < 0.01) {
      this.current.set(target);
      return;
    }

    const start = performance.now();
    const duration = Math.min(this.duration(), 700);

    const tick = (now: number) => {
      const t = Math.min((now - start) / duration, 1);
      const eased = t === 1 ? 1 : 1 - Math.pow(2, -10 * t); // easeOutExpo
      this.current.set(from + (target - from) * eased);
      if (t < 1) {
        this.frame = requestAnimationFrame(tick);
      }
    };
    this.frame = requestAnimationFrame(tick);
  }

  private stop(): void {
    if (this.frame != null) {
      cancelAnimationFrame(this.frame);
      this.frame = undefined;
    }
  }
}
