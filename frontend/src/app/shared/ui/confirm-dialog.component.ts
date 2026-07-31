import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { TranslatePipe } from '../../core/i18n/t.pipe';
import { IconComponent } from './icon.component';

export interface ConfirmDialogData {
  title: string;
  /** Ce qui va se passer, formule a l'indicatif. Pas « etes-vous sur ? ». */
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  /** `true` quand l'action est difficile ou impossible a annuler. */
  destructive?: boolean;
  icon?: string;
}

/**
 * Dialogue de confirmation.
 *
 * Reserve aux actions **difficiles a annuler** — dans cette application, la
 * fusion de deux tickets. Confirmer une action reversible entraine
 * l'utilisateur a valider sans lire, et le jour ou la question compte
 * vraiment, il clique quand meme.
 *
 * Deux details qui changent l'utilite du dialogue :
 *  - le message **decrit la consequence** (« Le ticket #12 sera rattache au
 *    #8 ») au lieu de demander « etes-vous sur ? » — la reponse est alors une
 *    decision informee ;
 *  - l'action destructive **n'est pas** le bouton par defaut du focus : le
 *    focus va sur Annuler, la touche Entree ne detruit donc rien.
 */
@Component({
  selector: 'app-confirm-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatDialogModule, TranslatePipe, IconComponent],
  template: `
    <div class="confirm">
      <div class="confirm__head">
        <span class="confirm__icon" [class.confirm__icon--danger]="data.destructive">
          <app-icon [name]="data.icon ?? (data.destructive ? 'warning' : 'help')" [size]="20" />
        </span>
        <div>
          <h2 class="confirm__title">{{ data.title }}</h2>
          <p class="confirm__msg">{{ data.message }}</p>
        </div>
      </div>

      <div class="confirm__actions">
        <button type="button" class="btn btn--secondary" cdkFocusInitial (click)="close(false)">
          {{ data.cancelLabel ?? ('common.cancel' | t) }}
        </button>
        <button
          type="button"
          class="btn"
          [class.btn--danger]="data.destructive"
          [class.btn--primary]="!data.destructive"
          (click)="close(true)"
        >
          {{ data.confirmLabel ?? ('common.confirm' | t) }}
        </button>
      </div>
    </div>
  `,
  styles: [
    `
      .confirm { padding: var(--space-5); max-width: 420px; }

      .confirm__head {
        display: flex;
        gap: var(--space-3);
        align-items: flex-start;
      }

      .confirm__icon {
        display: grid;
        place-items: center;
        width: 36px;
        height: 36px;
        flex: none;
        border-radius: var(--radius-md);
        background: var(--accent-soft-bg);
        color: var(--accent-soft-fg);
      }

      .confirm__icon--danger {
        background: var(--danger-bg);
        color: var(--danger-fg);
      }

      .confirm__title {
        margin: 0;
        font-size: var(--text-lg);
        font-weight: var(--weight-semibold);
        letter-spacing: var(--tracking-snug);
      }

      .confirm__msg {
        margin: var(--space-2) 0 0;
        font-size: var(--text-base);
        color: var(--text-secondary);
        line-height: var(--leading-normal);
      }

      .confirm__actions {
        display: flex;
        justify-content: flex-end;
        gap: var(--space-2);
        margin-top: var(--space-6);
      }
    `,
  ],
})
export class ConfirmDialogComponent {
  protected readonly data = inject<ConfirmDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<ConfirmDialogComponent, boolean>);

  protected close(result: boolean): void {
    this.ref.close(result);
  }
}
