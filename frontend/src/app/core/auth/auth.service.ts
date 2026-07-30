import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  CurrentUser,
  LoginRequest,
  MeResponse,
  RegisterRequest,
  Role,
  TokenResponse,
} from '../models/auth.models';
import { TokenStore } from './token.store';

/**
 * Etat d'authentification expose en signals. L'identite est derivee du JWT (claims sub/role),
 * ce qui evite un appel reseau a chaque chargement ; /me reste disponible pour re-synchroniser.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly tokens = inject(TokenStore);
  private readonly router = inject(Router);
  private readonly base = `${environment.apiBaseUrl}/api/auth`;

  private readonly _user = signal<CurrentUser | null>(this.decodeUser(this.tokens.accessToken));

  readonly user = this._user.asReadonly();
  readonly isAuthenticated = computed(() => this._user() !== null);
  readonly role = computed<Role | null>(() => this._user()?.role ?? null);

  login(body: LoginRequest): Observable<TokenResponse> {
    return this.http.post<TokenResponse>(`${this.base}/login`, body).pipe(
      tap((res) => this.onTokens(res)),
    );
  }

  /** Creation d'un compte (ADMIN uniquement, garde cote backend). */
  register(body: RegisterRequest): Observable<unknown> {
    return this.http.post(`${this.base}/register`, body);
  }

  refresh(): Observable<TokenResponse> {
    const refreshToken = this.tokens.refreshToken;
    return this.http.post<TokenResponse>(`${this.base}/refresh`, { refreshToken }).pipe(
      tap((res) => this.onTokens(res)),
    );
  }

  logout(): void {
    const refreshToken = this.tokens.refreshToken;
    if (refreshToken) {
      // Revocation best-effort cote serveur ; on nettoie l'etat local quoi qu'il arrive.
      this.http.post(`${this.base}/logout`, { refreshToken }).subscribe({ error: () => undefined });
    }
    this.tokens.clear();
    this._user.set(null);
    // Retour explicite au login : sans cela on resterait sur une page protegee videe de ses donnees.
    this.router.navigate(['/login']);
  }

  me(): Observable<MeResponse> {
    return this.http.get<MeResponse>(`${this.base}/me`);
  }

  private onTokens(res: TokenResponse): void {
    this.tokens.set(res.accessToken, res.refreshToken);
    this._user.set(this.decodeUser(res.accessToken));
  }

  private decodeUser(token: string | null): CurrentUser | null {
    if (!token) {
      return null;
    }
    try {
      const base64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
      const json = decodeURIComponent(
        atob(base64)
          .split('')
          .map((c) => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
          .join(''),
      );
      const payload = JSON.parse(json) as { sub?: string; role?: Role; exp?: number };
      if (!payload.sub || !payload.role) {
        return null;
      }
      if (payload.exp && Date.now() >= payload.exp * 1000) {
        return null; // access token expire : traite comme deconnecte (le 401 declenche le refresh)
      }
      return { email: payload.sub, role: payload.role };
    } catch {
      return null;
    }
  }
}
