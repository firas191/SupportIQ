import { Pipe, PipeTransform, inject } from '@angular/core';
import { I18nService } from '../../core/i18n/i18n.service';

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/**
 * Date relative : « il y a 3 min », « hier », « 14 mars ».
 *
 * Pourquoi pas une date absolue partout : dans une file de support, la question
 * n'est jamais « quel jour ce ticket est-il arrive » mais « depuis combien de
 * temps attend-il ». Un relatif repond en un coup d'oeil ; un `12/03/2026 14:32`
 * demande un calcul mental.
 *
 * Au-dela de 7 jours on repasse en absolu : « il y a 43 jours » n'aide plus
 * personne, alors qu'une date se situe.
 *
 * `pure: false` : le rendu depend de l'heure courante **et** de la langue, deux
 * entrees invisibles depuis les arguments du pipe. Un pipe pur resterait fige
 * sur la premiere valeur calculee.
 */
@Pipe({ name: 'relativeTime', standalone: true, pure: false })
export class RelativeTimePipe implements PipeTransform {
  private readonly i18n = inject(I18nService);

  transform(value: string | Date | null | undefined): string {
    if (!value) {
      return '—';
    }
    const date = value instanceof Date ? value : new Date(value);
    if (Number.isNaN(date.getTime())) {
      return '—';
    }

    const diff = Date.now() - date.getTime();

    if (diff < 0) {
      return this.shortDate(date);
    }
    if (diff < MINUTE) {
      return this.i18n.t('time.now');
    }
    if (diff < HOUR) {
      return this.i18n.t('time.minutes', { n: Math.floor(diff / MINUTE) });
    }
    if (diff < DAY) {
      return this.i18n.t('time.hours', { n: Math.floor(diff / HOUR) });
    }
    if (diff < 2 * DAY) {
      return this.i18n.t('time.yesterday');
    }
    if (diff < 7 * DAY) {
      return this.i18n.t('time.days', { n: Math.floor(diff / DAY) });
    }
    return this.shortDate(date);
  }

  private shortDate(date: Date): string {
    const sameYear = date.getFullYear() === new Date().getFullYear();
    return date.toLocaleDateString(this.i18n.locale(), {
      day: 'numeric',
      month: 'short',
      year: sameYear ? undefined : 'numeric',
    });
  }
}

/** Date complete, destinee a l'attribut `title`. */
@Pipe({ name: 'absoluteTime', standalone: true, pure: false })
export class AbsoluteTimePipe implements PipeTransform {
  private readonly i18n = inject(I18nService);

  transform(value: string | Date | null | undefined): string {
    if (!value) {
      return '';
    }
    const date = value instanceof Date ? value : new Date(value);
    if (Number.isNaN(date.getTime())) {
      return '';
    }
    return date.toLocaleString(this.i18n.locale(), {
      weekday: 'long',
      day: 'numeric',
      month: 'long',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  }
}
