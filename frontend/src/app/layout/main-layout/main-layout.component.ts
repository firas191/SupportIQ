import { Component, OnDestroy, OnInit, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { AuthService } from '../../core/auth/auth.service';
import { RealtimeService } from '../../core/realtime/realtime.service';

@Component({
  selector: 'app-main-layout',
  standalone: true,
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatToolbarModule,
    MatSidenavModule,
    MatListModule,
    MatIconModule,
    MatButtonModule,
  ],
  templateUrl: './main-layout.component.html',
  styleUrl: './main-layout.component.scss',
})
export class MainLayoutComponent implements OnInit, OnDestroy {
  private readonly auth = inject(AuthService);
  private readonly realtime = inject(RealtimeService);

  readonly user = this.auth.user;
  readonly role = this.auth.role;
  /** Etat du canal temps reel, affiche dans la barre du haut (S4-J5). */
  readonly live = this.realtime.connected;

  /** Dashboard reserve aux MANAGER+ : on masque le lien pour un AGENT (coherence UI/RBAC). */
  readonly isManager = computed(() => {
    const r = this.role();
    return r === 'MANAGER' || r === 'ADMIN';
  });

  ngOnInit(): void {
    // Une seule connexion WebSocket pour toute la session applicative.
    this.realtime.connect();
  }

  ngOnDestroy(): void {
    this.realtime.disconnect();
  }

  logout(): void {
    this.realtime.disconnect();
    this.auth.logout();
  }
}
