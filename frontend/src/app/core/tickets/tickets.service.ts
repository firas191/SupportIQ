import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PageResponse, TicketQuery, TicketSummary } from '../models/ticket.models';

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
}
