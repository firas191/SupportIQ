import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { I18nService } from '../../core/i18n/i18n.service';
import { TranslatePipe } from '../../core/i18n/t.pipe';
import { Topic, TopicSnapshot } from '../../core/models/topic.models';
import { TopicsService } from '../../core/topics/topics.service';
import { ToastService } from '../../core/ui/toast.service';
import { AbsoluteTimePipe, RelativeTimePipe } from '../../shared/pipes/relative-time.pipe';
import { BadgeComponent } from '../../shared/ui/badge.component';
import { IconComponent } from '../../shared/ui/icon.component';
import { IllustrationComponent } from '../../shared/ui/illustration.component';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import { SkeletonComponent } from '../../shared/ui/skeleton.component';

/**
 * Sujets emergents (S7-J1).
 *
 * Quatre partis pris, tous destines a empecher l'ecran d'affirmer plus que la
 * mesure ne le permet.
 *
 * 1. **« Nouveau » plutot que « +100 % ».** Un sujet dont la premiere moitie de
 *    fenetre est vide n'a pas de croissance chiffrable. Le serveur renvoie
 *    `null`, et l'ecran le dit avec un mot — un pourcentage invente ici serait
 *    lu, retenu, et cite en reunion.
 *
 * 2. **Les deux moities sont montrees**, pas seulement leur rapport. « +200 % »
 *    sur 3 tickets contre 1 et sur 300 contre 100 n'appellent pas la meme
 *    reaction, et seul le detail permet de faire la difference. Le rapport seul
 *    est exactement le genre de chiffre qui declenche une reunion pour rien.
 *
 * 3. **Les tickets d'exemple sont cliquables.** Le libelle est ecrit par un
 *    modele a partir des tickets les plus centraux du groupe : c'est une
 *    interpretation, pas une donnee. Pouvoir la verifier en un clic est ce qui
 *    la rend utilisable.
 *
 * 4. **La date de calcul est visible.** L'instantane date de la nuit derniere,
 *    pas de maintenant. Sans cette date, un responsable lirait les chiffres du
 *    matin comme s'ils integraient les tickets de l'apres-midi.
 */
@Component({
  selector: 'app-topics',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    TranslatePipe,
    RelativeTimePipe,
    AbsoluteTimePipe,
    BadgeComponent,
    IconComponent,
    IllustrationComponent,
    PageHeaderComponent,
    SkeletonComponent,
  ],
  templateUrl: './topics.component.html',
  styleUrl: './topics.component.scss',
})
export class TopicsComponent {
  private readonly topics = inject(TopicsService);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);

  protected readonly snapshot = signal<TopicSnapshot | null>(null);
  protected readonly loading = signal(true);
  protected readonly working = signal(false);

  protected readonly items = computed(() => this.snapshot()?.topics ?? []);

  /**
   * Trois etats bien distincts, et non deux.
   *
   * « Jamais calcule » et « calcule, rien trouve » se ressemblent a l'ecran et
   * ne veulent pas du tout dire la meme chose : le premier appelle un clic sur
   * « Recalculer », le second dit qu'il n'y a rien a voir. Les confondre ferait
   * cliquer indefiniment sur un bouton qui ne changera rien.
   */
  protected readonly state = computed<'loading' | 'never' | 'nothing' | 'ready'>(() => {
    if (this.loading()) {
      return 'loading';
    }
    if (!this.snapshot()?.computedAt) {
      return 'never';
    }
    return this.items().length === 0 ? 'nothing' : 'ready';
  });

  /**
   * Part de la fenetre representee par le plus gros sujet.
   *
   * Sert a mettre les barres a l'echelle. Sans reference commune, un sujet de 8
   * tickets et un de 300 auraient des barres de meme longueur, ce qui inverserait
   * le message de la page.
   */
  protected readonly largest = computed(() =>
    this.items().reduce((max, topic) => Math.max(max, topic.size), 1),
  );

  constructor() {
    this.load();
  }

  protected detect(): void {
    if (this.working()) {
      return;
    }
    this.working.set(true);
    this.topics.detect().subscribe({
      next: (snapshot) => {
        this.working.set(false);
        this.snapshot.set(snapshot);
        this.toast.success(this.i18n.t('topics.recomputed'));
      },
      error: (err) => {
        this.working.set(false);
        this.toast.error(
          this.i18n.t(err.status === 503 ? 'topics.unavailable' : 'topics.failed'),
        );
      },
    });
  }

  /* --- Presentation ------------------------------------------------------- */

  protected widthOf(topic: Topic): string {
    return `${Math.round((topic.size / this.largest()) * 100)}%`;
  }

  /**
   * Ton de la pastille de croissance.
   *
   * Volontairement avare : seule une forte hausse est signalee en `warning`,
   * parce qu'elle signifie du travail supplementaire. Une baisse reste neutre —
   * la presenter comme une bonne nouvelle supposerait que moins de tickets vaut
   * toujours mieux, ce que rien dans les donnees ne dit (un canal casse produit
   * aussi une baisse).
   */
  protected growthTone(growth: number | null): 'warning' | 'neutral' {
    return growth !== null && growth >= 50 ? 'warning' : 'neutral';
  }

  /** Un sujet dont la croissance est trop faible pour etre commentee. */
  protected isStable(growth: number | null): boolean {
    return growth !== null && Math.abs(growth) < 0.05;
  }

  /**
   * Pourcentage signe, sans aucun texte.
   *
   * Les deux cas qui demandent un mot — « nouveau » et « stable » — sont rendus dans le gabarit
   * avec le pipe de traduction. Traduire ici obligerait a relire un signal de langue pour que le
   * texte suive une bascule FR/EN, alors que le pipe le fait deja.
   */
  protected growthText(growth: number | null): string {
    if (growth === null) {
      return '';
    }
    // Arrondi a l'entier : la premiere decimale d'une croissance calculee sur quelques dizaines
    // de tickets n'est que du bruit, et l'afficher donnerait une precision que la mesure n'a pas.
    const value = Math.round(Math.abs(growth)).toLocaleString(this.i18n.locale());
    return growth > 0 ? `+${value} %` : `-${value} %`;
  }

  private load(): void {
    this.loading.set(true);
    this.topics.latest().subscribe({
      next: (snapshot) => {
        this.snapshot.set(snapshot);
        this.loading.set(false);
      },
      error: () => {
        this.snapshot.set(null);
        this.loading.set(false);
      },
    });
  }
}
