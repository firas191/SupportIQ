import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Draft, DraftStatus, DraftTone } from '../models/draft.models';

/**
 * Brouillons de reponse (S5-J4).
 *
 * `latest` renvoie `null` quand aucun brouillon n'existe : le backend repond 204
 * et Angular transforme un corps vide en `null`. C'est le cas nominal — un
 * ticket qu'on ouvre pour la premiere fois — et il ne doit pas transiter par la
 * branche d'erreur.
 */
@Injectable({ providedIn: 'root' })
export class DraftsService {
  private readonly http = inject(HttpClient);
  private readonly api = environment.apiBaseUrl;

  latest(ticketId: number): Observable<Draft | null> {
    return this.http.get<Draft | null>(`${this.api}/api/tickets/${ticketId}/draft`);
  }

  generate(ticketId: number, tone: DraftTone): Observable<Draft> {
    return this.http.post<Draft>(`${this.api}/api/tickets/${ticketId}/draft`, { tone });
  }

  /**
   * Corriger (EDITED), valider (SENT) ou ecarter (REJECTED).
   *
   * Valider declenche l'envoi au client quand il est actif. L'appel **reussit
   * meme si le courriel echoue** : la reponse porte alors `deliveryError`. La
   * decision humaine est acquise, seule la livraison a manque.
   */
  review(draftId: number, status: DraftStatus, content?: string): Observable<Draft> {
    return this.http.patch<Draft>(`${this.api}/api/drafts/${draftId}`, { status, content });
  }

  /** Rejoue l'envoi d'une reponse deja validee, apres un echec de livraison. */
  resend(draftId: number): Observable<Draft> {
    return this.http.post<Draft>(`${this.api}/api/drafts/${draftId}/send`, {});
  }
}
