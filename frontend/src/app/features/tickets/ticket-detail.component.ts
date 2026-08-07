import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { I18nService } from '../../core/i18n/i18n.service';
import { TranslatePipe } from '../../core/i18n/t.pipe';
import { TranslationKey } from '../../core/i18n/translations.fr';
import { TicketDetail } from '../../core/models/ticket.models';
import { TicketsService } from '../../core/tickets/tickets.service';
import { ToastService } from '../../core/ui/toast.service';
import {
  CATEGORY_LABELS,
  PRIORITY_LABELS,
  RELIABILITY_LABELS,
  SENTIMENT_LABELS,
  analysisDepth,
  reliabilityLevel,
} from '../../shared/labels';
import { AbsoluteTimePipe, RelativeTimePipe } from '../../shared/pipes/relative-time.pipe';
import { BadgeComponent } from '../../shared/ui/badge.component';
import { ConfirmDialogComponent, ConfirmDialogData } from '../../shared/ui/confirm-dialog.component';
import { EmptyStateComponent } from '../../shared/ui/empty-state.component';
import { IconComponent } from '../../shared/ui/icon.component';
import { SkeletonComponent } from '../../shared/ui/skeleton.component';
import { DraftPanelComponent } from './draft-panel.component';

type Field = 'category' | 'priority' | 'sentiment';

interface Choice {
  value: string;
  labelKey: TranslationKey;
}

/**
 * Fiche ticket : lecture du message, resultat de l'analyse, correction humaine,
 * regroupement des doublons.
 *
 * Mise en page en deux colonnes. Le message occupe la colonne large — c'est ce
 * qu'un agent vient lire, et une ligne de texte de 70 caracteres se lit plus
 * vite qu'une de 140. Tout ce qui sert a *agir* (classement, correction,
 * doublons) tient dans un rail lateral, toujours visible pendant la lecture,
 * sans jamais couper le fil du message.
 *
 * Deux ameliorations d'interaction par rapport a la version precedente :
 *
 * 1. **Correction en un clic.** Les trois listes deroulantes sont remplacees
 *    par des groupes de pastilles. Corriger passe de deux clics (ouvrir, puis
 *    choisir) a un seul, et surtout les valeurs possibles sont visibles : on
 *    voit immediatement que « Urgente » existe, sans avoir a deplier.
 *
 * 2. **Le regroupement demande confirmation.** C'est la seule action de cet
 *    ecran qu'on ne peut pas defaire d'un clic ; le dialogue enonce la
 *    consequence exacte plutot que « etes-vous sur ? ».
 *
 * Vocabulaire : l'ecran parle de « fiabilite » et d'« analyse approfondie », pas
 * de modele ni de score. L'utilisateur a besoin de savoir s'il peut faire
 * confiance au classement — pas de savoir comment il a ete produit.
 */
