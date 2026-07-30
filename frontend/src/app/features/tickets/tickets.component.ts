import { DatePipe, LowerCasePipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatTableModule } from '@angular/material/table';
import { Router } from '@angular/router';
import { debounceTime } from 'rxjs';
import {
  TicketCategory,
  TicketPriority,
  TicketSentiment,
  TicketSource,
  TicketStatus,
  TicketSummary,
} from '../../core/models/ticket.models';
import { RealtimeService } from '../../core/realtime/realtime.service';
import { TicketsService } from '../../core/tickets/tickets.service';

/** Un filtre actif, affiche sous forme de chip retirable. */
interface ActiveFilter {
  key: 'q' | 'status' | 'source' | 'language' | 'category' | 'priority' | 'sentiment';
  label: string;
}

/**
 * Liste et recherche de tickets : table Material paginee/triee/filtree cote serveur (signals).
 * S4-J3 : la saisie `q` declenche une recherche **full-text** (le backend trie alors par pertinence)
 * et les filtres actifs sont resumes en **chips retirables**.
 */
@Component({
  selector: 'app-tickets',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    DatePipe,
    LowerCasePipe,
    MatTableModule,
    MatSortModule,
    MatPaginatorModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatChipsModule,
    MatIconModule,
    MatProgressBarModule,
  ],
  templateUrl: './tickets.component.html',
  styleUrl: './tickets.component.scss',
})
export class TicketsComponent implements OnInit {
  private readonly tickets = inject(TicketsService);
  private readonly fb = inject(FormBuilder);
  private readonly router = inject(Router);
  private readonly realtime = inject(RealtimeService);

  /** Tickets arrives en temps reel depuis le dernier chargement (S4-J5). */
  readonly pendingCount = this.realtime.newTickets;

  readonly rows = signal<TicketSummary[]>([]);
  readonly total = signal(0);
  readonly loading = signal(false);
  readonly pageIndex = signal(0);
  readonly pageSize = signal(20);
  /** Vrai quand une recherche texte est active : le tri serveur passe alors en pertinence. */
  readonly relevanceMode = signal(false);

  private sortField = 'createdAt';
  private sortDirection: 'asc' | 'desc' = 'desc';

  readonly displayedColumns = ['createdAt', 'source', 'subject', 'status', 'language', 'customerEmail'];
  readonly statuses: TicketStatus[] = ['NEW', 'ANALYZED', 'IN_PROGRESS', 'RESOLVED', 'MERGED'];
  readonly sources: TicketSource[] = ['FILE', 'WEBHOOK', 'EMAIL', 'MANUAL'];
  readonly categories: TicketCategory[] =
    ['TECHNIQUE', 'FACTURATION', 'COMPTE', 'RECLAMATION', 'DEMANDE'];
  readonly priorities: TicketPriority[] = ['LOW', 'MEDIUM', 'HIGH'];
  readonly sentiments: TicketSentiment[] = ['NEG', 'NEU', 'POS'];

  readonly filterForm = this.fb.group({
    q: [''],
    status: [''],
    source: [''],
    language: [''],
    category: [''],
    priority: [''],
    sentiment: [''],
  });

  /** Resume des filtres actifs (pour les chips). Derive de l'etat du formulaire. */
  readonly activeFilters = signal<ActiveFilter[]>([]);

  readonly hasFilters = computed(() => this.activeFilters().length > 0);

  constructor() {
    // Un seul flux debounce pour texte + selects : reset a la page 0 puis rechargement.
    this.filterForm.valueChanges.pipe(debounceTime(300), takeUntilDestroyed()).subscribe(() => {
      this.pageIndex.set(0);
      this.load();
    });
  }

  ngOnInit(): void {
    this.load();
  }

  onPage(event: PageEvent): void {
    this.pageIndex.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
    this.load();
  }

  onSort(sort: Sort): void {
    this.sortField = sort.active || 'createdAt';
    this.sortDirection = (sort.direction || 'desc') as 'asc' | 'desc';
    this.pageIndex.set(0);
    this.load();
  }

  /** Recharge la liste et remet le compteur temps reel a zero. */
  refreshFromRealtime(): void {
    this.pageIndex.set(0);
    this.load();
  }

  /** Ouvre la fiche ticket (S4-J4). */
  openDetail(id: number): void {
    this.router.navigate(['/tickets', id]);
  }

  /** Retire un filtre depuis sa chip (le valueChanges relance la recherche). */
  removeFilter(key: ActiveFilter['key']): void {
    this.filterForm.get(key)?.setValue('');
  }

  clearAll(): void {
    this.filterForm.reset({
      q: '', status: '', source: '', language: '', category: '', priority: '', sentiment: '',
    });
  }

  private load(): void {
    const f = this.filterForm.getRawValue();
    const q = f.q?.trim() || undefined;

    this.relevanceMode.set(!!q);
    this.activeFilters.set(this.buildChips(f));
    this.loading.set(true);

    this.tickets
      .list({
        q,
        status: (f.status as TicketStatus) || undefined,
        source: (f.source as TicketSource) || undefined,
        language: f.language || undefined,
        category: (f.category as TicketCategory) || undefined,
        priority: (f.priority as TicketPriority) || undefined,
        sentiment: (f.sentiment as TicketSentiment) || undefined,
        page: this.pageIndex(),
        size: this.pageSize(),
        sort: this.sortField,
        direction: this.sortDirection,
      })
      .subscribe({
        next: (p) => {
          this.rows.set(p.content);
          this.total.set(p.totalElements);
          this.loading.set(false);
          this.realtime.acknowledge();  // les donnees sont a jour : compteur remis a zero
        },
        error: () => this.loading.set(false),
      });
  }

  private buildChips(f: Record<string, string | null>): ActiveFilter[] {
    const labels: Record<string, string> = {
      q: 'Recherche',
      status: 'Statut',
      source: 'Source',
      language: 'Langue',
      category: 'Catégorie',
      priority: 'Priorité',
      sentiment: 'Sentiment',
    };
    return (Object.keys(labels) as ActiveFilter['key'][])
      .filter((key) => !!f[key]?.trim())
      .map((key) => ({ key, label: `${labels[key]} : ${f[key]}` }));
  }
}
