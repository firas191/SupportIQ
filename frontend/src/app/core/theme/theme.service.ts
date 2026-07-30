import { Injectable, effect, signal } from '@angular/core';

export type Theme = 'light' | 'dark';

const STORAGE_KEY = 'supportiq.theme';

/**
 * Bascule clair / sombre.
 *
 * Le theme vit dans un attribut `data-theme` sur <html> : c'est ce que les
 * tokens CSS ecoutent. Aucune classe n'est ajoutee sur les composants, aucun
 * style n'est recalcule en JavaScript — le navigateur reevalue simplement les
 * custom properties, ce qui est quasi gratuit.
 *
 * L'attribut est deja pose par un script inline dans index.html **avant** le
 * premier rendu (sinon : flash blanc au chargement en mode sombre). Ce service
 * se contente de reprendre la main sur cette valeur initiale, puis de la
 * maintenir.
 *
 * Trois niveaux de decision, dans l'ordre :
 *   1. le choix explicite de l'utilisateur (localStorage),
 *   2. sinon la preference systeme (prefers-color-scheme),
 *   3. sinon le theme clair.
 * Tant que l'utilisateur n'a rien choisi, on continue de suivre le systeme en
 * direct : basculer son OS en sombre le soir bascule l'application aussi.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly _theme = signal<Theme>(this.readInitial());

  readonly theme = this._theme.asReadonly();

  constructor() {
    // L'attribut DOM suit toujours le signal — une seule source de verite.
    effect(() => {
      const theme = this._theme();
      document.documentElement.setAttribute('data-theme', theme);
    });

    this.followSystemUntilUserChooses();
  }

  toggle(): void {
    this.set(this._theme() === 'dark' ? 'light' : 'dark');
  }

  set(theme: Theme): void {
    this._theme.set(theme);
    try {
      localStorage.setItem(STORAGE_KEY, theme);
    } catch {
      // Mode navigation privee ou stockage plein : le theme reste valable pour
      // la session en cours, ce n'est pas une erreur bloquante.
    }
  }

  private readInitial(): Theme {
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      if (saved === 'light' || saved === 'dark') {
        return saved;
      }
    } catch {
      /* stockage indisponible */
    }
    return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }

  private followSystemUntilUserChooses(): void {
    const query = window.matchMedia?.('(prefers-color-scheme: dark)');
    query?.addEventListener?.('change', (e) => {
      let hasExplicitChoice = false;
      try {
        hasExplicitChoice = !!localStorage.getItem(STORAGE_KEY);
      } catch {
        /* ignore */
      }
      if (!hasExplicitChoice) {
        this._theme.set(e.matches ? 'dark' : 'light');
      }
    });
  }
}
