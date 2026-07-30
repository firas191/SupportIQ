import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * Icone Material Symbols.
 *
 * Pourquoi un composant plutot que `<span class="material-symbols-rounded">` :
 * pour que la taille et le remplissage soient des **tokens** et non des valeurs
 * ecrites a la main dans 40 gabarits. Une icone de 18 px partout, sauf decision
 * explicite — c'est ce qui donne le sentiment d'un jeu d'icones coherent.
 *
 * `aria-hidden` par defaut : une icone accompagne presque toujours un texte, et
 * la faire lire par un lecteur d'ecran produit un doublon. Les rares icones
 * porteuses de sens a elles seules passent `[decorative]="false"` et fournissent
 * un `label`.
 *
 * Material Symbols est une police **variable** : le remplissage (`FILL`) et la
 * graisse se pilotent en CSS, sans charger un second fichier. C'est ce qui
 * permet d'avoir l'icone pleine sur l'entree de menu active et l'icone en trait
 * partout ailleurs, pour zero octet supplementaire.
 */
@Component({
  selector: 'app-icon',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span
      class="material-symbols-rounded"
      [style.font-size.px]="size()"
      [style.font-variation-settings]="variation()"
      [attr.aria-hidden]="decorative() ? 'true' : null"
      [attr.role]="decorative() ? null : 'img'"
      [attr.aria-label]="decorative() ? null : label()"
      >{{ name() }}</span
    >
  `,
  styles: [
    `
      :host {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        flex: none;
        line-height: 1;
      }

      span {
        line-height: 1;
        user-select: none;
      }
    `,
  ],
})
export class IconComponent {
  readonly name = input.required<string>();
  readonly size = input(18);
  readonly filled = input(false);
  readonly weight = input(400);
  readonly decorative = input(true);
  readonly label = input<string | null>(null);

  protected variation(): string {
    return `'FILL' ${this.filled() ? 1 : 0}, 'wght' ${this.weight()}, 'GRAD' 0, 'opsz' 24`;
  }
}
