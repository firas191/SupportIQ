import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { ImportsService } from '../../core/imports/imports.service';
import { ImportPreview, TicketField } from '../../core/models/import.models';
import { ToastService } from '../../core/ui/toast.service';
import { EmptyStateComponent } from '../../shared/ui/empty-state.component';
import { IconComponent } from '../../shared/ui/icon.component';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';

interface FieldDef {
  key: TicketField;
  label: string;
  hint: string;
  required: boolean;
  candidates: string[];
}

/**
 * Import de tickets depuis un fichier.
 *
 * Un import est un moment a risque : l'utilisateur verse des donnees dont il
 * n'est pas toujours l'auteur, dans un systeme qu'il connait mal, et une
 * erreur se paie en milliers de lignes fausses. L'ecran est donc construit
 * autour de la **confiance avant l'engagement** :
 *
 *  1. **Deposer** — glisser-deposer ou selection classique ;
 *  2. **Verifier** — nombre de lignes, erreurs detectees, apercu reel du
 *     fichier tel qu'il a ete lu (encodage compris) ;
 *  3. **Associer et confirmer** — les colonnes sont pre-associees par
 *     heuristique, l'utilisateur corrige si besoin, puis valide.
 *
 * Rien n'est ecrit avant la derniere etape. C'est ce qui permet d'afficher
 * l'apercu et le rapport d'erreurs *avant* de s'engager, et donc d'annuler
 * sans consequence.
 *
 * La progression est materialisee par un fil d'etapes : sur un formulaire qui
 * se deplie au fur et a mesure, savoir combien il reste a faire evite
 * l'abandon.
 */
@Component({
  selector: 'app-import',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatFormFieldModule,
    MatSelectModule,
    PageHeaderComponent,
    IconComponent,
    EmptyStateComponent,
  ],
  templateUrl: './import.component.html',
  styleUrl: './import.component.scss',
})
export class ImportComponent {
  private readonly fb = inject(FormBuilder);
  private readonly imports = inject(ImportsService);
  private readonly toast = inject(ToastService);

  protected readonly fields: FieldDef[] = [
    {
      key: 'subject',
      label: 'Sujet',
      hint: "L'intitulé de la demande. Seul champ indispensable.",
      required: true,
      candidates: ['subject', 'sujet', 'title', 'titre', 'objet'],
    },
    {
      key: 'body',
      label: 'Message',
      hint: 'Le texte complet écrit par le client.',
      required: false,
      candidates: ['body', 'corps', 'message', 'description', 'content', 'texte'],
    },
    {
      key: 'customerEmail',
      label: 'E-mail du client',
      hint: 'Permet de regrouper les demandes d’un même client.',
      required: false,
      candidates: ['customer_email', 'email', 'mail', 'e-mail'],
    },
    {
      key: 'externalRef',
      label: 'Référence',
      hint: 'Identifiant de votre outil actuel. Évite les doublons en cas de ré-import.',
      required: false,
      candidates: ['external_ref', 'ref', 'reference', 'id', 'ticket_id'],
    },
    {
      key: 'createdAt',
      label: 'Date de réception',
      hint: 'Sans cette colonne, la date d’import fait foi.',
      required: false,
      candidates: ['created_at', 'date', 'created', 'date_creation'],
    },
    {
      key: 'language',
      label: 'Langue',
      hint: 'Détectée automatiquement si la colonne est absente.',
      required: false,
      candidates: ['language', 'lang', 'langue'],
    },
  ];

  protected readonly preview = signal<ImportPreview | null>(null);
  protected readonly uploading = signal(false);
  protected readonly confirming = signal(false);
  protected readonly filename = signal<string | null>(null);
  protected readonly dragging = signal(false);
  protected readonly showAllErrors = signal(false);

  protected readonly busy = computed(() => this.uploading() || this.confirming());

  /** Etape courante, pour le fil de progression. */
  protected readonly step = computed(() => {
    if (!this.preview()) {
      return 1;
    }
    return this.mappingForm.valid ? 3 : 2;
  });

  protected readonly steps = [
    { n: 1, label: 'Déposer le fichier' },
    { n: 2, label: 'Vérifier et associer' },
    { n: 3, label: 'Confirmer' },
  ];

  /** Colonnes du fichier automatiquement reconnues, pour le retour visuel. */
  protected readonly autoMapped = signal<Set<string>>(new Set());

