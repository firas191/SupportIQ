import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { DraftsService } from '../../core/drafts/drafts.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { TranslatePipe } from '../../core/i18n/t.pipe';
import { TranslationKey } from '../../core/i18n/translations.fr';
import { Draft, DraftStatus, DraftTone } from '../../core/models/draft.models';
import { ToastService } from '../../core/ui/toast.service';
import { splitCitations } from '../../shared/citations';
import { AbsoluteTimePipe, RelativeTimePipe } from '../../shared/pipes/relative-time.pipe';
import { IconComponent } from '../../shared/ui/icon.component';
import { IllustrationComponent } from '../../shared/ui/illustration.component';
import { SkeletonComponent } from '../../shared/ui/skeleton.component';

/**
 * Panneau « reponse proposee » de la fiche ticket (S5-J4, rapport §5.2).
 *
 * C'est l'ecran ou la plateforme cesse d'analyser pour proposer d'agir — et donc
 * celui qui doit rendre la garantie visible : **rien ne part sans qu'un humain
 * ait tranche**. Trois choix portent cette garantie plutot que de l'annoncer.
 *
 * 1. **Les sources sont a portee de clic, jamais a portee d'ecran.** Un
 *    marqueur `[1]` ouvre le passage exact, dans le panneau. La tentation etait
 *    de renvoyer vers l'ecran de la base de connaissances : il est reserve aux
 *    administrateurs, un agent y serait refuse — et surtout, verifier ne doit
 *    pas couter de quitter ce qu'on est en train de lire. Une verification qui
 *    coute un changement d'ecran ne se fait pas.
 *
 * 2. **L'abstention n'est pas une alerte.** Quand la documentation ne couvre
 *    pas la demande, le panneau affiche « rien a proposer » et **retire** le
 *    bouton de validation. Le texte d'abstention s'adresse a l'agent, pas au
 *    client : le proposer a l'envoi serait un piege. Le distinguer d'un
 *    brouillon douteux evite aussi d'habituer l'agent a ignorer les avertissements.
 *
 * 3. **Corriger ne remplace pas le brouillon.** La version corrigee est
 *    enregistree a cote de la sortie du modele, jamais par-dessus. On peut
 *    ainsi mesurer *combien* il a fallu reecrire, pas seulement combien de
 *    brouillons ont ete valides.
 *
 * Vocabulaire : « reponse proposee », « sources », « fiabilite ». Ni modele, ni
 * agent, ni citation au sens academique.
 */
