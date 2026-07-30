import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Location } from '@angular/common';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { IconComponent } from '../../shared/ui/icon.component';

/**
 * Page « adresse introuvable ».
 *
 * Auparavant, une adresse inconnue etait silencieusement redirigee vers
 * l'accueil. C'est le pire des comportements : l'utilisateur se retrouve
 * ailleurs sans savoir pourquoi, et croit avoir mal clique. Une page dediee
 * dit ce qui s'est passe et propose deux sorties — revenir en arriere, ou
 * rejoindre son ecran d'accueil.
 *
 * La destination « accueil » depend du role : un agent n'a pas acces a la vue
 * d'ensemble ; l'y envoyer produirait une seconde redirection, donc une
 * seconde surprise.
 */
@Component({
  selector: 'app-not-found',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, IconComponent],
  template: `
    <div class="nf">
      <span class="nf__code t-num">404</span>
      <h1 class="nf__title">Cette page n'existe pas</h1>
      <p class="nf__text">
        Le lien est peut-être incomplet, ou la page a été déplacée depuis que vous l'avez
        enregistrée.
      </p>

      <div class="nf__actions">
        <button type="button" class="btn btn--secondary" (click)="back()">
          <app-icon name="arrow_back" [size]="17" /> Page précédente
        </button>
        <a class="btn btn--primary" [routerLink]="home">
          <app-icon name="home" [size]="17" /> Retour à l'accueil
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

      /* Le code d'erreur en très grand mais très pâle : il donne le ton sans
         devenir le message. Ce que l'utilisateur doit lire, c'est le titre. */
      .nf__code {
        font-size: 88px;
        font-weight: var(--weight-bold);
        line-height: 1;
        letter-spacing: var(--tracking-tight);
        background: var(--brand-gradient);
        -webkit-background-clip: text;
        background-clip: text;
        color: transparent;
        opacity: 0.32;
      }

      .nf__title {
        margin: var(--space-4) 0 0;
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
