import { Pipe, PipeTransform } from '@angular/core';

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/**
 * Date relative : « il y a 3 min », « il y a 2 h », « hier », « 14 mars ».
 *
 * Pourquoi pas une date absolue partout : dans une file de support, la question
 * n'est jamais « quel jour ce ticket est-il arrive » mais « depuis combien de
 * temps attend-il ». Un relatif repond a la question en un coup d'oeil ; un
 * `12/03/2026 14:32` demande un calcul mental.
 *
 * Au-dela de 7 jours on repasse en absolu : « il y a 43 jours » n'aide plus
 * personne, alors qu'une date se situe.
 *
 * La valeur absolue complete reste disponible via `absolute()`, a mettre dans
 * l'attribut `title` — le survol donne la precision, la lecture donne le sens.
 */
@Pipe({ name: 'relativeTime', standalone: true, pure: true })
export class RelativeTimePipe implements PipeTransform {
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
      return "a l'instant";
    }
    if (diff < HOUR) {
      return `il y a ${Math.floor(diff / MINUTE)} min`;
    }
    if (diff < DAY) {
      return `il y a ${Math.floor(diff / HOUR)} h`;
    }
    if (diff < 2 * DAY) {
      return 'hier';
    }
    if (diff < 7 * DAY) {
      return `il y a ${Math.floor(diff / DAY)} j`;
    }
    return this.shortDate(date);
  }

  private shortDate(date: Date): string {
    const sameYear = date.getFullYear() === new Date().getFullYear();
    return date.toLocaleDateString('fr-FR', {
      day: 'numeric',
      month: 'short',
      year: sameYear ? undefined : 'numeric',
    });
  }
}

/** Date complete, destinee a l'attribut `title`. */
@Pipe({ name: 'absoluteTime', standalone: true, pure: true })
export class AbsoluteTimePipe implements PipeTransform {
  transform(value: string | Date | null | undefined): string {
    if (!value) {
      return '';
    }
    const date = value instanceof Date ? value : new Date(value);
    if (Number.isNaN(date.getTime())) {
      return '';
    }
    return date.toLocaleString('fr-FR', {
      weekday: 'long',
      day: 'numeric',
      month: 'long',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  }
}
