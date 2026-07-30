import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Kpi, Trends } from '../models/dashboard.models';

/** Appels de l'API dashboard (S4-J1). Reserve aux MANAGER+ cote serveur. */
@Injectable({ providedIn: 'root' })
export class DashboardService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/api/dashboard`;

  kpis(): Observable<Kpi> {
    return this.http.get<Kpi>(`${this.base}/kpis`);
  }

  /** Toutes les series des graphiques en un appel (fenetre en jours). */
  trends(days: number): Observable<Trends> {
    return this.http.get<Trends>(`${this.base}/trends`, {
      params: new HttpParams().set('days', days),
    });
  }
}
