import { Pipe, PipeTransform, inject } from '@angular/core';
import { I18nService, TranslateParams } from './i18n.service';
import { TranslationKey } from './translations.fr';

/**
 * Pipe de traduction : `{{ 'tickets.title' | t }}`, avec parametres
 * `{{ 'tickets.range' | t: { from: 1, to: 25, total: 300 } }}`.
 *
 * **Pourquoi `pure: false`.** Un pipe pur est memoise sur ses arguments : la
 * cle ne changeant pas quand la langue change, il ne serait jamais reevalue et
 * l'interface resterait figee dans la langue initiale. Un pipe impur est
 * reevalue a chaque cycle de detection — c'est le compromis retenu par toutes
 * les bibliotheques d'i18n runtime (ngx-translate, transloco).
 *
 * Le cout reel est negligeable : `transform` fait une lecture dans un objet
 * (O(1)) et **tous les composants de l'application sont en OnPush**, donc les
 * cycles de detection sont rares et localises. En echange, chaque libelle suit
 * la langue instantanement, sans rechargement ni abonnement a gerer.
 */
@Pipe({ name: 't', standalone: true, pure: false })
export class TranslatePipe implements PipeTransform {
  private readonly i18n = inject(I18nService);

  transform(key: TranslationKey, params?: TranslateParams): string {
    return this.i18n.t(key, params);
  }
}
