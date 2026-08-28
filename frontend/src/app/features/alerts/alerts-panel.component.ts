import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { AlertsService } from '../../core/alerts/alerts.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { TranslatePipe } from '../../core/i18n/t.pipe';
import { Alert } from '../../core/models/alert.models';
import { RealtimeService } from '../../core/realtime/realtime.service';
import { ToastService } from '../../core/ui/toast.service';
import { AbsoluteTimePipe } from '../../shared/pipes/relative-time.pipe';
import { BadgeComponent } from '../../shared/ui/badge.component';
import { IconComponent } from '../../shared/ui/icon.component';

/**
 * Panneau d'alertes avec acquittement (S7-J2, rapport §9).
 *
 * Trois partis pris.
 *
 * 1. **Le panneau se replie en une ligne discrete quand il n'y a rien.** Un
 *    bandeau « aucune alerte » occuperait en permanence la place la plus
 *    precieuse de l'ecran pour dire qu'il ne se passe rien. Une alerte doit se
 *    remarquer : elle ne le peut que si son emplacement est calme le reste du
 *    temps. La ligne subsiste parce qu'elle porte le declenchement manuel — et
 *    qu'un emplacement totalement vide laisserait croire que la fonction
 *    n'existe pas.
 *
 * 2. **Les chiffres sont montres, pas le score.** « 41 tickets Facturation entre
 *    14 h et 15 h, la ou 6 etaient attendus » se comprend sans rien savoir de la
 *    methode ; « score 7,2 » ne veut rien dire pour la personne qui doit decider
 *    d'agir. Le score reste accessible en info-bulle, pour qui veut verifier.
 *
 * 3. **L'acquittement dit qui.** Une alerte prise en charge n'est pas une alerte
 *    effacee : elle reste visible avec le nom de la personne, ce qui evite que
 *    deux responsables traitent le meme incident chacun de son cote.
 */
@Component({
  selector: 'app-alerts-panel',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslatePipe,
    // `RelativeTimePipe` retire : l'heure de l'anomalie est desormais affichee en bornes absolues
    // (voir `hourRange`). `absoluteTime` reste pour les info-bulles.
    AbsoluteTimePipe,
    BadgeComponent,
    IconComponent,
  ],
  templateUrl: './alerts-panel.component.html',
  styleUrl: './alerts-panel.component.scss',
})
export class AlertsPanelComponent {
  private readonly alerts = inject(AlertsService);
  private readonly realtime = inject(RealtimeService);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);

  protected readonly items = signal<Alert[]>([]);
  protected readonly working = signal<number | null>(null);
  protected readonly detecting = signal(false);
  /** Les alertes acquittees sont repliees par defaut : elles ne demandent plus rien. */
  protected readonly showHandled = signal(false);

  protected readonly open = computed(() => this.items().filter((a) => !a.acknowledgedAt));
  protected readonly handled = computed(() => this.items().filter((a) => a.acknowledgedAt));
  protected readonly visible = computed(() =>
    this.showHandled() ? this.items() : this.open(),
  );

  constructor() {
    // Un seul effet couvre le chargement initial **et** chaque signal temps reel : il s'execute une
    // premiere fois a la creation, puis a chaque incrementation du compteur. Appeler `load()` dans
    // le constructeur en plus lancerait deux requetes identiques a l'ouverture de l'ecran.
    //
    // Le message pousse n'est qu'un signal : il ne porte pas les chiffres, il dit que quelque chose
    // a bouge. On recharge la liste par l'API protegee — c'est ce qui evite de dupliquer le RBAC
    // dans le canal WebSocket, ouvert en permitAll (S4-J5).
    effect(
      () => {
        this.realtime.alertPing();
        this.load();
      },
      { allowSignalWrites: true },
    );
  }

  protected detect(): void {
    if (this.detecting()) {
      return;
    }
    this.detecting.set(true);
    this.alerts.detect().subscribe({
      next: (created) => {
        this.detecting.set(false);
        this.load();
        // Zero alerte creee est un **resultat**, pas un echec : soit rien d'anormal, soit le pic
        // avait deja ete signale. Le dire evite de cliquer trois fois en croyant que rien ne part.
        this.toast.success(
          this.i18n.t(created.length ? 'alerts.detected' : 'alerts.nothingNew'),
        );
      },
      error: (err) => {
        this.detecting.set(false);
        this.toast.error(
          this.i18n.t(err.status === 503 ? 'alerts.unavailable' : 'alerts.failed'),
        );
      },
    });
  }

  protected acknowledge(alert: Alert): void {
    if (this.working() !== null) {
      return;
    }
    this.working.set(alert.id);
    this.alerts.acknowledge(alert.id).subscribe({
      next: (updated) => {
        this.working.set(null);
        this.items.update((list) => list.map((a) => (a.id === updated.id ? updated : a)));
      },
      error: (err) => {
        this.working.set(null);
        // 409 = quelqu'un d'autre vient de la prendre. Ce n'est pas une erreur de l'utilisateur,
        // c'est une information : on recharge pour lui montrer qui s'en charge.
        this.toast.error(
          this.i18n.t(err.status === 409 ? 'alerts.alreadyHandled' : 'alerts.ackFailed'),
        );
        this.load();
      },
    });
  }

  protected toggleHandled(): void {
    this.showHandled.update((shown) => !shown);
  }

  /**
   * Valeur attendue, arrondie et ecrite en francais.
   *
   * Le serveur renvoie `12.63` — deux decimales et un point. Affiche tel quel, cela donne
   * « 30 tickets, contre 12.63 attendus » : une precision que la mesure n'a pas, dans une notation
   * qui n'est pas celle de la langue. Meme defaut que le « 0.0 % » du premier digest (S6-J4).
   *
   * Une decimale suffit : personne ne decide differemment entre 12,6 et 12,63 attendus.
   */
  protected expected(alert: Alert): string {
    const value = alert.payload.expected ?? 0;
    return value.toLocaleString(this.i18n.locale(), {
      minimumFractionDigits: 0,
      maximumFractionDigits: 1,
    });
  }

  /**
   * Bornes de l'heure concernee, dans le fuseau du lecteur.
   *
   * Un temps relatif (« il y a 1 h ») donnait « sur l'heure de il y a 1 h », et surtout il devient
   * faux en restant affiche : une alerte lue vingt minutes plus tard porterait « il y a 1 h » alors
   * qu'il y en a deux. Une alerte designe **une heure precise**, elle doit la nommer.
   */
  protected hourRange(alert: Alert): { from: string; to: string } {
    const start = new Date(alert.bucketStart);
    const end = new Date(start.getTime() + 3_600_000);
    const format = (d: Date) =>
      d.toLocaleTimeString(this.i18n.locale(), { hour: '2-digit', minute: '2-digit' });
    return { from: format(start), to: format(end) };
  }

  private load(): void {
    this.alerts.list().subscribe({
      // Le panneau est un complement du tableau de bord : une erreur ici ne doit pas l'empecher
      // d'afficher ses chiffres. On disparait silencieusement plutot que d'occuper l'ecran avec
      // un message d'echec sur une fonction annexe.
      next: (list) => this.items.set(list),
      error: () => this.items.set([]),
    });
  }
}
