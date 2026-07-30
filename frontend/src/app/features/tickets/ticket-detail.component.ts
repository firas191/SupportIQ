import { DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TicketDetail } from '../../core/models/ticket.models';
import { TicketsService } from '../../core/tickets/tickets.service';

/**
 * Fiche ticket (S4-J4) : contenu, analyse IA avec badge de confiance, mots-cles, tickets
 * similaires, et la **boucle human-in-the-loop** (correction des predictions + fusion de doublons).
 */
@Component({
  selector: 'app-ticket-detail',
  standalone: true,
  imports: [
    DatePipe,
    FormsModule,
    RouterLink,
    MatCardModule,
    MatChipsModule,
    MatIconModule,
    MatButtonModule,
    MatSelectModule,
    MatFormFieldModule,
    MatProgressBarModule,
  ],
  templateUrl: './ticket-detail.component.html',
  styleUrl: './ticket-detail.component.scss',
})
export class TicketDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly tickets = inject(TicketsService);
  private readonly snackBar = inject(MatSnackBar);

  readonly ticket = signal<TicketDetail | null>(null);
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly error = signal<string | null>(null);

  readonly categories = ['TECHNIQUE', 'FACTURATION', 'COMPTE', 'RECLAMATION', 'DEMANDE'];
  readonly priorities = ['LOW', 'MEDIUM', 'HIGH'];
  readonly sentiments = ['NEG', 'NEU', 'POS'];

  /** Confiance en pourcentage + niveau (pour colorier le badge). */
  readonly confidencePct = computed(() => {
    const c = this.ticket()?.analysis?.confidence;
    return c == null ? null : Math.round(c * 100);
  });

  readonly confidenceLevel = computed<'high' | 'medium' | 'low' | null>(() => {
    const pct = this.confidencePct();
    if (pct == null) {
      return null;
    }
    return pct >= 80 ? 'high' : pct >= 50 ? 'medium' : 'low';
  });

  ngOnInit(): void {
    const id = Number(this.route.snapshot.paramMap.get('id'));
    if (!Number.isFinite(id)) {
      this.error.set('Identifiant de ticket invalide.');
      return;
    }
    this.load(id);
  }

  /** Correction d'un champ : le backend trace l'annotation puis renvoie la fiche a jour. */
  correct(field: 'category' | 'priority' | 'sentiment', value: string): void {
    const current = this.ticket();
    if (!current || !value) {
      return;
    }
    this.saving.set(true);
    this.tickets.annotate(current.id, field, value).subscribe({
      next: (t) => {
        this.ticket.set(t);
        this.saving.set(false);
        this.snackBar.open(`Correction enregistrée (${field} → ${value}).`, 'OK', { duration: 4000 });
      },
      error: (err) => {
        this.saving.set(false);
        const msg = err.status === 409
          ? "Ce ticket n'a pas encore d'analyse à corriger."
          : 'Correction impossible.';
        this.snackBar.open(msg, 'OK', { duration: 5000 });
      },
    });
  }

  /** Fusionne le ticket courant dans un ticket similaire (suggestion de doublon). */
  mergeInto(targetId: number): void {
    const current = this.ticket();
    if (!current) {
      return;
    }
    this.saving.set(true);
    this.tickets.merge(current.id, targetId).subscribe({
      next: (t) => {
        this.ticket.set(t);
        this.saving.set(false);
        this.snackBar.open(`Ticket fusionné dans #${targetId}.`, 'OK', { duration: 4000 });
      },
      error: (err) => {
        this.saving.set(false);
        const msg = err.status === 409 ? 'Ce ticket est déjà fusionné.' : 'Fusion impossible.';
        this.snackBar.open(msg, 'OK', { duration: 5000 });
      },
    });
  }

  open(id: number): void {
    this.router.navigate(['/tickets', id]).then(() => this.load(id));
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
        this.error.set(err.status === 404 ? 'Ticket introuvable.' : 'Chargement impossible.');
        this.loading.set(false);
      },
    });
  }
}
