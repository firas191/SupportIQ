import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  HostListener,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { TranslatePipe } from '../../core/i18n/t.pipe';
import { ThemeService } from '../../core/theme/theme.service';
import { CommandPaletteService } from '../../core/ui/command-palette.service';
import { IconComponent } from './icon.component';

interface Command {
  id: string;
  label: string;
  hint?: string;
  icon: string;
  group: string;
  /** Mots supplementaires pris en compte par la recherche mais non affiches. */
  keywords?: string;
  run: () => void;
}

/**
 * Palette de commandes (Ctrl/Cmd + K).
 *
 * Ce que ce composant resout : dans une application a plusieurs ecrans, tout
 * deplacement coute un aller-retour visuel vers la navigation. La palette
 * transforme « chercher ou cliquer » en « ecrire ou l'on va ». Les utilisateurs
 * intensifs — ici, les agents — n'ont plus a quitter le clavier.
 *
 * Trois choix de conception :
 *
 *  1. **Commandes contextuelles au role.** La palette n'affiche que ce que
 *     l'utilisateur a le droit de faire. Proposer une destination interdite
 *     pour la refuser ensuite serait un mensonge d'interface.
 *
 *  2. **Reconnaissance de la saisie.** Un nombre est compris comme un numero de
 *     ticket et propose l'ouverture directe ; tout autre texte propose une
 *     recherche. La palette repond a l'intention, pas seulement au libelle.
 *
 *  3. **Filtrage par sous-sequence** (« tik » trouve « Tickets », « chg thm »
 *     trouve « Changer de theme ») : on ne demande pas a l'utilisateur de
 *     connaitre l'orthographe exacte de la commande qu'il cherche.
 *
 * Accessibilite : role `dialog` modal, focus place a l'ouverture et **rendu**
 * a l'element precedent a la fermeture, navigation aux fleches avec
 * `aria-activedescendant`, Echap ferme. Le fond est inerte car la palette
 * capture le clavier tant qu'elle est ouverte.
 */
