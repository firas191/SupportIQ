import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { I18nService } from '../../core/i18n/i18n.service';
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
 * Un seul composant pour priorite, statut, humeur, categorie et origine : ils
 * partagent la meme geometrie, seuls le libelle et le ton changent. Cinq
 * composants quasi identiques divergeraient tot ou tard.
 *
 * Le composant fait trois choses que le gabarit appelant n'a plus a faire :
 *  - traduire la valeur brute de l'API dans la langue courante ;
 *  - choisir le ton, donc la couleur, a partir du **sens** et non du gout ;
 *  - poser la bonne marque visuelle selon la famille.
 *
 * Deux familles ont un traitement particulier :
 *  - **Priorite** : pastille pleine de la couleur du ton, sans aucun glyphe.
 *    Les icones precedentes se lisaient comme de la ponctuation dans une
 *    colonne dense ; la pastille donne un point d'ancrage a position fixe qui
 *    rend la colonne balayable sans la charger.
 *  - **Categorie** : ton neutre + pastille de sa teinte d'identification. Un
 *    fond colore la mettrait au meme niveau que la priorite, alors qu'elle ne
 *    demande aucune action.
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
      [attr.title]="hint()"
    >
      @if (dotColor(); as color) {
        <i class="badge__dot" [style.background]="color"></i>
      } @else if (showIcon() && def()?.icon) {
        <app-icon [name]="def()!.icon!" [size]="13" />
      }
      {{ text() }}
    </span>
  `,
  styles: [
    `
      :host { display: inline-flex; }

      .badge__dot {
        width: 6px;
        height: 6px;
        border-radius: 999px;
        flex: none;
      }
    `,
  ],
})
export class BadgeComponent {
  private readonly i18n = inject(I18nService);

  readonly kind = input<BadgeKind>('plain');
  readonly value = input<string | null | undefined>(null);
  /** Libelle deja traduit, impose (mode `plain`). */
  readonly label = input<string | null>(null);
  readonly toneOverride = input<Tone | null>(null);
  readonly showIcon = input(false);

  protected readonly def = computed<LabelDef | null>(() => {
    const kind = this.kind();
    return kind === 'plain' ? null : labelOf(TABLES[kind], this.value());
  });

  protected readonly text = computed(() => {
    const forced = this.label();
    if (forced) {
      return forced;
    }
    const def = this.def();
    return def ? this.i18n.t(def.key) : (this.value() ?? '—');
  });

  protected readonly hint = computed(() => {
    const hintKey = this.def()?.hintKey;
    return hintKey ? this.i18n.t(hintKey) : null;
  });

  protected readonly tone = computed<Tone>(
    () => this.toneOverride() ?? this.def()?.tone ?? 'neutral',
  );

  /**
   * Couleur de la pastille, ou `null` si cette famille n'en porte pas.
   * Priorite : couleur du ton. Categorie : teinte d'identification.
   */
  protected readonly dotColor = computed<string | null>(() => {
    const kind = this.kind();
    if (kind === 'category' && this.value()) {
      return CATEGORY_COLOR_VAR[this.value()!] ?? 'var(--cat-unknown)';
    }
    if (kind === 'priority' && this.value()) {
      const tone = this.tone();
      if (tone === 'danger') return 'var(--danger)';
      if (tone === 'warning') return 'var(--warning)';
      return 'var(--text-disabled)';
    }
    return null;
  });
}
