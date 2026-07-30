import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * En-tete de page.
 *
 * Tous les ecrans commencent pareil : un titre, une phrase de contexte, des
 * actions a droite. En faire un composant garantit le meme rythme vertical
 * partout — c'est ce genre de regularite invisible qui distingue un produit
 * d'un assemblage d'ecrans.
 *
 * Le sous-titre n'est pas decoratif : il repond a « qu'est-ce que je regarde,
 * et sur quelle periode / quel perimetre ».
 */
@Component({
  selector: 'app-page-header',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="head">
      <div class="head__text">
        <h1 class="t-title">{{ title() }}</h1>
        @if (subtitle()) {
          <p class="head__sub">{{ subtitle() }}</p>
        }
      </div>
      <div class="head__actions">
        <ng-content />
      </div>
    </header>
  `,
  styles: [
    `
      :host { display: block; }

      .head {
        display: flex;
        align-items: flex-start;
        justify-content: space-between;
        gap: var(--space-4);
        flex-wrap: wrap;
      }

      .head__text { min-width: 0; }

      .head__sub {
        margin: 4px 0 0;
        font-size: var(--text-base);
        color: var(--text-tertiary);
        max-width: 62ch;
        line-height: var(--leading-snug);
      }

      .head__actions {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        flex-wrap: wrap;
      }
    `,
  ],
})
export class PageHeaderComponent {
  readonly title = input.required<string>();
  readonly subtitle = input<string | null>(null);
}
