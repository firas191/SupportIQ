import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatDialog } from '@angular/material/dialog';
import { MatTooltipModule } from '@angular/material/tooltip';
import { I18nService } from '../../core/i18n/i18n.service';
import { TranslatePipe } from '../../core/i18n/t.pipe';
import { TranslationKey } from '../../core/i18n/translations.fr';
import { KnowledgeService } from '../../core/knowledge/knowledge.service';
import { KbChunk, KbDocument } from '../../core/models/kb.models';
import { ToastService } from '../../core/ui/toast.service';
import { AbsoluteTimePipe, RelativeTimePipe } from '../../shared/pipes/relative-time.pipe';
import { ConfirmDialogComponent, ConfirmDialogData } from '../../shared/ui/confirm-dialog.component';
import { EmptyStateComponent } from '../../shared/ui/empty-state.component';
import { IconComponent } from '../../shared/ui/icon.component';
import { IllustrationComponent } from '../../shared/ui/illustration.component';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import { SkeletonComponent } from '../../shared/ui/skeleton.component';

/**
 * Base de connaissances — ecran d'administration (S5-J1).
 *
 * Ce que cet ecran doit rendre evident, dans l'ordre :
 *
 * 1. **Ce que la plateforme sait.** La liste des documents avec, pour chacun, combien de passages
 *    sont reellement interrogeables. Un document charge mais non vectorise est invisible a la
 *    recherche : le taire serait un mensonge d'interface.
 *
 * 2. **Comment lui apprendre quelque chose.** Une zone de depot, au meme endroit que celle des
 *    imports de tickets — la coherence entre deux ecrans d'administration compte plus que
 *    l'originalite de chacun.
 *
 * 3. **Ce qu'elle repondrait.** Le banc d'essai de recherche est le cœur du livrable « KB
 *    interrogeable ». Sans lui, l'administrateur depose des fichiers a l'aveugle et ne decouvre la
 *    qualite du corpus qu'au moment ou un agent recoit un mauvais brouillon.
 *
 * Le vocabulaire reste **produit** : « fragments », « interrogeables », « pertinence ». Ni chunk,
 * ni embedding, ni cosinus.
 */
@Component({
  selector: 'app-knowledge',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatTooltipModule,
    TranslatePipe,
    RelativeTimePipe,
    AbsoluteTimePipe,
    PageHeaderComponent,
    IconComponent,
    IllustrationComponent,
    EmptyStateComponent,
    SkeletonComponent,
  ],
  templateUrl: './knowledge.component.html',
  styleUrl: './knowledge.component.scss',
})
export class KnowledgeComponent implements OnInit {
  private readonly kb = inject(KnowledgeService);
  private readonly toast = inject(ToastService);
  private readonly dialog = inject(MatDialog);
  private readonly i18n = inject(I18nService);

  protected readonly documents = signal<KbDocument[]>([]);
  protected readonly totalChunks = signal(0);
  protected readonly loading = signal(true);
  protected readonly uploading = signal(false);
  protected readonly reindexing = signal(false);
  protected readonly dragging = signal(false);

  protected readonly question = new FormControl('', { nonNullable: true });
  protected readonly results = signal<KbChunk[] | null>(null);
  protected readonly searching = signal(false);

  protected readonly skeletonRows = [1, 2, 3];

  /** Fragments interrogeables : c'est ce chiffre, et non le total, qui mesure la couverture reelle. */
  protected readonly searchableChunks = computed(() =>
    this.documents().reduce((sum, d) => sum + d.indexed, 0),
  );

  /** Vrai si au moins un document a des passages non vectorises — la reindexation a un sens. */
  protected readonly needsReindex = computed(() => this.documents().some((d) => !d.fullyIndexed));

  protected readonly busy = computed(() => this.uploading() || this.reindexing());

  ngOnInit(): void {
    this.load();
  }

  /* --- Depot de document -------------------------------------------------- */

