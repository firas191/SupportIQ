import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ChartConfiguration } from 'chart.js';
import { I18nService } from '../../core/i18n/i18n.service';
import { TranslatePipe } from '../../core/i18n/t.pipe';
import { TranslationKey } from '../../core/i18n/translations.fr';
import { InsightService } from '../../core/insight/insight.service';
import { InsightAnswer, InsightValue } from '../../core/models/insight.models';
import { ThemeService } from '../../core/theme/theme.service';
import { ChartComponent } from '../../shared/chart/chart.component';
import { baseChartOptions, categoryColor, token } from '../../shared/chart/chart-theme';
import { domainValueKey, humaniseDomainValues } from '../../shared/labels';
import { IconComponent } from '../../shared/ui/icon.component';
import { IllustrationComponent } from '../../shared/ui/illustration.component';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';

/** Vues interrogeables, traduites en langage courant. */
const SOURCE_LABELS: Record<string, TranslationKey> = {
  v_tickets: 'insight.sourceTickets',
  v_daily_volume: 'insight.sourceDaily',
  v_category_trends: 'insight.sourceTrends',
  v_hourly_load: 'insight.sourceHourly',
  v_ticket_stats: 'insight.sourceStats',
  v_draft_activity: 'insight.sourceDrafts',
};

/** Categories du triage : seules etiquettes a porter une couleur de sens. */
const CATEGORIES = new Set([
  'TECHNIQUE',
  'FACTURATION',
  'COMPTE',
  'RECLAMATION',
  'DEMANDE',
  'NON_ANALYSE',
]);

/**
 * Questions proposees au premier contact.
 *
 * Choisies parmi celles que la suite d'eval du S6-J2 valide de facon stable.
 * C'est un choix **produit**, pas une mise en scene : une suggestion qui echoue
 * une fois sur deux apprendrait a l'utilisateur que l'outil ne marche pas. Le
 * chiffre de reussite, lui, se mesure ailleurs et sur des questions libres.
 */
const SUGGESTIONS: TranslationKey[] = [
  'insight.suggest1',
  'insight.suggest2',
  'insight.suggest3',
  'insight.suggest4',
];

/**
 * Un echange du fil. Objet d'interface, volontairement declare ici et non dans
 * `core/models` : ce dossier ne decrit que ce qui traverse le reseau.
 *
 * `pending` et `errorKey` vivent par echange et non dans un etat global — deux
 * questions peuvent etre en vol, et un echec sur l'une ne doit pas effacer la
 * reponse de l'autre.
 */
interface Exchange {
  id: number;
  question: string;
  pending: boolean;
  answer: InsightAnswer | null;
  errorKey: TranslationKey | null;
}

interface ExchangeView extends Exchange {
  chart: ChartConfiguration | null;
  sources: string[];
  showSql: boolean;
  /** Synthese avec les valeurs brutes remplacees par leur libelle. */
  answerText: string;
}

/**
 * Chat Insight — un responsable pose une question, la plateforme repond (S6-J3).
 *
 * Trois partis pris portent cet ecran.
 *
 * 1. **La source est toujours visible, la requete toujours accessible.** Chaque
 *    reponse indique en clair ce qui a ete lu (« les tickets », « les volumes
 *    quotidiens ») et laisse ouvrir la requete exacte. Ce n'est pas de la
 *    transparence decorative : la mesure du S6-J2 a montre que l'agent repond
 *    parfois a une question *voisine* de celle posee. Aucune barriere technique
 *    ne detecte cela — montrer ce qui a ete lu, si.
 *
 * 2. **C'est un historique, pas une conversation.** Chaque question part seule :
 *    l'agent n'a aucune memoire d'echange. L'ecran ne simule donc ni
 *    interlocuteur ni « en train d'ecrire », et rien n'y suggere qu'une relance
 *    (« et le mois dernier ? ») fonctionnerait. Promettre un comportement
 *    inexistant coute plus cher que de ne pas l'offrir.
 *
 * 3. **Un resultat sans graphique n'est pas un echec.** Quand la forme du
 *    resultat ne s'y prete pas — une seule valeur, trop de categories — l'ecran
 *    dit pourquoi au lieu d'afficher un cadre vide, qui se lit comme une panne.
 *
 * Vocabulaire : « source », « resultat », « requete ». Ni vue, ni agent, ni
 * modele.
 */
