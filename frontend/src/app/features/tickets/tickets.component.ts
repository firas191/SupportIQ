import { DatePipe, LowerCasePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
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
import { debounceTime } from 'rxjs';
import { TicketsService } from '../../core/tickets/tickets.service';
import { TicketSource, TicketStatus, TicketSummary } from '../../core/models/ticket.models';

/**
 * Liste des tickets : table Material paginee/triee/filtree cote serveur (signals, ADR-0002).
 * La table ne charge jamais tout le jeu de donnees ; chaque changement de filtre/tri/page recharge.
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

  readonly rows = signal<TicketSummary[]>([]);
  readonly total = signal(0);
  readonly loading = signal(false);
  readonly pageIndex = signal(0);
  readonly pageSize = signal(20);

  private sortField = 'createdAt';
  private sortDirection: 'asc' | 'desc' = 'desc';

  readonly displayedColumns = ['createdAt', 'source', 'subject', 'status', 'language', 'customerEmail'];
  readonly statuses: TicketStatus[] = ['NEW', 'ANALYZED', 'IN_PROGRESS', 'RESOLVED', 'MERGED'];
  readonly sources: TicketSource[] = ['FILE', 'WEBHOOK', 'EMAIL', 'MANUAL'];

  readonly filterForm = this.fb.group({
    q: [''],
    status: [''],
    source: [''],
    language: [''],
  });

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

  private load(): void {
    const f = this.filterForm.getRawValue();
    this.loading.set(true);
    this.tickets
      .list({
        q: f.q || undefined,
        status: (f.status as TicketStatus) || undefined,
        source: (f.source as TicketSource) || undefined,
        language: f.language || undefined,
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
        },
        error: () => this.loading.set(false),
      });
  }
}
