import { Injectable, computed, effect, signal } from '@angular/core';
import { EN } from './translations.en';
import { Dictionary, FR, TranslationKey } from './translations.fr';

export type Lang = 'fr' | 'en';

const STORAGE_KEY = 'supportiq.lang';

const DICTIONARIES: Record<Lang, Dictionary> = { fr: FR, en: EN };

/** Parametres d'interpolation : `{n}`, `{email}`… */
export type TranslateParams = Record<string, string | number>;

/**
 * Traduction de l'interface.
 *
 * **Pourquoi pas `@angular/localize`** (l'i18n officielle d'Angular) : elle
 * compile un bundle **par langue** et exige un rechargement complet — souvent
 * un changement d'URL — pour changer de langue. La demande ici est explicite :
 * bascule instantanee, sans rechargement. Un dictionnaire charge en memoire est
 * donc la seule reponse correcte. Contrepartie assumee : les deux langues sont
 * dans le bundle (quelques ko compresses, negligeable).
 *
 * **Pourquoi pas ngx-translate / transloco** : une dependance de plus, un
 * chargement asynchrone de fichiers JSON, et surtout la perte du typage. Ici,
 * les cles sont un **type TypeScript** derive du dictionnaire francais : une
 * cle inexistante ou une traduction anglaise manquante ne compile pas. C'est
 * plus sur qu'un `translate.get('tickets.titel')` qui echoue en silence devant
 * l'utilisateur.
 *
 * Choix de la langue, dans l'ordre :
 *   1. le choix explicite de l'utilisateur (localStorage) ;
 *   2. sinon la langue du navigateur si elle est supportee ;
 *   3. sinon le francais.
 */
@Injectable({ providedIn: 'root' })
export class I18nService {
  private readonly _lang = signal<Lang>(this.readInitial());

  readonly lang = this._lang.asReadonly();

  /**
   * Dictionnaire courant. Expose en `computed` pour que tout ce qui en depend
   * (pipe, libelles de graphiques, badges) se recalcule a la bascule.
   */
  readonly dict = computed(() => DICTIONARIES[this._lang()]);

  readonly available: { code: Lang; label: string; flag: string }[] = [
    { code: 'fr', label: 'Français', flag: 'FR' },
    { code: 'en', label: 'English', flag: 'EN' },
  ];

  constructor() {
    effect(() => {
      const lang = this._lang();
      // `lang` sur <html> : indispensable pour les lecteurs d'ecran (choix de
      // la voix et de la prononciation) et pour la cesure typographique.
      document.documentElement.setAttribute('lang', lang);
    });
  }

  set(lang: Lang): void {
    if (lang === this._lang()) {
      return;
    }
    this._lang.set(lang);
    try {
      localStorage.setItem(STORAGE_KEY, lang);
    } catch {
      /* stockage indisponible : le choix vaut pour la session */
    }
  }

  toggle(): void {
    this.set(this._lang() === 'fr' ? 'en' : 'fr');
  }

  /**
   * Traduit une cle. Le retour est toujours une chaine : si une cle venait a
   * manquer (impossible en TypeScript, possible via un cast), on renvoie la
   * cle elle-meme plutot qu'une case vide — un defaut visible se corrige, un
   * texte absent passe inapercu.
   */
  t(key: TranslationKey, params?: TranslateParams): string {
    const raw = this.dict()[key] ?? key;
    return params ? interpolate(raw, params) : raw;
  }

  /**
   * Accord singulier / pluriel. Les deux langues suivent la meme regle simple
   * (0 et 1 au singulier en anglais, 0 et 1 au singulier en francais aussi
   * pour nos formulations), donc une seule fonction suffit ici.
   */
  plural(n: number, singular: TranslationKey, plural: TranslationKey, params?: TranslateParams): string {
    return this.t(n > 1 ? plural : singular, { n, ...params });
  }

  /** Locale complete, pour `toLocaleString` (nombres, dates). */
  locale(): string {
    return this._lang() === 'fr' ? 'fr-FR' : 'en-GB';
  }

  private readInitial(): Lang {
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      if (saved === 'fr' || saved === 'en') {
        return saved;
      }
    } catch {
      /* stockage indisponible */
    }
    return navigator.language?.toLowerCase().startsWith('en') ? 'en' : 'fr';
  }
}

function interpolate(text: string, params: TranslateParams): string {
  return text.replace(/\{(\w+)\}/g, (match, name: string) =>
    name in params ? String(params[name]) : match,
  );
}