@Component({
  selector: 'app-insight',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslatePipe,
    IconComponent,
    IllustrationComponent,
    PageHeaderComponent,
    ChartComponent,
  ],
  templateUrl: './insight.component.html',
  styleUrl: './insight.component.scss',
})
export class InsightComponent {
  private readonly insight = inject(InsightService);
  private readonly i18n = inject(I18nService);
  private readonly theme = inject(ThemeService);

  protected readonly suggestions = SUGGESTIONS;
  protected readonly draft = signal('');

  private readonly exchanges = signal<Exchange[]>([]);
  private readonly openSql = signal<ReadonlySet<number>>(new Set());
  private nextId = 1;

  protected readonly pending = computed(() => this.exchanges().some((e) => e.pending));
  protected readonly empty = computed(() => this.exchanges().length === 0);

  /**
   * Vue de chaque echange, graphique compris.
   *
   * Le `theme.theme()` en tete cree la dependance qui force le recalcul a la
   * bascule clair/sombre : Chart.js dessine dans un canvas, il ne connait ni la
   * cascade CSS ni les tokens, donc les couleurs doivent lui etre redonnees.
   */
  protected readonly items = computed<ExchangeView[]>(() => {
    this.theme.theme();
    const open = this.openSql();
    return this.exchanges().map((exchange) => ({
      ...exchange,
      chart: exchange.answer ? this.buildChart(exchange.answer) : null,
      sources: exchange.answer ? this.sourcesOf(exchange.answer.sql) : [],
      showSql: open.has(exchange.id),
      // Le modele cite les valeurs telles qu'il les lit (« le canal FILE ») : il
      // n'a pas connaissance du vocabulaire produit, et le lui enseigner
      // ajouterait un mode de defaillance pour une substitution que le code
      // fait sans se tromper.
      answerText: exchange.answer
        ? humaniseDomainValues(exchange.answer.answer, (key) => this.i18n.t(key))
        : '',
    }));
  });

  /* --- Actions ------------------------------------------------------------ */

  protected onInput(event: Event): void {
    this.draft.set((event.target as HTMLTextAreaElement).value);
  }

