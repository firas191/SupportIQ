import { Injectable, signal } from '@angular/core';

/**
 * Etat d'ouverture de la palette de commandes.
 *
 * Un service minuscule, mais necessaire : la palette est rendue une seule fois
 * dans le gabarit principal, alors que n'importe quel ecran doit pouvoir
 * l'ouvrir (bouton de la barre du haut, etat vide « rechercher autre chose »,
 * raccourci clavier). Sans ce point de rendez-vous, il faudrait faire remonter
 * un evenement a travers toute la hierarchie.
 */
@Injectable({ providedIn: 'root' })
export class CommandPaletteService {
  private readonly _open = signal(false);

  readonly open = this._open.asReadonly();

  toggle(): void {
    this._open.update((v) => !v);
  }

  show(): void {
    this._open.set(true);
  }

  hide(): void {
    this._open.set(false);
  }
}
