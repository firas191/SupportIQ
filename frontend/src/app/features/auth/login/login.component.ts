import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { ThemeService } from '../../../core/theme/theme.service';
import { IconComponent } from '../../../shared/ui/icon.component';

/**
 * Page de connexion.
 *
 * Premiere impression du produit : c'est le seul ecran que tout le monde voit,
 * y compris un recruteur ou un jury a qui l'on fait une demonstration. Il a
 * donc droit a un traitement particulier — un ecran scinde, formulaire d'un
 * cote, panneau de marque de l'autre.
 *
 * Le panneau n'est pas decoratif : il enonce en trois lignes ce que fait le
 * produit. Un utilisateur qui arrive par un lien d'invitation apprend ou il
 * est avant meme de s'identifier.
 *
 * Deux details de comportement qui valent plus que l'esthetique :
 *
 *  - **Redirection selon le role.** Un agent n'a pas acces a la vue
 *    d'ensemble. L'envoyer vers /dashboard pour que la garde le renvoie
 *    aussitot vers /tickets produit un clignotement et l'impression d'une
 *    erreur. On calcule la destination des la reception du jeton.
 *
 *  - **Message d'erreur unique.** « Identifiants invalides » sans preciser si
 *    c'est l'adresse ou le mot de passe : distinguer les deux permettrait
 *    d'enumerer les comptes existants. La panne serveur, elle, est distinguee —
 *    l'utilisateur doit savoir que le probleme n'est pas de son cote.
 */
@Component({
  selector: 'app-login',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, IconComponent],
  templateUrl: './login.component.html',
  styleUrl: './login.component.scss',
})
export class LoginComponent {
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly theme = inject(ThemeService);

  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly showPassword = signal(false);

  protected readonly form = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required]],
  });

  protected readonly highlights = [
    { icon: 'bolt', text: 'Chaque ticket classe et priorise des son arrivee' },
    { icon: 'content_copy', text: 'Les demandes en double reperees et regroupees' },
    { icon: 'monitoring', text: 'La charge et la satisfaction, suivies en direct' },
  ];

  protected togglePassword(): void {
    this.showPassword.update((v) => !v);
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    this.loading.set(true);
    this.error.set(null);

    this.auth.login(this.form.getRawValue()).subscribe({
      next: () => {
        const role = this.auth.role();
        const home = role === 'MANAGER' || role === 'ADMIN' ? '/dashboard' : '/tickets';
        this.router.navigateByUrl(home);
      },
      error: (err: HttpErrorResponse) => {
        this.error.set(
          err.status === 0
            ? 'Service indisponible. Verifiez votre connexion, puis reessayez.'
            : 'Adresse e-mail ou mot de passe incorrect.',
        );
        this.loading.set(false);
      },
    });
  }
}
