import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import {
  CATEGORY_COLOR_VAR,
  CATEGORY_LABELS,
  LabelDef,
  PRIORITY_LABELS,
  SENTIMENT_LABELS,
  SOURCE_LABELS,
  STATUS_LABELS,
  Tone,
  labelOf,
} from '../labels';
import { IconComponent } from './icon.component';

export type BadgeKind = 'priority' | 'status' | 'sentiment' | 'category' | 'source' | 'plain';

const TABLES: Record<Exclude<BadgeKind, 'plain'>, Record<string, LabelDef>> = {
  priority: PRIORITY_LABELS,
  status: STATUS_LABELS,
  sentiment: SENTIMENT_LABELS,
  category: CATEGORY_LABELS,
  source: SOURCE_LABELS,
};

/**
 * Badge de valeur metier.
 *
 * Un seul composant pour priorite, statut, sentiment, categorie et origine :
 * ils partagent la meme geometrie, seuls le libelle et le ton changent. Avoir
 * cinq composants quasi identiques, c'est garantir qu'ils divergeront.
 *
 * Le composant fait deux choses que le gabarit appelant n'a plus a faire :
 *  - traduire la valeur brute de l'API en vocabulaire produit (voir labels.ts) ;
 *  - choisir le ton, donc la couleur, a partir du sens et non de l'esthetique.
 *
 * La categorie est un cas a part : elle est affichee en ton neutre avec une
 * simple pastille de couleur. Lui donner un fond colore la mettrait au meme
 * niveau visuel que la priorite, alors qu'elle ne demande aucune action. La
 * pastille suffit a l'identifier d'un coup d'oeil dans une liste.
 */
@Component({
  selector: 'app-badge',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent],
  template: `
    <span
      class="badge"
      [class.badge--danger]="tone() === 'danger'"
      [class.badge--warning]="tone() === 'warning'"
      [class.badge--success]="tone() === 'success'"
      [class.badge--info]="tone() === 'info'"
      [class.badge--accent]="tone() === 'accent'"
      [attr.title]="def().hint"
    >
      @if (kind() === 'category' && value()) {
        <i class="cat-dot" [style.background]="categoryColor()"></i>
      } @else if (showIcon() && def().icon) {
        <app-icon [name]="def().icon!" [size]="13" />
      }
      {{ def().label }}
    </span>
  `,
  styles: [
    `
      :host { display: inline-flex; }

      .cat-dot {
        width: 6px;
        height: 6px;
        border-radius: 999px;
        flex: none;
      }
    `,
  ],
})
export class BadgeComponent {
  readonly kind = input<BadgeKind>('plain');
  readonly value = input<string | null | undefined>(null);
  /** Libelle impose (mode `plain`, ou pour surcharger la table). */
  readonly text = input<string | null>(null);
  readonly toneOverride = input<Tone | null>(null);
  readonly showIcon = input(false);

  protected readonly def = computed<LabelDef>(() => {
    const kind = this.kind();
    if (kind === 'plain') {
      return { label: this.text() ?? this.value() ?? '—', tone: this.toneOverride() ?? 'neutral' };
    }
    const found = labelOf(TABLES[kind], this.value());
    return this.text() ? { ...found, label: this.text()! } : found;
  });

  protected readonly tone = computed<Tone>(() => this.toneOverride() ?? this.def().tone);

  protected readonly categoryColor = computed(
    () => CATEGORY_COLOR_VAR[this.value() ?? ''] ?? 'var(--cat-unknown)',
  );
}
