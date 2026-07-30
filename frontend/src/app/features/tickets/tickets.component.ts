import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { debounceTime, distinctUntilChanged } from 'rxjs';
import { RealtimeService } from '../../core/realtime/realtime.service';
import {
  TicketCategory,
  TicketPriority,
  TicketSentiment,
  TicketSource,
  TicketStatus,
  TicketSummary,
} from '../../core/models/ticket.models';
import { TicketsService } from '../../core/tickets/tickets.service';
import {
  CATEGORY_LABELS,
  PRIORITY_LABELS,
  SENTIMENT_LABELS,
  SOURCE_LABELS,
  STATUS_LABELS,
  textOf,
} from '../../shared/labels';
import { AbsoluteTimePipe, RelativeTimePipe } from '../../shared/pipes/relative-time.pipe';
import { BadgeComponent } from '../../shared/ui/badge.component';
import { EmptyStateComponent } from '../../shared/ui/empty-state.component';
import { IconComponent } from '../../shared/ui/icon.component';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import { SkeletonComponent } from '../../shared/ui/skeleton.component';

/** Cle de filtre a valeur unique. */
type FilterKey = 'status' | 'source' | 'language' | 'category' | 'priority' | 'sentiment';

interface Option {
  value: string;
  label: string;
}

/** Colonnes sur lesquelles le tri serveur est autorise (liste blanche backend). */
const SORTABLE = ['createdAt', 'subject', 'status', 'source', 'language'] as const;
type SortField = (typeof SORTABLE)[number];

/**
 * File de tickets — l'ecran ou un agent passe sa journee.
 *
 * Trois problemes d'architecture d'information corriges par rapport a la
 * version precedente :
 *
 * 1. **Les colonnes ne montraient pas ce qui fait la valeur du produit.** La
 *    table affichait date, origine, sujet, statut, langue, client — mais ni la
 *    priorite, ni la categorie, ni l'humeur du client. On pouvait filtrer sur
 *    ces champs sans jamais les voir. Ils sont desormais les premieres
 *    colonnes : le tri visuel se fait maintenant a l'oeil, sans filtrer.
 *
 * 2. **Sept listes deroulantes alignees.** Elles occupaient deux rangs et
 *    demandaient deux clics par valeur. Le statut, de loin le filtre le plus
 *    utilise, devient un jeu d'onglets — visible en permanence, un clic. Les
 *    six autres passent dans un panneau, en pastilles a bascule : toutes les
 *    valeurs possibles sont visibles d'un coup, et selectionner coute un clic
 *    au lieu de deux.
 *
 * 3. **Etat de recherche invisible.** Apres deux ou trois filtres, on ne
 *    savait plus ce qui etait actif. Les pastilles de filtre actif, retirables
 *    a l'unite, rendent l'etat lisible et reversible.
 *
 * Les filtres sont aussi ecrits dans l'URL : une recherche devient partageable
 * et survit a un rechargement de page.
 */
