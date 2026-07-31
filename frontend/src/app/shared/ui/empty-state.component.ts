import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { IllustrationComponent, IllustrationName } from './illustration.component';

/**
 * Etat vide.
 *
 * Un ecran sans donnees est le moment ou l'utilisateur doute le plus : « est-ce
 * que ca charge encore ? est-ce casse ? ai-je mal cherche ? ». Un etat vide
 * traite repond aux trois et propose la sortie.
 *
 * Structure imposee : une illustration (reperage immediat, et un peu de
 * chaleur la ou l'ecran est vide), un titre qui **constate**, une phrase qui
 * **oriente**, et une action facultative. Jamais un « Aucun resultat » seul.
 *
 * Les libelles arrivent **deja traduits** : le composant ne connait pas les
 * cles i18n, ce qui le garde reutilisable pour du texte calcule (nom de
 * fichier, requete saisie…).
 */
@Component({
  selector: 'app-empty-state',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IllustrationComponent],
  template: `
    <div class="empty-state">
      <app-illustration [name]="illustration()" [size]="compact() ? 116 : 152" />
      <p class="empty-state__title">{{ title() }}</p>
      @if (text()) {
        <p class="empty-state__text">{{ text() }}</p>
      }
      <ng-content />
    </div>
  `,
  styles: [
    `
      :host { display: block; }

      app-illustration { margin-bottom: var(--space-2); }
    `,
  ],
})
export class EmptyStateComponent {
  readonly illustration = input<IllustrationName>('empty-queue');
  readonly title = input.required<string>();
  readonly text = input<string | null>(null);
  /** Version reduite, pour les cartes de tableau de bord. */
  readonly compact = input(false);
}
