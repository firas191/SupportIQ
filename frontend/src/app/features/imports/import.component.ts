import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { I18nService } from '../../core/i18n/i18n.service';
import { TranslatePipe } from '../../core/i18n/t.pipe';
import { TranslationKey } from '../../core/i18n/translations.fr';
import { ImportsService } from '../../core/imports/imports.service';
import { ImportPreview, TicketField } from '../../core/models/import.models';
import { ToastService } from '../../core/ui/toast.service';
import { IconComponent } from '../../shared/ui/icon.component';
import { IllustrationComponent } from '../../shared/ui/illustration.component';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';

interface FieldDef {
  key: TicketField;
  labelKey: TranslationKey;
  hintKey: TranslationKey;
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
    TranslatePipe,
    PageHeaderComponent,
    IconComponent,
    IllustrationComponent,
  ],
  templateUrl: './import.component.html',
  styleUrl: './import.component.scss',
})
export class ImportComponent {
  private readonly fb = inject(FormBuilder);
  private readonly imports = inject(ImportsService);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);

  protected readonly fields: FieldDef[] = [
    {
      key: 'subject',
      labelKey: 'imports.fieldSubject',
      hintKey: 'imports.fieldSubjectHint',
      required: true,
      candidates: ['subject', 'sujet', 'title', 'titre', 'objet'],
    },
    {
      key: 'body',
      labelKey: 'imports.fieldBody',
      hintKey: 'imports.fieldBodyHint',
      required: false,
      candidates: ['body', 'corps', 'message', 'description', 'content', 'texte'],
    },
    {
      key: 'customerEmail',
      labelKey: 'imports.fieldEmail',
      hintKey: 'imports.fieldEmailHint',
      required: false,
      candidates: ['customer_email', 'email', 'mail', 'e-mail'],
    },
    {
      key: 'externalRef',
      labelKey: 'imports.fieldRef',
      hintKey: 'imports.fieldRefHint',
      required: false,
      candidates: ['external_ref', 'ref', 'reference', 'id', 'ticket_id'],
    },
    {
      key: 'createdAt',
      labelKey: 'imports.fieldDate',
      hintKey: 'imports.fieldDateHint',
      required: false,
      candidates: ['created_at', 'date', 'created', 'date_creation'],
    },
    {
      key: 'language',
      labelKey: 'imports.fieldLanguage',
      hintKey: 'imports.fieldLanguageHint',
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

  protected readonly steps: { n: number; labelKey: TranslationKey }[] = [
    { n: 1, labelKey: 'imports.step1' },
    { n: 2, labelKey: 'imports.step2' },
    { n: 3, labelKey: 'imports.step3' },
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
      this.toast.error(this.i18n.t('imports.needSubject'));
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
            ? this.i18n.t('imports.createdWithSkipped', {
                inserted: res.inserted,
                skipped: res.skipped,
              })
            : this.i18n.t('imports.created', { n: res.inserted }),
        );
        this.reset();
      },
      error: (err: HttpErrorResponse) => {
        this.confirming.set(false);
        this.toast.error(
          this.i18n.t(err.status === 409 ? 'imports.alreadyImported' : 'imports.confirmFailed'),
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

  /** Taille lisible. Les unites SI restent identiques dans les deux langues,
      seul le separateur decimal suit la locale. */
  protected formatSize(bytes: number): string {
    if (bytes < 1024) {
      return `${bytes} B`;
    }
    if (bytes < 1024 * 1024) {
      return `${(bytes / 1024).toFixed(0)} kB`;
    }
    return `${(bytes / (1024 * 1024)).toLocaleString(this.i18n.locale(), {
      maximumFractionDigits: 1,
    })} MB`;
  }

  /** Nombre formate selon la langue (separateur de milliers). */
  protected num(value: number): string {
    return value.toLocaleString(this.i18n.locale());
  }

  protected errorsLabel(n: number): string {
    return this.i18n.plural(n, 'imports.rowsInError', 'imports.rowsInErrorPlural');
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
    const key: TranslationKey =
      err.status === 415
        ? 'imports.badFormat'
        : err.status === 413
          ? 'imports.tooLarge'
          : err.status === 400
            ? 'imports.unreadable'
            : 'imports.uploadFailed';
    return this.i18n.t(key);
  }
}
