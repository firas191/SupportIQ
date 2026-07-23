import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ImportsService } from '../../core/imports/imports.service';
import { ImportPreview, TicketField } from '../../core/models/import.models';

interface FieldDef {
  key: TicketField;
  label: string;
  required: boolean;
  candidates: string[];
}

@Component({
  selector: 'app-import',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatSelectModule,
    MatProgressBarModule,
  ],
  templateUrl: './import.component.html',
  styleUrl: './import.component.scss',
})
export class ImportComponent {
  private readonly fb = inject(FormBuilder);
  private readonly imports = inject(ImportsService);
  private readonly snackBar = inject(MatSnackBar);

  readonly fields: FieldDef[] = [
    { key: 'externalRef', label: 'Reference externe', required: false, candidates: ['external_ref', 'ref', 'reference', 'id'] },
    { key: 'customerEmail', label: 'Email client', required: false, candidates: ['customer_email', 'email', 'mail'] },
    { key: 'subject', label: 'Sujet', required: true, candidates: ['subject', 'sujet', 'title', 'titre'] },
    { key: 'body', label: 'Corps', required: false, candidates: ['body', 'corps', 'message', 'description', 'content'] },
    { key: 'createdAt', label: 'Date de creation', required: false, candidates: ['created_at', 'date', 'created'] },
    { key: 'language', label: 'Langue', required: false, candidates: ['language', 'lang', 'langue'] },
  ];

  readonly preview = signal<ImportPreview | null>(null);
  readonly loading = signal(false);
  readonly confirming = signal(false);
  readonly filename = signal<string | null>(null);
  readonly busy = computed(() => this.loading() || this.confirming());

  readonly mappingForm = this.fb.group({
    externalRef: this.fb.control<string | null>(null),
    customerEmail: this.fb.control<string | null>(null),
    subject: this.fb.control<string | null>(null, Validators.required),
    body: this.fb.control<string | null>(null),
    createdAt: this.fb.control<string | null>(null),
    language: this.fb.control<string | null>(null),
  });

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) {
      return;
    }
    this.filename.set(file.name);
    this.loading.set(true);
    this.preview.set(null);
    this.mappingForm.reset();
    this.imports.upload(file).subscribe({
      next: (res) => {
        this.preview.set(res);
        this.autoMap(res.headers);
        this.loading.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.snackBar.open(this.uploadError(err), 'OK', { duration: 5000 });
        this.loading.set(false);
      },
    });
    input.value = ''; // permet de re-selectionner le meme fichier
  }

  confirm(): void {
    const current = this.preview();
    if (!current || this.mappingForm.invalid) {
      this.mappingForm.markAllAsTouched();
      return;
    }
    const raw = this.mappingForm.getRawValue();
    const mapping: Record<string, string> = {};
    (Object.keys(raw) as TicketField[]).forEach((key) => {
      const value = raw[key];
      if (value) {
        mapping[key] = value;
      }
    });

    this.confirming.set(true);
    this.imports.confirm(current.importId, mapping).subscribe({
      next: (res) => {
        this.snackBar.open(`${res.inserted} tickets crees, ${res.skipped} ignores.`, 'OK', { duration: 5000 });
        this.confirming.set(false);
        this.reset();
      },
      error: (err: HttpErrorResponse) => {
        const msg = err.status === 409 ? 'Import deja confirme.' : 'Confirmation impossible.';
        this.snackBar.open(msg, 'OK', { duration: 5000 });
        this.confirming.set(false);
      },
    });
  }

  reset(): void {
    this.preview.set(null);
    this.filename.set(null);
    this.mappingForm.reset();
  }

  private autoMap(headers: string[]): void {
    const lower = headers.map((h) => h.toLowerCase());
    for (const field of this.fields) {
      const idx = lower.findIndex((h) => field.candidates.includes(h));
      if (idx >= 0) {
        this.mappingForm.controls[field.key].setValue(headers[idx]);
      }
    }
  }

  private uploadError(err: HttpErrorResponse): string {
    if (err.status === 415) {
      return 'Format de fichier non supporte.';
    }
    if (err.status === 413) {
      return 'Fichier trop volumineux.';
    }
    if (err.status === 400) {
      return 'Fichier illisible.';
    }
    return 'Echec de l\'upload.';
  }
}
