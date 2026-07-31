import { Injectable, inject } from '@angular/core';
import { MatSnackBar, MatSnackBarConfig } from '@angular/material/snack-bar';
import { I18nService } from '../i18n/i18n.service';

/**
 * Notifications transitoires.
 *
 * Un seul point d'entree pour tous les retours ephemeres. Sans lui, chaque
 * ecran choisit sa duree, sa position et son libelle de bouton — et
 * l'utilisateur percoit une application faite de morceaux.
 *
 * Trois decisions encapsulees ici :
 *  - **Position en bas a droite.** Le defaut Material (centre bas) recouvre les
 *    actions principales. En bas a droite, la notification est dans le champ
 *    peripherique sans rien masquer.
 *  - **Duree indexee sur la gravite.** Un succes se lit en 3 s. Une erreur
 *    demande de comprendre puis de decider : 6 s.
 *  - **Liseré colore** plutot qu'un fond entierement teinte : la nature du
 *    message se lit en peripherie, sans agresser.
 *
 * Le message arrive **deja traduit** (les appelants disposent du contexte et
 * des parametres) ; seul le bouton de fermeture est traduit ici.
 */
@Injectable({ providedIn: 'root' })
export class ToastService {
  private readonly snackBar = inject(MatSnackBar);
  private readonly i18n = inject(I18nService);

  private readonly base: MatSnackBarConfig = {
    horizontalPosition: 'end',
    verticalPosition: 'bottom',
  };

  success(message: string): void {
    this.open(message, 'snack--success', 3500);
  }

  error(message: string): void {
    this.open(message, 'snack--error', 6000);
  }

  info(message: string): void {
    this.open(message, 'snack--info', 4000);
  }

  private open(message: string, panelClass: string, duration: number): void {
    this.snackBar.open(message, this.i18n.t('common.close'), {
      ...this.base,
      panelClass,
      duration,
    });
  }
}
