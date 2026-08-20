import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Observable } from 'rxjs';
import { DigestService } from '../../core/digest/digest.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { TranslatePipe } from '../../core/i18n/t.pipe';
import { TranslationKey } from '../../core/i18n/translations.fr';
import { Digest, DigestStatus } from '../../core/models/digest.models';
import { ToastService } from '../../core/ui/toast.service';
import { AbsoluteTimePipe, RelativeTimePipe } from '../../shared/pipes/relative-time.pipe';
import { IconComponent } from '../../shared/ui/icon.component';
import { IllustrationComponent } from '../../shared/ui/illustration.component';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import { SkeletonComponent } from '../../shared/ui/skeleton.component';

/**
 * Synthese hebdomadaire (S6-J4).
 *
 * Trois choix d'affichage, tous dictes par la meme idee : **un document qui part
 * par courriel doit etre verifiable avant de partir, et son sort doit etre
 * visible apres.**
 *
 * 1. **L'etat d'envoi est explicite pour chaque semaine** — envoye, jamais
 *    envoye, ou echec avec sa cause. Un courriel qui ne part pas en silence est
 *    pire qu'une erreur affichee : personne ne s'apercoit que la synthese n'est
 *    jamais arrivee.
 *
 * 2. **L'absence de configuration d'envoi est annoncee**, pas devinee. Sans
 *    serveur de courriel, l'ecran dit que la synthese reste consultable ici
 *    plutot que de laisser croire qu'elle a ete expediee.
 *
 * 3. **Le contenu est lisible a l'ecran**, sans telecharger le PDF. Le Markdown
 *    fait foi ; le PDF n'en est qu'un rendu.
 */
@Component({
  selector: 'app-digest',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslatePipe,
    RelativeTimePipe,
    AbsoluteTimePipe,
    IconComponent,
    IllustrationComponent,
    PageHeaderComponent,
    SkeletonComponent,
  ],
  templateUrl: './digest.component.html',
  styleUrl: './digest.component.scss',
})
export class DigestComponent {
  private readonly digests = inject(DigestService);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);

  protected readonly items = signal<Digest[]>([]);
  protected readonly status = signal<DigestStatus | null>(null);
  protected readonly loading = signal(true);
  protected readonly working = signal(false);
  protected readonly openId = signal<number | null>(null);

  protected readonly empty = computed(() => !this.loading() && this.items().length === 0);

  constructor() {
    this.load();
    this.digests.status().subscribe({
      next: (s) => this.status.set(s),
      // L'etat de configuration est un confort : son absence ne doit pas
      // empecher de consulter les syntheses.
      error: () => this.status.set(null),
    });
  }

  /* --- Actions ------------------------------------------------------------ */

  protected generate(): void {
    this.run(this.digests.generate(), 'digest.generated');
  }

  /**
   * Reproduit une semaine deja generee.
   *
   * Action distincte de « Generer maintenant » et **par semaine**, parce que le bouton principal
   * renvoie simplement la synthese existante quand elle est deja la. Sans cette distinction, un
   * clic donnait un message de succes sans que rien ne change a l'ecran — le pire retour possible :
   * il laisse croire a une panne silencieuse.
   */
  protected regenerate(digest: Digest): void {
    this.run(this.digests.regenerate(digest.weekStart), 'digest.regenerated');
  }

  protected resend(id: number): void {
    if (this.working()) {
      return;
    }
    this.working.set(true);
    this.digests.send(id).subscribe({
      next: (d) => {
        this.working.set(false);
        this.items.update((list) => list.map((item) => (item.id === id ? d : item)));
        this.toast.success(this.i18n.t(d.sentAt ? 'digest.sent' : 'digest.sendFailed'));
      },
      error: (err) => {
        this.working.set(false);
        this.toast.error(this.i18n.t(this.errorKey(err.status)));
      },
    });
  }

  protected download(digest: Digest): void {
    this.digests.pdf(digest.id).subscribe({
      next: (blob) => this.saveBlob(blob, `digest-${digest.weekStart}.pdf`),
      error: () => this.toast.error(this.i18n.t('digest.pdfUnavailable')),
    });
  }

  protected toggle(id: number): void {
    this.openId.update((current) => (current === id ? null : id));
  }

  /* --- Interne ------------------------------------------------------------ */

  /** Generation et regeneration ne different que par l'appel et le message de succes. */
  private run(call: Observable<Digest>, successKey: TranslationKey): void {
    if (this.working()) {
      return;
    }
    this.working.set(true);
    call.subscribe({
      next: () => {
        this.working.set(false);
        this.toast.success(this.i18n.t(successKey));
        this.load();
      },
      error: (err) => {
        this.working.set(false);
        this.toast.error(this.i18n.t(this.errorKey(err.status)));
      },
    });
  }

  private saveBlob(blob: Blob, filename: string): void {
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    link.click();
    // Sans revocation, l'URL objet retient le blob en memoire jusqu'au
    // rechargement de la page — une fuite discrete mais reelle sur un ecran
    // qu'on garde ouvert.
    URL.revokeObjectURL(url);
  }

  private errorKey(status: number): TranslationKey {
    return status === 503 ? 'digest.unavailable' : 'digest.failed';
  }

  private load(): void {
    this.loading.set(true);
    this.digests.list().subscribe({
      next: (list) => {
        this.items.set(list);
        this.loading.set(false);
      },
      error: () => {
        this.items.set([]);
        this.loading.set(false);
      },
    });
  }
}