@Component({
  selector: 'app-tickets',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    RelativeTimePipe,
    AbsoluteTimePipe,
    PageHeaderComponent,
    BadgeComponent,
    IconComponent,
    EmptyStateComponent,
    SkeletonComponent,
  ],
  templateUrl: './tickets.component.html',
  styleUrl: './tickets.component.scss',
})
export class TicketsComponent implements OnInit {
  private readonly tickets = inject(TicketsService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly realtime = inject(RealtimeService);

  /* --- Etat --------------------------------------------------------------- */

  protected readonly rows = signal<TicketSummary[]>([]);
  protected readonly total = signal(0);
  /** Premier chargement : squelette. Les suivants : fine barre de progression. */
  protected readonly firstLoad = signal(true);
  protected readonly loading = signal(false);
  protected readonly failed = signal(false);

  protected readonly pageIndex = signal(0);
  protected readonly pageSize = signal(25);
  protected readonly sortField = signal<SortField>('createdAt');
  protected readonly sortDir = signal<'asc' | 'desc'>('desc');

  protected readonly search = new FormControl('', { nonNullable: true });
  /**
   * Miroir en signal de la saisie. Un FormControl n'est pas un signal : sans
   * ce miroir, les `computed` qui dependent du texte (pastilles, mode
   * pertinence) ne seraient jamais recalcules. Il est mis a jour sans
   * temporisation — l'affichage de l'etat doit suivre la frappe, meme si la
   * requete reseau, elle, attend.
   */
  protected readonly queryText = signal('');
  protected readonly filters = signal<Record<FilterKey, string>>({
    status: '',
    source: '',
    language: '',
    category: '',
    priority: '',
    sentiment: '',
  });

  protected readonly filterPanelOpen = signal(false);
  protected readonly pendingCount = this.realtime.newTickets;

  /* --- Options d'interface ------------------------------------------------ */

  /** Onglets de statut : le filtre le plus frequent, donc toujours visible. */
  protected readonly statusTabs: Option[] = [
    { value: '', label: 'Tous' },
    { value: 'NEW', label: textOf(STATUS_LABELS, 'NEW') },
    { value: 'IN_PROGRESS', label: textOf(STATUS_LABELS, 'IN_PROGRESS') },
    { value: 'RESOLVED', label: textOf(STATUS_LABELS, 'RESOLVED') },
  ];

  protected readonly filterGroups: { key: FilterKey; title: string; options: Option[] }[] = [
    { key: 'priority', title: 'Priorite', options: this.toOptions(PRIORITY_LABELS, ['HIGH', 'MEDIUM', 'LOW']) },
    {
      key: 'category',
      title: 'Categorie',
      options: this.toOptions(CATEGORY_LABELS, ['TECHNIQUE', 'FACTURATION', 'COMPTE', 'RECLAMATION', 'DEMANDE']),
    },
    { key: 'sentiment', title: 'Humeur du client', options: this.toOptions(SENTIMENT_LABELS, ['NEG', 'NEU', 'POS']) },
    { key: 'source', title: 'Origine', options: this.toOptions(SOURCE_LABELS, ['WEBHOOK', 'FILE', 'EMAIL', 'MANUAL']) },
    { key: 'language', title: 'Langue', options: [{ value: 'fr', label: 'Francais' }, { value: 'en', label: 'Anglais' }] },
  ];

  protected readonly skeletonRows = Array.from({ length: 8 });



  /* --- Valeurs derivees --------------------------------------------------- */

  /** Nombre de filtres actifs, hors recherche texte : pastille sur le bouton. */
  protected readonly activeFilterCount = computed(
    () => Object.values(this.filters()).filter(Boolean).length,
  );

  protected readonly activeChips = computed(() => {
    const current = this.filters();
    const chips: { key: FilterKey | 'q'; label: string }[] = [];

    const text = this.queryText().trim();
    if (text) {
      chips.push({ key: 'q', label: `« ${text} »` });
    }
    for (const group of this.filterGroups) {
      const value = current[group.key];
      if (value) {
        const option = group.options.find((o) => o.value === value);
        chips.push({ key: group.key, label: `${group.title} : ${option?.label ?? value}` });
      }
    }
    if (current.status) {
      chips.push({ key: 'status', label: `Statut : ${textOf(STATUS_LABELS, current.status)}` });
    }
    return chips;
  });

  protected readonly hasAnyFilter = computed(() => this.activeChips().length > 0);

  /** Une recherche texte force le tri par pertinence cote serveur. */
  protected readonly relevanceMode = computed(() => !!this.queryText().trim());

  protected readonly rangeLabel = computed(() => {
    const total = this.total();
    if (total === 0) {
      return '0';
    }
    const from = this.pageIndex() * this.pageSize() + 1;
    const to = Math.min(from + this.pageSize() - 1, total);
    return `${from}–${to} sur ${total.toLocaleString('fr-FR')}`;
  });

  protected readonly lastPage = computed(() =>
    Math.max(0, Math.ceil(this.total() / this.pageSize()) - 1),
  );

  /* --- Cycle de vie ------------------------------------------------------- */

  constructor() {
    // 300 ms : au-dessous on tire une requete par frappe, au-dessus la
    // recherche parait poussive. distinctUntilChanged evite la requete
    // inutile quand la valeur revient a l'identique (copier-coller, undo).
    this.search.valueChanges.pipe(takeUntilDestroyed()).subscribe((value) => this.queryText.set(value));

    this.search.valueChanges
      .pipe(debounceTime(300), distinctUntilChanged(), takeUntilDestroyed())
      .subscribe(() => {
        this.pageIndex.set(0);
        this.load();
      });
  }

  ngOnInit(): void {
    this.restoreFromUrl();
    this.load();
  }

  /* --- Interactions ------------------------------------------------------- */

  protected setStatus(value: string): void {
    this.patchFilter('status', value);
  }

  protected toggleFilter(key: FilterKey, value: string): void {
    // Re-cliquer la valeur active la retire : la pastille est une bascule,
    // pas un bouton radio. Un aller-retour ne coute jamais deux clics.
    const next = this.filters()[key] === value ? '' : value;
    this.patchFilter(key, next);
  }

  protected removeChip(key: FilterKey | 'q'): void {
    if (key === 'q') {
      this.search.setValue('');
      return;
    }
    this.patchFilter(key, '');
  }

  protected clearAll(): void {
    this.filters.set({ status: '', source: '', language: '', category: '', priority: '', sentiment: '' });
    this.pageIndex.set(0);
    // emitEvent: false — on relance nous-memes, sinon le debounce ajouterait
    // une seconde requete 300 ms plus tard.
    this.search.setValue('', { emitEvent: false });
    this.queryText.set('');
    this.load();
  }

  protected sortBy(field: string): void {
    if (!SORTABLE.includes(field as SortField)) {
      return;
    }
    const key = field as SortField;
    if (this.sortField() === key) {
      this.sortDir.set(this.sortDir() === 'asc' ? 'desc' : 'asc');
    } else {
      this.sortField.set(key);
      // Une date part du plus recent, un texte de A vers Z : c'est ce que
      // l'on attend implicitement d'un premier clic sur chaque type.
      this.sortDir.set(key === 'createdAt' ? 'desc' : 'asc');
    }
    this.pageIndex.set(0);
    this.load();
  }

  protected ariaSort(field: string): 'ascending' | 'descending' | 'none' {
    if (this.sortField() !== field) {
      return 'none';
    }
    return this.sortDir() === 'asc' ? 'ascending' : 'descending';
  }

  protected goToPage(index: number): void {
    const clamped = Math.max(0, Math.min(index, this.lastPage()));
    if (clamped === this.pageIndex()) {
      return;
    }
    this.pageIndex.set(clamped);
    this.load();
  }

  protected changePageSize(event: Event): void {
    this.pageSize.set(Number((event.target as HTMLSelectElement).value));
    this.pageIndex.set(0);
    this.load();
  }

  protected openDetail(id: number): void {
    this.router.navigate(['/tickets', id]);
  }

  /** Ligne au clavier : Entree ou Espace ouvrent la fiche, comme un clic. */
  protected onRowKeydown(event: KeyboardEvent, id: number): void {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      this.openDetail(id);
    }
  }

