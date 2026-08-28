import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { TranslatePipe } from '../../core/i18n/t.pipe';
import { TranslationKey } from '../../core/i18n/translations.fr';
import { RealtimeService } from '../../core/realtime/realtime.service';
import { ThemeService } from '../../core/theme/theme.service';
import { CommandPaletteService } from '../../core/ui/command-palette.service';
import { ROLE_LABELS, labelOf } from '../../shared/labels';
import { BrandComponent } from '../../shared/ui/brand.component';
import { CommandPaletteComponent } from '../../shared/ui/command-palette.component';
import { IconComponent } from '../../shared/ui/icon.component';

interface NavItem {
  path: string;
  labelKey: TranslationKey;
  icon: string;
  /** Role minimal requis. `null` = tout utilisateur authentifie. */
  minRole: 'ADMIN' | 'MANAGER' | null;
}

interface NavSection {
  titleKey: TranslationKey | null;
  items: NavItem[];
}

const SIDEBAR_KEY = 'supportiq.sidebar.collapsed';

/**
 * Coquille applicative : barre laterale, barre du haut, zone de contenu.
 *
 * Architecture d'information — le point le plus structurant de cette refonte.
 * Auparavant, « Tickets », « Imports » et « Utilisateurs » etaient trois
 * entrees de meme poids visuel. Or leur frequence d'usage n'a rien de
 * comparable : un agent ouvre la file cent fois par jour, un administrateur
 * importe un fichier une fois par mois. Donner le meme poids a une action
 * quotidienne et a une action mensuelle, c'est diluer la premiere.
 *
 * D'ou deux sections :
 *   - en haut, sans titre, ce que l'on ouvre tous les jours ;
 *   - **Administration** en bas, en retrait, et seulement pour qui a le droit.
 *
 * La barre laterale se replie en rail d'icones (64 px). Sur un ecran de
 * portable, cela rend ~180 px a la table des tickets — deux colonnes de plus
 * lisibles sans defilement horizontal. Le choix est memorise : une preference
 * d'espace de travail ne doit pas etre a refaire a chaque session.
 */
@Component({
  selector: 'app-main-layout',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatMenuModule,
    MatTooltipModule,
    TranslatePipe,
    IconComponent,
    BrandComponent,
    CommandPaletteComponent,
  ],
  templateUrl: './main-layout.component.html',
  styleUrl: './main-layout.component.scss',
})
export class MainLayoutComponent implements OnInit, OnDestroy {
  private readonly auth = inject(AuthService);
  private readonly realtime = inject(RealtimeService);
  private readonly router = inject(Router);
  protected readonly theme = inject(ThemeService);
  protected readonly palette = inject(CommandPaletteService);
  protected readonly i18n = inject(I18nService);

  protected readonly user = this.auth.user;
  protected readonly role = this.auth.role;
  protected readonly live = this.realtime.connected;

  /** Rail d'icones (bureau). */
  protected readonly collapsed = signal(this.readCollapsed());
  /** Tiroir superpose (mobile) — etat distinct : ce sont deux comportements. */
  protected readonly mobileOpen = signal(false);

  private readonly sections: NavSection[] = [
    {
      titleKey: null,
      items: [
        { path: '/dashboard', labelKey: 'nav.dashboard', icon: 'space_dashboard', minRole: 'MANAGER' },
        { path: '/insight', labelKey: 'nav.insight', icon: 'query_stats', minRole: 'MANAGER' },
        { path: '/topics', labelKey: 'nav.topics', icon: 'bubble_chart', minRole: 'MANAGER' },
        { path: '/digest', labelKey: 'nav.digest', icon: 'mail', minRole: 'MANAGER' },
        { path: '/tickets', labelKey: 'nav.tickets', icon: 'confirmation_number', minRole: null },
        // Section Travail et non Administration : deposer le PDF d'un client est
        // une tache de traitement quotidien (S7-J4).
        { path: '/intake', labelKey: 'nav.intake', icon: 'scanner', minRole: null },
      ],
    },
    {
      titleKey: 'nav.administration',
      items: [
        { path: '/imports', labelKey: 'nav.imports', icon: 'upload_file', minRole: 'ADMIN' },
        { path: '/knowledge', labelKey: 'nav.knowledge', icon: 'menu_book', minRole: 'ADMIN' },
        { path: '/admin/users', labelKey: 'nav.team', icon: 'group', minRole: 'ADMIN' },
      ],
    },
  ];

  /** Sections filtrees par role : une section entierement interdite disparait. */
  protected readonly visibleSections = computed(() =>
    this.sections
      .map((section) => ({ ...section, items: section.items.filter((i) => this.canSee(i.minRole)) }))
      .filter((section) => section.items.length > 0),
  );

  protected readonly roleLabel = computed(() => {
    const def = labelOf(ROLE_LABELS, this.role());
    return def ? this.i18n.t(def.key) : '';
  });

  /** Initiales pour l'avatar : « firas@gmail.com » donne « FI ». */
  protected readonly initials = computed(() => {
    const email = this.user()?.email ?? '';
    const name = email.split('@')[0];
    const parts = name.split(/[.\-_]/).filter(Boolean);
    const raw = parts.length >= 2 ? parts[0][0] + parts[1][0] : name.slice(0, 2);
    return raw.toUpperCase() || '?';
  });

  /** Raccourci affiche : le symbole Commande sur Mac, « Ctrl » ailleurs. */
  protected readonly shortcutKey = /Mac|iPhone|iPad/i.test(navigator.platform) ? '⌘' : 'Ctrl';

  ngOnInit(): void {
    // Une seule connexion temps reel pour toute la session applicative.
    this.realtime.connect();

    // Sur mobile le tiroir recouvre la page : le laisser ouvert apres une
    // navigation obligerait a le fermer a la main a chaque fois.
    this.router.events
      .pipe(filter((e) => e instanceof NavigationEnd))
      .subscribe(() => this.mobileOpen.set(false));
  }

  ngOnDestroy(): void {
    this.realtime.disconnect();
  }

  protected toggleCollapsed(): void {
    const next = !this.collapsed();
    this.collapsed.set(next);
    try {
      localStorage.setItem(SIDEBAR_KEY, String(next));
    } catch {
      /* stockage indisponible : la preference vaut pour la session */
    }
  }

  protected toggleMobile(): void {
    this.mobileOpen.update((v) => !v);
  }

  protected logout(): void {
    this.realtime.disconnect();
    this.auth.logout();
  }

  private canSee(minRole: NavItem['minRole']): boolean {
    if (minRole === null) {
      return true;
    }
    const role = this.role();
    if (minRole === 'ADMIN') {
      return role === 'ADMIN';
    }
    return role === 'ADMIN' || role === 'MANAGER';
  }

  private readCollapsed(): boolean {
    try {
      return localStorage.getItem(SIDEBAR_KEY) === 'true';
    } catch {
      return false;
    }
  }
}