  protected readonly errorSample = computed(() => {
    const errors = this.preview()?.errors ?? [];
    return this.showAllErrors() ? errors : errors.slice(0, 5);
  });

  protected readonly mappingForm = this.fb.group({
    externalRef: this.fb.control<string | null>(null),
    customerEmail: this.fb.control<string | null>(null),
    subject: this.fb.control<string | null>(null, Validators.required),
    body: this.fb.control<string | null>(null),
    createdAt: this.fb.control<string | null>(null),
    language: this.fb.control<string | null>(null),
  });

  /* --- Dépôt du fichier --------------------------------------------------- */

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
    // Remis a zero pour permettre de re-selectionner le meme fichier apres
    // une correction : sans cela, le navigateur n'emet pas de nouvel evenement.
    input.value = '';
  }

  /* --- Actions ------------------------------------------------------------ */

  protected confirm(): void {
    const current = this.preview();
    if (!current || this.mappingForm.invalid) {
      this.mappingForm.markAllAsTouched();
      this.toast.error('Indiquez au moins la colonne qui contient le sujet.');
      return;
    }

    const raw = this.mappingForm.getRawValue();
    const mapping: Record<string, string> = {};
    for (const key of Object.keys(raw) as TicketField[]) {
      const value = raw[key];
      if (value) {
        mapping[key] = value;
      }
    }

    this.confirming.set(true);
    this.imports.confirm(current.importId, mapping).subscribe({
      next: (res) => {
        this.confirming.set(false);
        this.toast.success(
          res.skipped > 0
            ? `${res.inserted} tickets créés, ${res.skipped} déjà connus et ignorés.`
            : `${res.inserted} tickets créés.`,
        );
        this.reset();
      },
      error: (err: HttpErrorResponse) => {
        this.confirming.set(false);
        this.toast.error(
          err.status === 409
            ? 'Ce fichier a déjà été importé.'
            : "L'import n'a pas pu être finalisé.",
        );
      },
    });
  }

  protected reset(): void {
    this.preview.set(null);
    this.filename.set(null);
    this.showAllErrors.set(false);
    this.autoMapped.set(new Set());
    this.mappingForm.reset();
  }

  protected toggleErrors(): void {
    this.showAllErrors.update((v) => !v);
  }

  protected formatSize(bytes: number): string {
    if (bytes < 1024) {
      return `${bytes} o`;
    }
    if (bytes < 1024 * 1024) {
      return `${(bytes / 1024).toFixed(0)} Ko`;
    }
    return `${(bytes / (1024 * 1024)).toFixed(1)} Mo`;
  }

  /* --- Interne ------------------------------------------------------------ */

  private upload(file: File): void {
    this.filename.set(`${file.name} · ${this.formatSize(file.size)}`);
    this.uploading.set(true);
    this.preview.set(null);
    this.mappingForm.reset();

    this.imports.upload(file).subscribe({
      next: (res) => {
        this.preview.set(res);
        this.autoMap(res.headers);
        this.uploading.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.uploading.set(false);
        this.filename.set(null);
        this.toast.error(this.uploadError(err));
      },
    });
  }

  /**
   * Pre-association par heuristique sur le nom des colonnes.
   *
   * L'utilisateur arrive presque toujours sur un formulaire deja rempli
   * correctement : il verifie au lieu de saisir. Le gain n'est pas seulement
   * du temps, c'est le taux d'erreur — associer six colonnes a la main sur un
   * fichier inconnu se rate souvent.
   */
  private autoMap(headers: string[]): void {
    const lower = headers.map((h) => h.toLowerCase().trim());
    const matched = new Set<string>();

    for (const field of this.fields) {
      const index = lower.findIndex((h) => field.candidates.includes(h));
      if (index >= 0) {
        this.mappingForm.controls[field.key].setValue(headers[index]);
        matched.add(field.key);
      }
    }
    this.autoMapped.set(matched);
  }

  private uploadError(err: HttpErrorResponse): string {
    switch (err.status) {
      case 415:
        return 'Format non pris en charge. Utilisez un fichier CSV, XLSX, JSON ou TXT.';
      case 413:
        return 'Fichier trop volumineux (50 Mo maximum).';
      case 400:
        return 'Le fichier n’a pas pu être lu. Vérifiez qu’il n’est pas corrompu.';
      default:
        return "L'envoi du fichier a échoué.";
    }
  }
}
