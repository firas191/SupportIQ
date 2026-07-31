import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AuthService } from '../../../core/auth/auth.service';
import { I18nService } from '../../../core/i18n/i18n.service';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { TranslationKey } from '../../../core/i18n/translations.fr';
import { Role } from '../../../core/models/auth.models';
import { ToastService } from '../../../core/ui/toast.service';
import { ROLE_LABELS } from '../../../shared/labels';
import { IconComponent } from '../../../shared/ui/icon.component';
import { PageHeaderComponent } from '../../../shared/ui/page-header.component';

/**
 * Creation d'un compte (reserve aux administrateurs).
 *
 * Le choix du role etait une liste deroulante affichant « AGENT / MANAGER /
 * ADMIN » : trois sigles, aucune indication de ce qu'ils autorisent. Un
 * administrateur qui attribue un role a l'aveugle donne trop de droits par
 * prudence — c'est un probleme de securite cause par l'interface.
 *
 * Les trois roles sont donc presentes en cartes selectionnables, chacune
 * enoncant ce qu'elle permet. Le choix devient informe, et le role le moins
 * privilegie est celui propose par defaut.
 *
 * La force du mot de passe est evaluee en direct : un retour pendant la saisie
 * fait bien plus pour la qualite des mots de passe qu'un message d'erreur
 * apres coup.
 */
@Component({
  selector: 'app-register',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, TranslatePipe, PageHeaderComponent, IconComponent],
  templateUrl: './register.component.html',
  styleUrl: './register.component.scss',
})
export class RegisterComponent {
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);

  protected readonly loading = signal(false);
  protected readonly showPassword = signal(false);
  protected readonly passwordValue = signal('');

  /** Ordre volontaire : du moins au plus privilegie. */
  protected readonly roles: { value: Role; def: (typeof ROLE_LABELS)[string] }[] = (
    ['AGENT', 'MANAGER', 'ADMIN'] as Role[]
  ).map((value) => ({ value, def: ROLE_LABELS[value] }));

  protected readonly form = this.fb.nonNullable.group({
    fullName: ['', [Validators.required]],
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(8)]],
    role: ['AGENT' as Role, [Validators.required]],
  });

  constructor() {
    this.form.controls.password.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe((v) => this.passwordValue.set(v));
  }

  /**
   * Force du mot de passe : longueur d'abord, puis variete des caracteres.
   * La longueur pese le plus car c'est elle qui protege reellement contre une
   * attaque par force brute — un mot de passe court reste faible meme avec des
   * symboles.
   */
  protected readonly strength = computed(() => {
    const value = this.passwordValue();
    if (!value) {
      return { score: 0, label: '', tone: 'neutral' };
    }
    let score = 0;
    if (value.length >= 8) score++;
    if (value.length >= 12) score++;
    if (/[A-Z]/.test(value) && /[a-z]/.test(value)) score++;
    if (/\d/.test(value) && /[^\w\s]/.test(value)) score++;

    const scale: { key: TranslationKey; tone: string }[] = [
      { key: 'team.strengthTooShort', tone: 'danger' },
      { key: 'team.strengthWeak', tone: 'danger' },
      { key: 'team.strengthOk', tone: 'warning' },
      { key: 'team.strengthGood', tone: 'success' },
      { key: 'team.strengthExcellent', tone: 'success' },
    ];
    const step = scale[score];
    return { score, label: this.i18n.t(step.key), tone: step.tone };
  });

  protected selectRole(role: Role): void {
    this.form.controls.role.setValue(role);
  }

  protected togglePassword(): void {
    this.showPassword.update((v) => !v);
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    this.loading.set(true);
    const email = this.form.controls.email.value;

    this.auth.register(this.form.getRawValue()).subscribe({
      next: () => {
        this.loading.set(false);
        this.toast.success(this.i18n.t('team.created', { email }));
        this.form.reset({ role: 'AGENT' });
        this.passwordValue.set('');
      },
      error: (err: HttpErrorResponse) => {
        this.loading.set(false);
        this.toast.error(this.i18n.t(err.status === 409 ? 'team.emailTaken' : 'team.createFailed'));
      },
    });
  }
}
