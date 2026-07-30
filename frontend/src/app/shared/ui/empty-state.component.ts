import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { IconComponent } from './icon.component';

/**
 * Etat vide.
 *
 * Un ecran sans donnees est le moment ou l'utilisateur doute le plus : « est-ce
 * que ca charge encore ? est-ce casse ? est-ce que j'ai mal cherche ? ». Un
 * etat vide traite repond aux trois en une phrase et propose la sortie.
 *
 * D'ou la structure imposee : une icone (reperage), un titre qui **constate**
 * ("Aucun ticket ne correspond"), une phrase qui **explique ou oriente**, et une
 * action facultative. Jamais un simple "Aucun resultat" seul.
 */
@Component({
  selector: 'app-empty-state',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent],
  template: `
    <div class="empty-state">
      <div class="empty-state__icon">
        <app-icon [name]="icon()" [size]="24" />
      </div>
      <p class="empty-state__title">{{ title() }}</p>
      @if (text()) {
        <p class="empty-state__text">{{ text() }}</p>
      }
      <ng-content />
    </div>
  `,
  styles: [':host { display: block; }'],
})
export class EmptyStateComponent {
  readonly icon = input('inbox');
  readonly title = input.required<string>();
  readonly text = input<string | null>(null);
}
