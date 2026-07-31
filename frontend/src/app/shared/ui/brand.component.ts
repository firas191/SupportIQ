import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * Marque de l'application : logo Proxym + nom du produit.
 *
 * **Pourquoi un composant.** Le bloc de marque apparaissait a trois endroits
 * (barre laterale, panneau de connexion, en-tete mobile) sous forme de SVG
 * recopie. Trois copies, c'est trois occasions de diverger — et le jour ou le
 * logo change, trois fichiers a retrouver. Un composant, un seul point de
 * verite.
 *
 * **Pourquoi une image et non un SVG inline.** Le logo est une composition de
 * neuf tuiles en transparence : reproduire fidelement ces superpositions en
 * SVG a la main serait long et fragile. Il est identique dans les deux themes
 * — le logo a ses couleurs propres, il n'a pas a suivre la palette.
 *
 * **Pourquoi la version 192 px et non le fichier source (625 px).** La marque
 * s'affiche entre 28 et 34 px : servir 30 ko pour cela est du gaspillage.
 * 192 px couvre les ecrans jusqu'a 3x de densite pour 12 ko, et le fichier est
 * deja telecharge puisqu'il sert aussi de favicon — donc zero requete
 * supplementaire.
 *
 * **Dimensions explicites** (`width`/`height` sur la balise) : sans elles, le
 * navigateur ne connait pas la place a reserver et la barre laterale sursaute
 * au chargement du logo. C'est la cause la plus courante de decalage visuel au
 * demarrage.
 *
 * `variant="on-dark"` inverse la couleur du nom du produit, pour les fonds
 * satures (panneau de connexion) ou le texte primaire ne passerait pas.
 */
@Component({
  selector: 'app-brand',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="brand" [class.brand--on-dark]="variant() === 'on-dark'">
      <img
        class="brand__mark"
        src="brand/favicon-192.png"
        alt=""
        aria-hidden="true"
        [width]="size()"
        [height]="size()"
        [style.width.px]="size()"
        [style.height.px]="size()"
        decoding="async"
      />
      @if (showName()) {
        <span class="brand__name" [style.font-size.px]="nameSize()">SupportIQ</span>
      }
    </span>
  `,
  styles: [
    `
      :host { display: inline-flex; min-width: 0; }

      .brand {
        display: inline-flex;
        align-items: center;
        gap: var(--space-2);
        min-width: 0;
      }

      .brand__mark {
        flex: none;
        object-fit: contain;
        /* Le logo est deja compose de formes arrondies : aucune ombre ni
           bordure, elles ne feraient qu'alourdir une marque deja riche. */
      }

      .brand__name {
        font-weight: var(--weight-bold);
        letter-spacing: var(--tracking-tight);
        color: var(--text-primary);
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }

      .brand--on-dark .brand__name { color: #fff; }
    `,
  ],
})
export class BrandComponent {
  readonly size = input(28);
  readonly nameSize = input(16);
  readonly showName = input(true);
  readonly variant = input<'default' | 'on-dark'>('default');
}