@Component({
  selector: 'app-command-palette',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe, IconComponent],
  template: `
    @if (isOpen()) {
      <div class="scrim" (click)="close()" aria-hidden="true"></div>

      <div
        class="palette"
        role="dialog"
        aria-modal="true"
        [attr.aria-label]="'palette.title' | t"
        (click)="$event.stopPropagation()"
      >
        <div class="palette__search">
          <app-icon name="search" [size]="18" />
          <input
            #searchInput
            type="text"
            role="combobox"
            aria-expanded="true"
            aria-controls="palette-list"
            [attr.aria-activedescendant]="activeId()"
            [placeholder]="'palette.placeholder' | t"
            [value]="query()"
            (input)="onInput($event)"
            (keydown)="onKeydown($event)"
          />
          <kbd class="kbd">Esc</kbd>
        </div>

        <div class="palette__list" id="palette-list" role="listbox">
          @for (group of grouped(); track group.name) {
            <div class="palette__group" role="presentation">{{ group.name }}</div>
            @for (cmd of group.items; track cmd.id) {
              <button
                type="button"
                class="palette__item"
                role="option"
                [id]="'cmd-' + cmd.id"
                [attr.aria-selected]="cmd.id === activeCommand()?.id"
                [class.is-active]="cmd.id === activeCommand()?.id"
                (mouseenter)="setActive(cmd)"
                (click)="execute(cmd)"
              >
                <app-icon [name]="cmd.icon" [size]="17" />
                <span class="palette__label">{{ cmd.label }}</span>
                @if (cmd.hint) {
                  <span class="palette__hint">{{ cmd.hint }}</span>
                }
                <app-icon name="subdirectory_arrow_left" [size]="14" />
              </button>
            }
          }

          @if (results().length === 0) {
            <div class="palette__none">
              <app-icon name="search_off" [size]="20" />
              <span>{{ 'palette.none' | t: { q: query() } }}</span>
            </div>
          }
        </div>

        <div class="palette__foot">
          <span><kbd class="kbd">↑</kbd><kbd class="kbd">↓</kbd> {{ 'palette.navigate' | t }}</span>
          <span><kbd class="kbd">↵</kbd> {{ 'palette.select' | t }}</span>
          <span class="spacer"></span>
          <span class="palette__brand">SupportIQ</span>
        </div>
      </div>
    }
  `,
  styles: [
    `
      .scrim {
        position: fixed;
        inset: 0;
        z-index: var(--z-overlay);
        background: var(--bg-overlay);
        backdrop-filter: blur(3px);
        animation: fade-in var(--duration-base) var(--ease-out);
      }

      .palette {
        position: fixed;
        /* Ancree au premier tiers vertical : la ou l'oeil se pose
           naturellement, et laisse la place a la liste en dessous. */
        top: 14vh;
        left: 50%;
        transform: translateX(-50%);
        width: min(620px, calc(100vw - var(--space-6)));
        z-index: calc(var(--z-overlay) + 1);
        background: var(--bg-surface);
        border: 1px solid var(--border-default);
        border-radius: var(--radius-xl);
        box-shadow: var(--shadow-lg);
        overflow: hidden;
        animation: palette-in var(--duration-base) var(--ease-out);
      }

      @keyframes palette-in {
        from { opacity: 0; transform: translateX(-50%) translateY(-8px) scale(0.985); }
        to { opacity: 1; transform: translateX(-50%) translateY(0) scale(1); }
      }

      .palette__search {
        display: flex;
        align-items: center;
        gap: var(--space-3);
        padding: 0 var(--space-4);
        height: 52px;
        border-bottom: 1px solid var(--border-subtle);
        color: var(--text-tertiary);

        input {
          flex: 1;
          min-width: 0;
          border: none;
          background: none;
          outline: none;
          font-size: var(--text-lg);
          color: var(--text-primary);

          &::placeholder { color: var(--text-disabled); }
        }
      }

      .palette__list {
        max-height: min(420px, 52vh);
        overflow-y: auto;
        padding: var(--space-2);
      }

      .palette__group {
        padding: var(--space-3) var(--space-2) var(--space-1);
        font-size: var(--text-xs);
        font-weight: var(--weight-semibold);
        letter-spacing: var(--tracking-wide);
        text-transform: uppercase;
        color: var(--text-tertiary);
      }

      .palette__item {
        display: flex;
        align-items: center;
        gap: var(--space-3);
        width: 100%;
        padding: 0 var(--space-3);
        height: 38px;
        border: none;
        border-radius: var(--radius-md);
        background: transparent;
        color: var(--text-secondary);
        text-align: left;
        cursor: pointer;
        transition: background-color var(--duration-fast) var(--ease-out);

        /* La derniere icone (le chevron « entree ») n'apparait que sur
           l'element actif : elle indique quoi faire, pas ou l'on est. */
        app-icon:last-child { opacity: 0; margin-left: auto; }

        &.is-active {
          background: var(--accent-soft-bg);
          color: var(--accent-soft-fg);

          app-icon:last-child { opacity: 0.7; }
        }
      }

      .palette__label {
        font-size: var(--text-md);
        font-weight: var(--weight-medium);
        color: var(--text-primary);
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }

      .palette__item.is-active .palette__label { color: var(--accent-soft-fg); }

      .palette__hint {
        font-size: var(--text-sm);
        color: var(--text-tertiary);
        margin-left: auto;
        white-space: nowrap;
      }

      .palette__none {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: var(--space-2);
        padding: var(--space-8) var(--space-4);
        color: var(--text-tertiary);
        font-size: var(--text-base);
        text-align: center;
      }

      .palette__foot {
        display: flex;
        align-items: center;
        gap: var(--space-4);
        padding: var(--space-2) var(--space-4);
        border-top: 1px solid var(--border-subtle);
        background: var(--bg-surface-2);
        font-size: var(--text-sm);
        color: var(--text-tertiary);

        span { display: inline-flex; align-items: center; gap: 4px; }
      }

      .palette__brand {
        font-weight: var(--weight-semibold);
        letter-spacing: var(--tracking-wide);
        text-transform: uppercase;
        font-size: var(--text-xs);
        opacity: 0.6;
      }

      @media (max-width: 640px) {
        .palette { top: 8vh; }
        .palette__foot { display: none; }
      }
    `,
  ],
})
export class CommandPaletteComponent {
  private readonly palette = inject(CommandPaletteService);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);
  private readonly theme = inject(ThemeService);
  private readonly i18n = inject(I18nService);

  private readonly searchInput = viewChild<ElementRef<HTMLInputElement>>('searchInput');
  /** Element focalise avant l'ouverture : on lui rend le focus a la fermeture. */
  private returnFocusTo: HTMLElement | null = null;

  protected readonly isOpen = this.palette.open;
  protected readonly query = signal('');
  protected readonly activeIndex = signal(0);

  constructor() {
    effect(() => {
      if (this.isOpen()) {
        this.returnFocusTo = document.activeElement as HTMLElement | null;
        // Le champ n'existe qu'une fois le bloc @if rendu. setTimeout (macro-tache)
        // et non queueMicrotask : les micro-taches s'executent avant la fin du
        // cycle de detection, donc avant que l'element soit dans le DOM.
        setTimeout(() => this.searchInput()?.nativeElement.focus());
      } else {
        this.returnFocusTo?.focus?.();
        this.returnFocusTo = null;
      }
    });
  }

  /** Raccourci global. `preventDefault` : Ctrl+K est pris par la barre d'adresse. */
  @HostListener('document:keydown', ['$event'])
  onGlobalKeydown(event: KeyboardEvent): void {
    if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
      event.preventDefault();
      this.query.set('');
      this.activeIndex.set(0);
      this.palette.toggle();
    } else if (event.key === 'Escape' && this.isOpen()) {
      event.preventDefault();
      this.close();
    }
  }

  /* --- Catalogue de commandes ------------------------------------------- */

  private readonly commands = computed<Command[]>(() => {
    const role = this.auth.role();
    const isManager = role === 'MANAGER' || role === 'ADMIN';
    const isAdmin = role === 'ADMIN';
    const t = this.i18n.t.bind(this.i18n);
    const navigate = t('palette.groupNavigate');
    const list: Command[] = [];

    if (isManager) {
      list.push({
        id: 'nav-dashboard',
        label: t('nav.dashboard'),
        icon: 'space_dashboard',
        group: navigate,
        keywords: 'dashboard tableau bord indicateurs statistiques kpi overview',
        run: () => this.router.navigate(['/dashboard']),
      });
    }

    list.push({
      id: 'nav-tickets',
      label: t('nav.tickets'),
      icon: 'confirmation_number',
      group: navigate,
      keywords: 'file liste demandes queue requests',
      run: () => this.router.navigate(['/tickets']),
    });

    if (isAdmin) {
      list.push(
        {
          id: 'nav-imports',
          label: t('imports.title'),
          icon: 'upload_file',
          group: navigate,
          keywords: 'csv xlsx json fichier charger file upload',
          run: () => this.router.navigate(['/imports']),
        },
        {
          id: 'nav-users',
          label: t('nav.team'),
          icon: 'group',
          group: navigate,
          keywords: 'utilisateurs comptes membres roles users accounts',
          run: () => this.router.navigate(['/admin/users']),
        },
      );
    }

    const prefs = t('palette.groupPreferences');
    const dark = this.theme.theme() === 'dark';
    list.push(
      {
        id: 'toggle-theme',
        label: t(dark ? 'palette.toLight' : 'palette.toDark'),
        icon: dark ? 'light_mode' : 'dark_mode',
        group: prefs,
        keywords: 'theme sombre clair nuit jour apparence dark light appearance',
        run: () => this.theme.toggle(),
      },
      {
        // La bascule de langue est dans la palette : c'est le chemin le plus
        // court pour un utilisateur qui vient d'atterrir dans la mauvaise
        // langue et cherche a en sortir sans explorer l'interface.
        id: 'toggle-lang',
        label: t(this.i18n.lang() === 'fr' ? 'palette.switchToEnglish' : 'palette.switchToFrench'),
        icon: 'translate',
        group: prefs,
        keywords: 'langue language english francais french anglais traduction',
        run: () => this.i18n.toggle(),
      },
      {
        id: 'logout',
        label: t('topbar.logout'),
        icon: 'logout',
        group: t('palette.groupAccount'),
        keywords: 'quitter sortir session deconnexion sign out',
        run: () => this.auth.logout(),
      },
    );

    return list;
  });

  /**
   * Resultats : d'abord les commandes suggerees par la saisie elle-meme
   * (ouvrir un numero de ticket, lancer une recherche), puis le catalogue
   * filtre. Les propositions contextuelles passent devant parce qu'elles
   * repondent a une intention explicite, pas a une correspondance de texte.
   */
  protected readonly results = computed<Command[]>(() => {
    const raw = this.query().trim();
    const contextual: Command[] = [];

    if (raw) {
      const ticketId = Number(raw.replace('#', ''));
      if (Number.isInteger(ticketId) && ticketId > 0) {
        contextual.push({
          id: 'open-ticket',
          label: this.i18n.t('palette.openTicket', { id: ticketId }),
          icon: 'open_in_new',
          group: this.i18n.t('palette.groupGoTo'),
          run: () => this.router.navigate(['/tickets', ticketId]),
        });
      }
      contextual.push({
        id: 'search-tickets',
        label: this.i18n.t('palette.searchFor', { q: raw }),
        icon: 'search',
        group: this.i18n.t('palette.groupGoTo'),
        run: () => this.router.navigate(['/tickets'], { queryParams: { q: raw } }),
      });
    }

    const needle = this.normalize(raw);
    const matched = needle
      ? this.commands().filter((c) => this.matches(this.normalize(`${c.label} ${c.keywords ?? ''}`), needle))
      : this.commands();

    return [...contextual, ...matched];
  });

  protected readonly grouped = computed(() => {
    const groups: { name: string; items: Command[] }[] = [];
    for (const cmd of this.results()) {
      const existing = groups.find((g) => g.name === cmd.group);
      if (existing) {
        existing.items.push(cmd);
      } else {
        groups.push({ name: cmd.group, items: [cmd] });
      }
    }
    return groups;
  });

  protected readonly activeCommand = computed<Command | null>(() => {
    const list = this.results();
    return list[Math.min(this.activeIndex(), list.length - 1)] ?? null;
  });

  protected readonly activeId = computed(() => {
    const cmd = this.activeCommand();
    return cmd ? `cmd-${cmd.id}` : null;
  });

  /* --- Interactions ------------------------------------------------------ */

  protected onInput(event: Event): void {
    this.query.set((event.target as HTMLInputElement).value);
    this.activeIndex.set(0);
  }

  protected onKeydown(event: KeyboardEvent): void {
    const count = this.results().length;
    if (count === 0) {
      return;
    }

    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        // Boucle : arrive en bas, on repart en haut. Evite le cul-de-sac.
        this.activeIndex.set((this.activeIndex() + 1) % count);
        this.scrollActiveIntoView();
        break;
      case 'ArrowUp':
        event.preventDefault();
        this.activeIndex.set((this.activeIndex() - 1 + count) % count);
        this.scrollActiveIntoView();
        break;
      case 'Enter': {
        event.preventDefault();
        const cmd = this.activeCommand();
        if (cmd) {
          this.execute(cmd);
        }
        break;
      }
    }
  }

  protected setActive(cmd: Command): void {
    const index = this.results().findIndex((c) => c.id === cmd.id);
    if (index >= 0) {
      this.activeIndex.set(index);
    }
  }

  protected execute(cmd: Command): void {
    this.close();
    cmd.run();
  }

  protected close(): void {
    this.palette.hide();
    this.query.set('');
    this.activeIndex.set(0);
  }

  /* --- Utilitaires ------------------------------------------------------- */

  /** Minuscules sans accents : « theme » doit trouver « theme » comme « thème ». */
  private normalize(value: string): string {
    return value
      .toLowerCase()
      .normalize('NFD')
      .replace(/[\u0300-\u036f]/g, '');
  }

  /**
   * Correspondance par sous-sequence : les caracteres de la requete doivent
   * apparaitre dans l'ordre, pas forcement cote a cote. « tks » trouve
   * « Tickets ». C'est la tolerance a laquelle les utilisateurs sont habitues
   * dans ce type d'outil.
   */
  private matches(haystack: string, needle: string): boolean {
    let i = 0;
    for (const char of haystack) {
      if (char === needle[i]) {
        i++;
        if (i === needle.length) {
          return true;
        }
      }
    }
    return false;
  }

  private scrollActiveIntoView(): void {
    queueMicrotask(() => {
      document
        .querySelector('.palette__item.is-active')
        ?.scrollIntoView({ block: 'nearest' });
    });
  }
}
