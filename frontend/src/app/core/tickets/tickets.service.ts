import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PageResponse, TicketDetail, TicketQuery, TicketSummary } from '../models/ticket.models';

/** Lecture paginee/filtree des tickets. Tri et pagination sont resolus cote serveur. */
@Injectable({ providedIn: 'root' })
export class TicketsService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/api/tickets`;

  list(query: TicketQuery): Observable<PageResponse<TicketSummary>> {
    let params = new HttpParams();
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== null && value !== '') {
        params = params.set(key, String(value));
      }
    }
    return this.http.get<PageResponse<TicketSummary>>(this.base, { params });
  }

  /** Fiche complete : ticket + analyse + mots-cles + tickets similaires (S4-J4). */
  detail(id: number): Observable<TicketDetail> {
    return this.http.get<TicketDetail>(`${this.base}/${id}`);
  }

  /** Correction humaine d'un champ d'analyse ; renvoie la fiche mise a jour. */
  annotate(id: number, field: string, value: string): Observable<TicketDetail> {
    return this.http.post<TicketDetail>(`${this.base}/${id}/annotations`, { field, value });
  }

  /** Marque ce ticket comme doublon de `targetId`. */
  merge(id: number, targetId: number): Observable<TicketDetail> {
    return this.http.post<TicketDetail>(`${this.base}/${id}/merge`, { targetId });
  }
}