@Component({
  selector: 'app-draft-panel',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslatePipe,
    RelativeTimePipe,
    AbsoluteTimePipe,
    IconComponent,
    SkeletonComponent,
    IllustrationComponent,
  ],
  templateUrl: './draft-panel.component.html',
  styleUrl: './draft-panel.component.scss',
})
export class DraftPanelComponent {
  private readonly drafts = inject(DraftsService);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);

  readonly ticketId = input.required<number>();
  /** Le ticket est deja regroupe : repondre n'a plus lieu d'etre. */
  readonly readOnly = input(false);

  protected readonly draft = signal<Draft | null>(null);
  protected readonly loading = signal(true);
  /** Une action est en cours (generation ou revue) : les boutons se verrouillent. */
  protected readonly working = signal(false);
  protected readonly tone = signal<DraftTone>('formal');
  protected readonly editing = signal(false);
  protected readonly editText = signal('');
  /** Source ouverte. `null` = toutes repliees. */
  protected readonly openMarker = signal<number | null>(null);

  protected readonly tones: { value: DraftTone; labelKey: TranslationKey }[] = [
    { value: 'formal', labelKey: 'draft.toneFormal' },
    { value: 'empathetic', labelKey: 'draft.toneEmpathetic' },
  ];

  constructor() {
    // La fiche permet de sauter d'un ticket a l'autre sans quitter la route (les
    // demandes proches sont cliquables) : le panneau doit suivre. Un chargement
    // dans `ngOnInit` resterait fige sur le premier ticket ouvert.
    //
    // `allowSignalWrites` : l'effet pilote volontairement l'etat du panneau —
    // c'est sa raison d'etre, pas un effet de bord accidentel. Meme motif que
    // dans le compteur anime.
    effect(
      () => {
        const id = this.ticketId();
        this.reset();
        this.load(id);
      },
      { allowSignalWrites: true },
    );
  }

  /* --- Etat derive -------------------------------------------------------- */

  /** Texte faisant foi : la version corrigee si elle existe, sinon celle du modele. */
  protected readonly text = computed(() => {
    const d = this.draft();
    return d ? (d.finalContent ?? d.content) : '';
  });

  protected readonly wasEdited = computed(() => this.draft()?.finalContent != null);

  private readonly knownMarkers = computed(
    () => new Set(this.draft()?.citations.map((c) => c.marker) ?? []),
  );

  /** Texte decoupe en fragments : le gabarit ne manipule jamais de balisage. */
  protected readonly segments = computed(() => splitCitations(this.text(), this.knownMarkers()));

  /** Une decision a ete prise : le panneau devient une archive consultable. */
  protected readonly decided = computed(() => {
    const status = this.draft()?.status;
    return status === 'SENT' || status === 'REJECTED';
  });

  protected readonly canAct = computed(
    () => !this.readOnly() && !this.decided() && !this.working(),
  );

  protected readonly statusKey = computed<TranslationKey>(() => {
    switch (this.draft()?.status) {
      case 'SENT':
        return 'draft.statusApproved';
      case 'EDITED':
        return 'draft.statusEdited';
      case 'REJECTED':
        return 'draft.statusRejected';
      default:
        return 'draft.statusProposed';
    }
  });

  /* --- Actions ------------------------------------------------------------ */

  protected setTone(value: DraftTone): void {
    this.tone.set(value);
  }

  protected toggleSource(marker: number): void {
    this.openMarker.update((current) => (current === marker ? null : marker));
  }

  protected generate(): void {
    if (this.working()) {
      return;
    }
    this.working.set(true);
    this.drafts.generate(this.ticketId(), this.tone()).subscribe({
      next: (d) => {
        this.draft.set(d);
        this.editing.set(false);
        this.openMarker.set(null);
        this.working.set(false);
      },
      error: (err) => {
        this.working.set(false);
        this.toast.error(this.i18n.t(this.errorKey(err.status)));
      },
    });
  }

  protected startEdit(): void {
    this.editText.set(this.text());
    this.editing.set(true);
  }

  protected cancelEdit(): void {
    this.editing.set(false);
  }

  protected onEditInput(event: Event): void {
    this.editText.set((event.target as HTMLTextAreaElement).value);
  }

  protected saveEdit(): void {
    const trimmed = this.editText().trim();
    if (!trimmed) {
      this.toast.error(this.i18n.t('draft.emptyText'));
      return;
    }
    if (trimmed === this.text().trim()) {
      // Rien a enregistrer : on referme sans appel reseau plutot que de laisser
      // le backend repondre 400 sur une non-action.
      this.editing.set(false);
      return;
    }
    this.review('EDITED', trimmed);
  }

  protected approve(): void {
    this.review('SENT');
  }

  protected reject(): void {
    this.review('REJECTED');
  }

  protected async copy(): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.text());
      this.toast.success(this.i18n.t('draft.copied'));
    } catch {
      // Presse-papiers refuse (contexte non securise, permission) : on le dit,
      // le texte reste selectionnable a la main.
      this.toast.error(this.i18n.t('draft.copyFailed'));
    }
  }

  /* --- Interne ------------------------------------------------------------ */

  private review(status: DraftStatus, content?: string): void {
    const current = this.draft();
    if (!current || this.working()) {
      return;
    }
    this.working.set(true);
    this.drafts.review(current.id, status, content).subscribe({
      next: (d) => {
        this.draft.set(d);
        this.editing.set(false);
        this.working.set(false);
        this.toast.success(this.i18n.t(this.successKey(status)));
      },
      error: (err) => {
        this.working.set(false);
        this.toast.error(this.i18n.t(this.errorKey(err.status)));
      },
    });
  }

  private successKey(status: DraftStatus): TranslationKey {
    switch (status) {
      case 'SENT':
        return 'draft.approved';
      case 'REJECTED':
        return 'draft.rejected';
      default:
        return 'draft.saved';
    }
  }

  private errorKey(status: number): TranslationKey {
    if (status === 409) {
      return 'draft.conflict';
    }
    if (status === 503) {
      return 'draft.unavailable';
    }
    return 'draft.failed';
  }

  private reset(): void {
    this.draft.set(null);
    this.editing.set(false);
    this.openMarker.set(null);
    this.working.set(false);
  }

  private load(ticketId: number): void {
    this.loading.set(true);
    this.drafts.latest(ticketId).subscribe({
      next: (d) => {
        // Garde anti-reponse perimee : passer vite d'un ticket a l'autre lance
        // deux requetes, et rien ne garantit qu'elles reviennent dans l'ordre.
        // Sans ce test, le brouillon du ticket precedent peut s'afficher sur le
        // ticket courant — et un agent validerait une reponse ecrite pour une
        // autre demande.
        if (this.ticketId() !== ticketId) {
          return;
        }
        this.draft.set(d);
        if (d) {
          this.tone.set(d.tone);
        }
        this.loading.set(false);
      },
      error: () => {
        // Absence de brouillon = 204 (corps nul), pas une erreur. Arriver ici
        // signale une vraie panne : le panneau se replie sur son etat vide
        // plutot que d'occuper la fiche avec un message d'echec secondaire.
        this.draft.set(null);
        this.loading.set(false);
      },
    });
  }
}