  /** Entree envoie, Maj+Entree passe a la ligne — convention d'un champ de saisie court. */
  protected onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.submit();
    }
  }

  protected submit(): void {
    const question = this.draft().trim();
    if (!question || this.pending()) {
      return;
    }
    this.draft.set('');
    this.ask(question);
  }

  protected useSuggestion(key: TranslationKey): void {
    if (!this.pending()) {
      this.ask(this.i18n.t(key));
    }
  }

  protected toggleSql(id: number): void {
    this.openSql.update((current) => {
      const next = new Set(current);
      if (!next.delete(id)) {
        next.add(id);
      }
      return next;
    });
  }

  /**
   * En-tete de colonne presentable.
   *
   * Les noms viennent des alias choisis par le modele (`nb_tickets`, `canal`) :
   * ils changent a chaque question, donc ils ne peuvent pas etre traduits. On
   * les rend simplement lisibles — souligne en espace, premiere lettre en
   * capitale — au lieu de les afficher en `SNAKE_CASE` hurlant.
   *
   * L'alternative aurait ete de demander des libelles au modele. C'est
   * exactement le genre de tache sans jugement qu'on lui refuse depuis le
   * S5-J3 : elle ajouterait un mode de defaillance pour un gain cosmetique.
   */
  protected header(name: string): string {
    const readable = name.replace(/_/g, ' ').trim();
    return readable.charAt(0).toUpperCase() + readable.slice(1);
  }

  protected cell(value: InsightValue): string {
    if (value === null) {
      return '—';
    }
    if (typeof value === 'boolean') {
      return this.i18n.t(value ? 'common.yes' : 'common.no');
    }
    if (typeof value === 'number') {
      return value.toLocaleString(this.i18n.locale(), { maximumFractionDigits: 2 });
    }
    // `FILE` devient « Import », `NEG` devient « Mecontent ». Les valeurs de ce
    // domaine sont globalement uniques, donc traduisibles sans connaitre leur
    // colonne — ce que cet ecran ignore par construction.
    const key = domainValueKey(value);
    return key ? this.i18n.t(key) : value;
  }

  /* --- Interne ------------------------------------------------------------ */

  private ask(question: string): void {
    const id = this.nextId++;
    this.exchanges.update((list) => [
      ...list,
      { id, question, pending: true, answer: null, errorKey: null },
    ]);

    this.insight.ask(question).subscribe({
      next: (answer) => this.settle(id, answer, null),
      error: (err) => this.settle(id, null, this.errorKey(err.status)),
    });
  }

  private settle(id: number, answer: InsightAnswer | null, errorKey: TranslationKey | null): void {
    this.exchanges.update((list) =>
      list.map((e) => (e.id === id ? { ...e, pending: false, answer, errorKey } : e)),
    );
  }

  private errorKey(status: number): TranslationKey {
    if (status === 422) {
      return 'insight.outOfScope';
    }
    if (status === 429) {
      return 'insight.tooMany';
    }
    if (status === 503) {
      return 'insight.unavailable';
    }
    return 'insight.failed';
  }

  /**
   * Sources lues, en langage courant.
   *
   * Detectees par simple presence du nom dans la requete : la liste est courte,
   * fermee, et le seul cout d'un faux positif serait de nommer une source de
   * trop. Analyser le SQL pour cela serait disproportionne.
   */
  private sourcesOf(sql: string): string[] {
    const lower = sql.toLowerCase();
    return Object.entries(SOURCE_LABELS)
      .filter(([view]) => lower.includes(view))
      .map(([, key]) => this.i18n.t(key));
  }

  /**
   * Configuration Chart.js a partir de la specification calculee cote serveur.
   *
   * Le **type** de graphique vient du serveur (deduit de la forme du resultat,
   * S6-J2) ; l'interface ne fait que l'habiller aux couleurs du theme. Decider
   * ici serait dupliquer une regle metier dans deux langages.
   */
  private buildChart(answer: InsightAnswer): ChartConfiguration | null {
    const spec = answer.chart;
    if (spec.type === 'none' || !spec.x || !spec.y) {
      return null;
    }
    const xIndex = answer.columns.indexOf(spec.x);
    const yIndex = answer.columns.indexOf(spec.y);
    if (xIndex < 0 || yIndex < 0) {
      return null;
    }

    // Les valeurs **brutes** servent a choisir les couleurs, les valeurs
    // **traduites** a afficher. Colorier depuis le libelle casserait des que la
    // traduction s'ecarte du jeton (« Réclamation » n'est pas « RECLAMATION »,
    // et en anglais rien ne correspondrait).
    const raw = answer.rows.map((row) => row[xIndex]);
    const labels = raw.map((value) => this.cell(value));
    const values = answer.rows.map((row) => Number(row[yIndex]) || 0);
    const accent = token('--accent');
    // Une categorie connue reprend sa couleur : le meme sujet garde la meme
    // teinte ici et sur le tableau de bord. Tout le reste (statuts, canaux,
    // dates) prend l'accent — inventer une couleur par valeur ferait croire a un
    // code couleur qui n'existe pas.
    const colors = raw.map((value) =>
      typeof value === 'string' && CATEGORIES.has(value) ? categoryColor(value) : accent,
    );

    if (spec.type === 'line') {
      return {
        type: 'line',
        data: {
          labels,
          datasets: [{
            label: spec.y,
            data: values,
            borderColor: accent,
            backgroundColor: this.alpha(accent, 0.14),
            fill: true,
            tension: 0.35,
            borderWidth: 2,
            pointRadius: 0,
            pointHoverRadius: 4,
          }],
        },
        options: baseChartOptions(),
      };
    }

    return {
      type: 'bar',
      data: {
        labels,
        datasets: [{
          label: spec.y,
          data: values,
          backgroundColor: colors,
          borderRadius: 6,
          maxBarThickness: 42,
        }],
      },
      options: baseChartOptions(),
    };
  }

  /** Couleur translucide, en conservant la notation d'origine (hex ou rgb). */
  private alpha(color: string, opacity: number): string {
    if (color.startsWith('#') && color.length === 7) {
      const value = parseInt(color.slice(1), 16);
      return `rgba(${(value >> 16) & 255}, ${(value >> 8) & 255}, ${value & 255}, ${opacity})`;
    }
    return color;
  }
}
