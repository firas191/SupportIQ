import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
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

type Field = 'category' | 'priority' | 'sentiment';

interface Choice {
  value: string;
  label: string;
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
    RelativeTimePipe,
    AbsoluteTimePipe,
    BadgeComponent,
    IconComponent,
    EmptyStateComponent,
    SkeletonComponent,
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

  protected readonly ticket = signal<TicketDetail | null>(null);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
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
  protected readonly correctionFields: { key: Field; label: string }[] = [
    { key: 'priority', label: 'Priorité' },
    { key: 'category', label: 'Catégorie' },
    { key: 'sentiment', label: 'Humeur du client' },
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
    return level ? { level, ...RELIABILITY_LABELS[level] } : null;
  });

  protected readonly depth = computed(() => {
    const analysis = this.ticket()?.analysis;
    return analysis ? analysisDepth(analysis.escalatedToLlm) : null;
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

  /* --- Cycle de vie ------------------------------------------------------- */

  ngOnInit(): void {
    const id = Number(this.route.snapshot.paramMap.get('id'));
    if (!Number.isFinite(id)) {
      this.error.set('Identifiant de ticket invalide.');
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
        this.toast.success('Classement corrigé. Merci, cela améliore les suivants.');
      },
      error: (err) => {
        this.stopSaving();
        this.toast.error(
          err.status === 409
            ? "Ce ticket n'a pas encore été analysé : rien à corriger pour l'instant."
            : "La correction n'a pas pu être enregistrée.",
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
      title: 'Regrouper ces deux demandes ?',
      message:
        `Ce ticket sera rattaché à « ${targetSubject || 'ticket #' + targetId} » et disparaîtra ` +
        `de la file active. L'historique est conservé.`,
      confirmLabel: 'Regrouper',
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
        this.toast.success(`Demandes regroupées sous le ticket #${targetId}.`);
      },
      error: (err) => {
        this.stopSaving();
        this.toast.error(
          err.status === 409 ? 'Ce ticket est déjà regroupé.' : "Le regroupement a échoué.",
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
        this.error.set(
          err.status === 404
            ? "Ce ticket n'existe pas ou a été supprimé."
            : 'Impossible de charger ce ticket pour le moment.',
        );
        this.loading.set(false);
      },
    });
  }

  private toChoices(table: Record<string, { label: string }>, order: string[]): Choice[] {
    return order.map((value) => ({ value, label: table[value]?.label ?? value }));
  }
}
