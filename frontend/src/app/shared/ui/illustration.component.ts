import { ChangeDetectionStrategy, Component, input } from '@angular/core';

export type IllustrationName =
  | 'empty-queue'
  | 'no-results'
  | 'error'
  | 'not-found'
  | 'upload'
  | 'analysis';

/**
 * Illustrations vectorielles.
 *
 * **Dessinees a la main, pas importees.** Trois raisons, dans cet ordre :
 *
 *  1. **Le theme.** Une illustration en PNG ou en SVG fige ses couleurs. Ici
 *     chaque trait consomme un token (`--border-strong`, `--accent`…), donc
 *     l'illustration bascule en sombre avec le reste de l'interface au lieu de
 *     rester un rectangle blanc au milieu d'une page sombre.
 *
 *  2. **Le poids.** Ces scenes font quelques centaines d'octets chacune,
 *     inlinees dans le bundle. Une banque d'illustrations, c'est 40 a 200 ko
 *     par image et une requete reseau, pour un ecran que l'on espere ne jamais
 *     montrer.
 *
 *  3. **La coherence.** Meme grammaire graphique que le reste : rayons de 3 a
 *     6 px, traits de 1,5 px, aucune couleur hors palette. Une illustration
 *     achetee introduit toujours son propre style.
 *
 * Le registre est volontairement **abstrait** (cartes, lignes, loupe) et non
 * figuratif : pas de personnage, donc aucune question de representation, et un
 * rendu qui vieillit mieux qu'un style illustratif date.
 *
 * `aria-hidden` systematique : le sens est porte par le titre et le texte de
 * l'etat vide, jamais par le dessin.
 */
