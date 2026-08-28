import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Alert } from '../models/alert.models';

/** Alertes d'anomalie (S7-J2). Reserve aux responsables cote serveur. */
@Injectable({ providedIn: 'root' })
export class AlertsService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/api/alerts`;

  list(): Observable<Alert[]> {
    return this.http.get<Alert[]>(this.base);
  }

  /**
   * Declenche une mesure immediate.
   *
   * Le detecteur tourne deja toutes les cinq minutes ; ce bouton sert a la demonstration, ou le pic
   * vient d'etre injecte et ou attendre le passage suivant n'aurait pas de sens.
   */
  detect(): Observable<Alert[]> {
    return this.http.post<Alert[]>(`${this.base}/detect?lookback=3`, {});
  }

  /** Prise en charge. Repond 409 si quelqu'un d'autre est passe avant. */
  acknowledge(id: number): Observable<Alert> {
    return this.http.post<Alert>(`${this.base}/${id}/ack`, {});
  }
}