@Component({
  selector: 'app-ticket-detail',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    MatTooltipModule,
    TranslatePipe,
    RelativeTimePipe,
    AbsoluteTimePipe,
    BadgeComponent,
    IconComponent,
    EmptyStateComponent,
    SkeletonComponent,
    DraftPanelComponent,
  ],
  templateUrl: './ticket-detail.component.html',
  styleUrl: './ticket-detail.component.scss',
})
export class TicketDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly tickets = inject(TicketsService);
  private readonly toast = inject(ToastService);
  private readonly dialog = inject(MatDialog);
  private readonly i18n = inject(I18nService);

  protected readonly ticket = signal<TicketDetail | null>(null);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<TranslationKey | null>(null);
  /** Champ en cours d'enregistrement : permet un retour cible, pas global. */
  protected readonly savingField = signal<Field | null>(null);

  /**
   * Champs corrigeables, dans l'ordre d'importance pour un agent : ce qui
   * change son plan de travail (priorite) avant ce qui alimente les
   * statistiques (categorie, humeur).
   *
   * Declares comme un tableau **type** plutot que parcourus comme des chaines
   * dans le gabarit : cela permet au compilateur de verifier que chaque cle
   * existe bien dans `choices`, au lieu de le decouvrir a l'execution.
   */
  protected readonly correctionFields: { key: Field; labelKey: TranslationKey }[] = [
    { key: 'priority', labelKey: 'tickets.groupPriority' },
    { key: 'category', labelKey: 'tickets.groupCategory' },
    { key: 'sentiment', labelKey: 'tickets.groupSentiment' },
  ];

  protected readonly choices: Record<Field, Choice[]> = {
    priority: this.toChoices(PRIORITY_LABELS, ['HIGH', 'MEDIUM', 'LOW']),
    category: this.toChoices(CATEGORY_LABELS, [
      'TECHNIQUE',
      'FACTURATION',
      'COMPTE',
      'RECLAMATION',
      'DEMANDE',
    ]),
    sentiment: this.toChoices(SENTIMENT_LABELS, ['NEG', 'NEU', 'POS']),
  };

  /* --- Qualite de l'analyse ---------------------------------------------- */

  protected readonly reliabilityPct = computed(() => {
    const c = this.ticket()?.analysis?.confidence;
    return c == null ? null : Math.round(c * 100);
  });

  protected readonly reliability = computed(() => {
    const level = reliabilityLevel(this.ticket()?.analysis?.confidence);
    if (!level) {
      return null;
    }
    const def = RELIABILITY_LABELS[level];
    return {
      level,
      label: this.i18n.t(def.key),
      hint: def.hintKey ? this.i18n.t(def.hintKey) : null,
    };
  });

  protected readonly depth = computed(() => {
    const analysis = this.ticket()?.analysis;
    if (!analysis) {
      return null;
    }
    const def = analysisDepth(analysis.escalatedToLlm);
    return { label: this.i18n.t(def.key), hint: this.i18n.t(def.hintKey), tone: def.tone, icon: def.icon };
  });

  /**
   * Circonference du cercle de progression (rayon 20). Le trace utilise
   * `stroke-dasharray` : la portion coloree vaut le pourcentage de fiabilite.
   */
  protected readonly ringCircumference = 2 * Math.PI * 20;

  protected readonly ringOffset = computed(() => {
    const pct = this.reliabilityPct() ?? 0;
    return this.ringCircumference * (1 - pct / 100);
  });

  protected readonly duplicates = computed(
    () => this.ticket()?.similar.filter((s) => s.duplicate) ?? [],
  );

  protected readonly duplicatesLabel = computed(() =>
    this.i18n.plural(this.duplicates().length, 'detail.duplicates', 'detail.duplicatesPlural'),
  );

  /* --- Cycle de vie ------------------------------------------------------- */

  ngOnInit(): void {
    const id = Number(this.route.snapshot.paramMap.get('id'));
    if (!Number.isFinite(id)) {
      this.error.set('detail.invalidId');
      this.loading.set(false);
      return;
    }
    this.load(id);
  }

  /* --- Actions ------------------------------------------------------------ */

  protected isCurrent(field: Field, value: string): boolean {
    const analysis = this.ticket()?.analysis;
    return !!analysis && (analysis as unknown as Record<Field, string>)[field] === value;
  }

  /** Correction d'un classement. Re-cliquer la valeur courante ne fait rien. */
  protected correct(field: Field, value: string): void {
    const current = this.ticket();
    if (!current || this.saving() || this.isCurrent(field, value)) {
      return;
    }

    this.saving.set(true);
    this.savingField.set(field);

    this.tickets.annotate(current.id, field, value).subscribe({
      next: (updated) => {
        this.ticket.set(updated);
        this.stopSaving();
        this.toast.success(this.i18n.t('detail.corrected'));
      },
      error: (err) => {
        this.stopSaving();
        this.toast.error(
          this.i18n.t(err.status === 409 ? 'detail.notAnalysedYet' : 'detail.correctFailed'),
        );
      },
    });
  }

  /** Regroupement : action peu reversible, donc confirmee. */
  protected merge(targetId: number, targetSubject: string | null): void {
    const current = this.ticket();
    if (!current) {
      return;
    }

    const data: ConfirmDialogData = {
      title: this.i18n.t('detail.mergeTitle'),
      message: this.i18n.t('detail.mergeMessage', {
        target: targetSubject || `#${targetId}`,
      }),
      confirmLabel: this.i18n.t('detail.merge'),
      destructive: true,
      icon: 'merge',
    };

    this.dialog
      .open(ConfirmDialogComponent, { data, autoFocus: false, restoreFocus: true })
      .afterClosed()
      .subscribe((confirmed) => {
        if (confirmed) {
          this.doMerge(targetId);
        }
      });
  }

  protected open(id: number): void {
    this.router.navigate(['/tickets', id]).then(() => this.load(id));
  }

  /* --- Interne ------------------------------------------------------------ */

  private doMerge(targetId: number): void {
    const current = this.ticket();
    if (!current) {
      return;
    }
    this.saving.set(true);
    this.tickets.merge(current.id, targetId).subscribe({
      next: (updated) => {
        this.ticket.set(updated);
        this.stopSaving();
        this.toast.success(this.i18n.t('detail.merged', { id: targetId }));
      },
      error: (err) => {
        this.stopSaving();
        this.toast.error(
          this.i18n.t(err.status === 409 ? 'detail.alreadyMerged' : 'detail.mergeFailed'),
        );
      },
    });
  }

  private stopSaving(): void {
    this.saving.set(false);
    this.savingField.set(null);
  }

  private load(id: number): void {
    this.loading.set(true);
    this.error.set(null);
    this.tickets.detail(id).subscribe({
      next: (t) => {
        this.ticket.set(t);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err.status === 404 ? 'detail.notFoundText' : 'detail.loadFailed');
        this.loading.set(false);
      },
    });
  }

  private toChoices(table: Record<string, { key: TranslationKey }>, order: string[]): Choice[] {
    return order.map((value) => ({ value, labelKey: table[value].key }));
  }
}