@Component({
  selector: 'app-illustration',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg viewBox="0 0 160 120" fill="none" aria-hidden="true" [style.width.px]="size()">
      <!-- Halo commun : ancre la scene et cree une profondeur douce. -->
      <ellipse class="halo" cx="80" cy="98" rx="52" ry="7" />

      @switch (name()) {
        <!-- ---------- File vide : des cartes bien rangees, rien a traiter -->
        @case ('empty-queue') {
          <rect class="surface" x="34" y="26" width="92" height="20" rx="6" />
          <rect class="line" x="44" y="34" width="34" height="4" rx="2" />
          <rect class="line line--faint" x="84" y="34" width="18" height="4" rx="2" />

          <rect class="surface" x="34" y="52" width="92" height="20" rx="6" />
          <rect class="line" x="44" y="60" width="26" height="4" rx="2" />
          <rect class="line line--faint" x="76" y="60" width="22" height="4" rx="2" />

          <rect class="surface surface--ghost" x="40" y="78" width="80" height="14" rx="6" />
          <circle class="accent-fill" cx="112" cy="20" r="11" />
          <path class="on-accent" d="M107.5 20.2l3 3 6-6.4" stroke-width="2"
                stroke-linecap="round" stroke-linejoin="round" />
        }

        <!-- ---------- Aucun resultat : une loupe sur des lignes estompees -->
        @case ('no-results') {
          <rect class="surface" x="24" y="30" width="112" height="58" rx="8" />
          <rect class="line line--faint" x="36" y="44" width="52" height="4" rx="2" />
          <rect class="line line--faint" x="36" y="56" width="72" height="4" rx="2" />
          <rect class="line line--faint" x="36" y="68" width="40" height="4" rx="2" />
          <circle class="stroke-strong" cx="104" cy="58" r="20" stroke-width="3" />
          <path class="stroke-accent" d="M118 72l14 14" stroke-width="4" stroke-linecap="round" />
        }

        <!-- ---------- Erreur : un lien rompu -->
        @case ('error') {
          <rect class="surface" x="30" y="30" width="100" height="56" rx="8" />
          <path class="stroke-danger" d="M62 58h14" stroke-width="3" stroke-linecap="round" />
          <path class="stroke-danger" d="M84 58h14" stroke-width="3" stroke-linecap="round"
                stroke-dasharray="4 6" />
          <circle class="danger-fill" cx="80" cy="34" r="13" />
          <path class="on-danger" d="M80 28.5v7" stroke-width="2.4" stroke-linecap="round" />
          <circle class="on-danger-fill" cx="80" cy="40" r="1.5" />
        }

        <!-- ---------- 404 : une carte qui sort du cadre -->
        @case ('not-found') {
          <rect class="surface surface--ghost" x="22" y="34" width="80" height="52" rx="8" />
          <rect class="surface" x="58" y="22" width="80" height="52" rx="8" />
          <rect class="line" x="70" y="38" width="38" height="4" rx="2" />
          <rect class="line line--faint" x="70" y="50" width="54" height="4" rx="2" />
          <rect class="line line--faint" x="70" y="60" width="30" height="4" rx="2" />
          <path class="stroke-accent" d="M34 62l14 14M48 62l-14 14" stroke-width="3"
                stroke-linecap="round" />
        }

        <!-- ---------- Depot de fichier : une feuille qui monte vers un plateau -->
        @case ('upload') {
          <path class="stroke-strong" d="M40 74v10a6 6 0 006 6h68a6 6 0 006-6V74"
                stroke-width="3" stroke-linecap="round" />
          <rect class="surface" x="60" y="18" width="40" height="48" rx="6" />
          <rect class="line line--faint" x="68" y="30" width="24" height="4" rx="2" />
          <rect class="line line--faint" x="68" y="40" width="18" height="4" rx="2" />
          <path class="stroke-accent" d="M80 60V34" stroke-width="3" stroke-linecap="round" />
          <path class="stroke-accent" d="M71 43l9-9 9 9" stroke-width="3"
                stroke-linecap="round" stroke-linejoin="round" />
        }

        <!-- ---------- Analyse : un histogramme qui monte -->
        @default {
          <rect class="surface" x="26" y="24" width="108" height="64" rx="8" />
          <rect class="accent-fill" x="42" y="58" width="12" height="18" rx="3" />
          <rect class="accent-fill accent-fill--soft" x="62" y="48" width="12" height="28" rx="3" />
          <rect class="accent-fill" x="82" y="38" width="12" height="38" rx="3" />
          <rect class="accent-fill accent-fill--soft" x="102" y="52" width="12" height="24" rx="3" />
          <path class="stroke-accent" d="M42 44l20-8 20-10 20 6" stroke-width="2.4"
                stroke-linecap="round" stroke-linejoin="round" stroke-dasharray="3 5" />
        }
      }
    </svg>
  `,
  styles: [
    `
      :host {
        display: block;
        line-height: 0;
      }

      svg {
        height: auto;
        max-width: 100%;
        /* Entree douce : l'illustration se pose au lieu d'apparaitre. */
        animation: illus-in 520ms cubic-bezier(0.22, 1, 0.36, 1) both;
      }

      @keyframes illus-in {
        from { opacity: 0; transform: translateY(6px) scale(0.98); }
        to { opacity: 1; transform: translateY(0) scale(1); }
      }

      .halo { fill: var(--bg-sunken); }

      .surface {
        fill: var(--bg-surface);
        stroke: var(--border-strong);
        stroke-width: 1.5;
      }

      /* Element en arriere-plan : meme forme, presence divisee par deux. */
      .surface--ghost {
        fill: var(--bg-sunken);
        stroke: var(--border-default);
        opacity: 0.75;
      }

      .line { fill: var(--text-disabled); }
      .line--faint { fill: var(--border-strong); }

      .stroke-strong { stroke: var(--border-strong); fill: none; }
      .stroke-accent { stroke: var(--accent); fill: none; }
      .stroke-danger { stroke: var(--danger); fill: none; }

      .accent-fill { fill: var(--accent); }
      .accent-fill--soft { fill: var(--accent); opacity: 0.42; }
      .danger-fill { fill: var(--danger); }

      .on-accent { stroke: var(--accent-contrast); fill: none; }
      .on-danger { stroke: #fff; fill: none; }
      .on-danger-fill { fill: #fff; }

      @media (prefers-reduced-motion: reduce) {
        svg { animation: none; }
      }
    `,
  ],
})
export class IllustrationComponent {
  readonly name = input<IllustrationName>('analysis');
  readonly size = input(160);
}