  protected refreshFromRealtime(): void {
    this.pageIndex.set(0);
    this.load();
  }

  protected toggleFilterPanel(): void {
    this.filterPanelOpen.update((v) => !v);
  }

  /* --- Chargement --------------------------------------------------------- */

  private patchFilter(key: FilterKey, value: string): void {
    this.filters.update((f) => ({ ...f, [key]: value }));
    this.pageIndex.set(0);
    this.load();
  }

  private load(): void {
    const f = this.filters();
    const q = this.search.value.trim() || undefined;

    this.loading.set(true);
    this.failed.set(false);
    this.syncUrl(q, f);

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
        sort: this.sortField(),
        direction: this.sortDir(),
      })
      .subscribe({
        next: (page) => {
          this.rows.set(page.content);
          this.total.set(page.totalElements);
          this.loading.set(false);
          this.firstLoad.set(false);
          // Les donnees affichees sont a jour : le compteur temps reel repart de zero.
          this.realtime.acknowledge();
        },
        error: () => {
          this.loading.set(false);
          this.firstLoad.set(false);
          this.failed.set(true);
        },
      });
  }

  /**
   * Ecrit l'etat de recherche dans l'URL (sans empiler d'entree d'historique).
   * Consequence concrete : un agent peut envoyer « les urgences non traitees »
   * a un collegue par simple copier-coller de l'adresse.
   */
  private syncUrl(q: string | undefined, f: Record<FilterKey, string>): void {
    const params: Record<string, string> = {};
    if (q) {
      params['q'] = q;
    }
    for (const [key, value] of Object.entries(f)) {
      if (value) {
        params[key] = value;
      }
    }
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: params,
      replaceUrl: true,
    });
  }

  private restoreFromUrl(): void {
    const params = this.route.snapshot.queryParamMap;
    const q = params.get('q');
    if (q) {
      this.search.setValue(q, { emitEvent: false });
      this.queryText.set(q);
    }
    const keys: FilterKey[] = ['status', 'source', 'language', 'category', 'priority', 'sentiment'];
    const restored = { ...this.filters() };
    for (const key of keys) {
      const value = params.get(key);
      if (value) {
        restored[key] = value;
      }
    }
    this.filters.set(restored);
  }

  private toOptions(table: Record<string, { label: string }>, order: string[]): Option[] {
    return order.map((value) => ({ value, label: table[value]?.label ?? value }));
  }
}
