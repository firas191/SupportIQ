import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Location } from '@angular/common';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { TranslatePipe } from '../../core/i18n/t.pipe';
import { IconComponent } from '../../shared/ui/icon.component';
import { IllustrationComponent } from '../../shared/ui/illustration.component';

/**
 * Page « adresse introuvable ».
 *
 * Auparavant, une adresse inconnue etait silencieusement redirigee vers
 * l'accueil : l'utilisateur se retrouvait ailleurs sans savoir pourquoi et
 * croyait avoir mal clique. Une page dediee dit ce qui s'est passe et propose
 * deux sorties — revenir en arriere, ou rejoindre son ecran d'accueil.
 *
 * La destination « accueil » depend du role : un agent n'a pas acces a la vue
 * d'ensemble, l'y envoyer produirait une seconde redirection.
 */
@Component({
  selector: 'app-not-found',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, TranslatePipe, IconComponent, IllustrationComponent],
  template: `
    <div class="nf">
      <app-illustration name="not-found" [size]="180" />
      <span class="nf__code t-num">404</span>
      <h1 class="nf__title">{{ 'notFound.title' | t }}</h1>
      <p class="nf__text">{{ 'notFound.text' | t }}</p>

      <div class="nf__actions">
        <button type="button" class="btn btn--secondary" (click)="back()">
          <app-icon name="arrow_back" [size]="17" /> {{ 'notFound.previous' | t }}
        </button>
        <a class="btn btn--primary" [routerLink]="home">
          <app-icon name="home" [size]="17" /> {{ 'notFound.home' | t }}
        </a>
      </div>
    </div>
  `,
  styles: [
    `
      :host {
        display: grid;
        place-items: center;
        min-height: 70vh;
        padding: var(--space-6);
      }

      .nf {
        display: flex;
        flex-direction: column;
        align-items: center;
        text-align: center;
        max-width: 420px;
        animation: rise-in var(--duration-slow) var(--ease-out) both;
      }

      /* Le code d'erreur en grand mais tres pale : il donne le ton sans devenir
         le message. Ce que l'utilisateur doit lire, c'est le titre. */
      .nf__code {
        margin-top: var(--space-3);
        font-size: var(--text-sm);
        font-weight: var(--weight-bold);
        letter-spacing: 0.22em;
        color: var(--text-disabled);
      }

      .nf__title {
        margin: var(--space-2) 0 0;
        font-size: var(--text-2xl);
        font-weight: var(--weight-semibold);
        letter-spacing: var(--tracking-tight);
      }

      .nf__text {
        margin: var(--space-2) 0 var(--space-6);
        color: var(--text-tertiary);
        line-height: var(--leading-normal);
      }

      .nf__actions {
        display: flex;
        gap: var(--space-2);
        flex-wrap: wrap;
        justify-content: center;
      }
    `,
  ],
})
export class NotFoundComponent {
  private readonly location = inject(Location);
  private readonly auth = inject(AuthService);

  protected readonly home =
    this.auth.role() === 'MANAGER' || this.auth.role() === 'ADMIN' ? '/dashboard' : '/tickets';

  protected back(): void {
    this.location.back();
  }
}
