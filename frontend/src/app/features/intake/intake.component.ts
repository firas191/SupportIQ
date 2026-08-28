import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { I18nService } from '../../core/i18n/i18n.service';
import { TranslatePipe } from '../../core/i18n/t.pipe';
import { IntakeService } from '../../core/intake/intake.service';
import { ExtractionResult, ProposedTicket } from '../../core/models/intake.models';
import { ToastService } from '../../core/ui/toast.service';
import { IconComponent } from '../../shared/ui/icon.component';
import { IllustrationComponent } from '../../shared/ui/illustration.component';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';

/**
 * Ingestion de documents non structures : deposer, relire, creer (S7-J4).
 *
 * **L'ecran existe pour une seule raison : rien ne doit etre cree sans avoir ete
 * lu.** Un decoupage errone produit des tickets qui ressemblent en tout point a
 * de vrais tickets — meme forme, meme file, meme analyse — et que personne ne
 * verrait jamais passer. C'est la meme architecture que le brouillon de reponse
 * (S5-J4), avec un enjeu plus direct encore.
 *
 * Trois partis pris.
 *
 * 1. **Les champs peu fiables sont surlignes, pas masques.** La confiance est
 *    par champ (rapport §5.4) : en pratique le sujet et le corps sont bons et
 *    c'est l'adresse qui manque. Surligner ce seul champ dit ou regarder ;
 *    afficher un score global de 0,7 ne dit rien d'actionnable.
 *
 * 2. **Tout est modifiable avant creation.** Corriger vaut mieux qu'ecarter :
 *    une demande dont l'adresse est fausse reste une demande a traiter.
 *
 * 3. **Ecarter une entree est aussi simple que la garder.** Un document
 *    contient souvent des en-tetes ou des mentions legales que le modele prend
 *    pour une demande ; les retirer doit couter un clic.
 */
@Component({
  selector: 'app-intake',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    TranslatePipe,
    IconComponent,
    IllustrationComponent,
    PageHeaderComponent,
  ],
  templateUrl: './intake.component.html',
  styleUrl: './intake.component.scss',
})
export class IntakeComponent {
  private readonly intake = inject(IntakeService);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);
  private readonly router = inject(Router);

  /** Seuil en dessous duquel un champ est signale a relire. */
  private static readonly LOW_CONFIDENCE = 0.6;

  protected readonly result = signal<ExtractionResult | null>(null);
  protected readonly rows = signal<ProposedTicket[]>([]);
  protected readonly fileName = signal<string | null>(null);
  protected readonly extracting = signal(false);
  protected readonly creating = signal(false);
  protected readonly dragging = signal(false);

  protected readonly hasRows = computed(() => this.rows().length > 0);
  protected readonly fromOcr = computed(() => this.result()?.method === 'ocr');

  /* --- Depot -------------------------------------------------------------- */

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

  protected onPick(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (file) {
      this.upload(file);
    }
    // Sans cette remise a zero, redeposer le meme fichier apres une correction
    // ne declenche aucun evenement : la valeur de l'input n'a pas change.
    input.value = '';
  }

  /* --- Relecture ---------------------------------------------------------- */

  protected isLow(value: number): boolean {
    return value < IntakeComponent.LOW_CONFIDENCE;
  }

  protected update(index: number, field: keyof ProposedTicket, value: string): void {
    this.rows.update((list) =>
      list.map((row, i) => (i === index ? { ...row, [field]: value } : row)),
    );
  }

  protected remove(index: number): void {
    this.rows.update((list) => list.filter((_, i) => i !== index));
  }

  protected reset(): void {
    this.result.set(null);
    this.rows.set([]);
    this.fileName.set(null);
  }

  protected confirm(): void {
    const tickets = this.rows();
    if (!tickets.length || this.creating()) {
      return;
    }
    this.creating.set(true);
    this.intake.confirm(tickets).subscribe({
      next: (created) => {
        this.creating.set(false);
        this.reset();
        this.toast.success(this.i18n.t('intake.created', { n: created.created }));
        // On emmene l'agent voir ce qu'il vient de creer : les tickets partent
        // aussitot en analyse, et l'ecran d'ingestion n'a plus rien a montrer.
        this.router.navigate(['/tickets']);
      },
      error: () => {
        this.creating.set(false);
        this.toast.error(this.i18n.t('intake.createFailed'));
      },
    });
  }

  /* --- Interne ------------------------------------------------------------ */

  private upload(file: File): void {
    if (this.extracting()) {
      return;
    }
    this.extracting.set(true);
    this.fileName.set(file.name);

    this.intake.extract(file).subscribe({
      next: (result) => {
        this.extracting.set(false);
        this.result.set(result);
        this.rows.set(result.tickets);
        if (!result.tickets.length) {
          // Zero demande extraite est un **resultat**, pas une panne : le
          // document peut n'en contenir aucune. Le dire evite de redeposer le
          // meme fichier trois fois en croyant a une erreur.
          this.toast.info(this.i18n.t('intake.nothingFound'));
        }
      },
      error: (err) => {
        this.extracting.set(false);
        this.fileName.set(null);
        this.toast.error(
          this.i18n.t(err.status === 415 ? 'intake.unsupported' : 'intake.extractFailed'),
        );
      },
    });
  }
}
