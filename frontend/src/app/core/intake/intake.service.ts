import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ConfirmResult, ExtractionResult, ProposedTicket } from '../models/intake.models';

/** Ingestion de documents non structures (S7-J4). Accessible aux AGENT+ cote serveur. */
@Injectable({ providedIn: 'root' })
export class IntakeService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/api/intake`;

  /** Etape 1 : extraction. Ne cree rien — le lot revient pour relecture. */
  extract(file: File): Observable<ExtractionResult> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<ExtractionResult>(`${this.base}/documents`, form);
  }

  /**
   * Etape 2 : creation des demandes retenues.
   *
   * Le lot repart tel que l'agent l'a corrige. Il n'est pas relu depuis un
   * stockage serveur, contrairement a l'import de fichier structure : un CSV de
   * 10 000 lignes ne peut pas transiter par le navigateur, une douzaine de
   * demandes deja affichees a l'ecran, si.
   */
  confirm(tickets: ProposedTicket[]): Observable<ConfirmResult> {
    return this.http.post<ConfirmResult>(`${this.base}/confirm`, {
      tickets: tickets.map((t) => ({
        subject: t.subject,
        body: t.body,
        customerEmail: t.customerEmail,
        language: t.language,
      })),
    });
  }
}
