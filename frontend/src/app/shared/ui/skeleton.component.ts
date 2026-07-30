import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * Bloc de chargement.
 *
 * Un squelette n'est pas un « joli spinner » : c'est une **promesse de mise en
 * page**. Il doit occuper exactement la place du contenu a venir, sinon
 * l'arrivee des donnees provoque un saut, et un saut est percu comme un bug.
 *
 * Regle d'usage retenue dans l'application :
 *  - **premier** chargement d'un ecran → squelette (l'utilisateur ne sait pas
 *    encore a quoi ressemble la page, on la lui dessine) ;
 *  - **rechargement** (changement de filtre, de page) → fine barre de
 *    progression en haut, et on garde les anciennes donnees a l'ecran. Vider
 *    la table pour la remplir 200 ms plus tard est desagreable et inutile.
 */
@Component({
  selector: 'app-skeleton',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span
      class="skeleton"
      [style.width]="width()"
      [style.height]="height()"
      [style.border-radius]="radius()"
      [style.animation-delay.ms]="delay()"
    ></span>
  `,
  styles: [
    `
      :host { display: block; }
      .skeleton { display: block; }
    `,
  ],
})
export class SkeletonComponent {
  readonly width = input('100%');
  readonly height = input('12px');
  readonly radius = input('6px');
  /** Decalage de la pulsation : evite que 8 lignes clignotent a l'unisson. */
  readonly delay = input(0);
}