  protected onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(true);
  }

  protected onDragLeave(): void {
    this.dragging.set(false);
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    const file = event.dataTransfer?.files?.[0];
    if (file) {
      this.upload(file);
    }
  }

  protected onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (file) {
      this.upload(file);
    }
    // Remis a zero pour autoriser le re-depot du meme fichier apres correction.
    input.value = '';
  }

  /* --- Actions ------------------------------------------------------------ */

  protected reindex(): void {
    this.reindexing.set(true);
    this.kb.reindex().subscribe({
      next: (res) => {
        this.reindexing.set(false);
        this.toast.success(
          res.processed > 0
            ? this.i18n.t('kb.reindexDone', { n: res.processed })
            : this.i18n.t('kb.reindexNothing'),
        );
        this.load();
      },
      error: (err: HttpErrorResponse) => {
        this.reindexing.set(false);
        this.toast.error(this.i18n.t(this.errorKey(err)));
      },
    });
  }

  protected confirmDelete(document: KbDocument): void {
    const data: ConfirmDialogData = {
      title: this.i18n.t('kb.deleteTitle'),
      message: this.i18n.t('kb.deleteMessage', {
        title: document.title,
        chunks: document.chunks,
      }),
      confirmLabel: this.i18n.t('kb.delete'),
      destructive: true,
      icon: 'delete',
    };

    this.dialog
      .open(ConfirmDialogComponent, { data, autoFocus: false, restoreFocus: true })
      .afterClosed()
      .subscribe((confirmed) => {
        if (confirmed) {
          this.remove(document.source);
        }
      });
  }

  /**
   * Soumission du banc d'essai.
   *
   * `preventDefault` est indispensable : sans directive Angular sur le `<form>`,
   * le navigateur applique son comportement natif et **recharge la page**.
   * Le `<form>` est conserve malgre tout — c'est lui qui fait fonctionner la
   * touche Entree et qui donne sa semantique au champ de recherche.
   */
  protected onSubmit(event: Event): void {
    event.preventDefault();
    this.search();
  }

  protected search(): void {
    const question = this.question.value.trim();
    if (!question || question.length < 2) {
      return;
    }
    this.searching.set(true);
    this.kb.search(question, 5).subscribe({
      next: (chunks) => {
        this.results.set(chunks);
        this.searching.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.searching.set(false);
        this.toast.error(this.i18n.t(this.errorKey(err)));
      },
    });
  }

  /** Pourcentage affiche a cote d'un resultat — « 0.8412 » ne parle a personne. */
  protected relevance(similarity: number): number {
    return Math.round(similarity * 100);
  }

  /**
   * Largeur de la barre de pertinence.
   *
   * Le pourcentage brut est **honnete mais illisible** : les similarites cosinus d'un modele
   * bi-encodeur se concentrent dans une bande etroite (typiquement 0,75 a 0,92). Des barres
   * remplies a 87, 82, 82 et 81 % paraissent toutes pleines, et l'utilisateur en conclut que les
   * cinq resultats se valent — alors que le premier est nettement meilleur.
   *
   * On etale donc la barre sur la plage reellement utilisee. Le **chiffre reste la valeur vraie** :
   * seule la representation graphique est recalee, jamais la donnee.
   *
   * Cette compression est aussi la raison d'etre du reranking par cross-encodeur prevu au J2 : un
   * bi-encodeur classe correctement mais discrimine mal.
   */
  protected relevanceBar(similarity: number): number {
    const FLOOR = 0.7;
    const CEIL = 0.95;
    const scaled = ((similarity - FLOOR) / (CEIL - FLOOR)) * 100;
    return Math.max(6, Math.min(100, Math.round(scaled)));
  }

  /* --- Interne ------------------------------------------------------------ */

  private upload(file: File): void {
    this.uploading.set(true);
    this.kb.ingest(file).subscribe({
      next: (res) => {
        this.uploading.set(false);
        this.toast.success(this.i18n.t('kb.indexed', { title: res.title, chunks: res.chunks }));
        // Le banc d'essai devient trompeur apres un changement de corpus : on l'efface.
        this.results.set(null);
        this.load();
      },
      error: (err: HttpErrorResponse) => {
        this.uploading.set(false);
        this.toast.error(this.i18n.t(this.errorKey(err)));
      },
    });
  }

  private remove(source: string): void {
    this.kb.delete(source).subscribe({
      next: () => {
        this.toast.success(this.i18n.t('kb.deleted'));
        this.results.set(null);
        this.load();
      },
      error: (err: HttpErrorResponse) => this.toast.error(this.i18n.t(this.errorKey(err))),
    });
  }

  private load(): void {
    this.loading.set(true);
    this.kb.overview().subscribe({
      next: (overview) => {
        this.documents.set(overview.documents);
        this.totalChunks.set(overview.totalChunks);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.toast.error(this.i18n.t('kb.loadFailed'));
      },
    });
  }

  /**
   * Message adapte au statut. Les trois cas distingues correspondent a trois actions differentes
   * pour l'utilisateur : changer de fichier, en prendre un plus petit, ou reessayer plus tard.
   */
  private errorKey(err: HttpErrorResponse): TranslationKey {
    switch (err.status) {
      case 415:
        return 'kb.badFormat';
      case 413:
        return 'kb.tooLarge';
      case 502:
      case 503:
        return 'kb.serviceDown';
      default:
        return 'kb.uploadFailed';
    }
  }
}
